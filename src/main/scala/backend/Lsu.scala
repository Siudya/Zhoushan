package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

class Lsu extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val ecp = Output(new ExCommitPacket)
    val busy = Output(Bool())
    val dmem = new CacheBusIO
    val flush = Input(Bool())
  })

  val lsu_update = io.uop.valid

  val uop = HoldUnlessWithFlush(io.uop, lsu_update, io.flush)
  val in1 = HoldUnlessWithFlush(io.in1, lsu_update, io.flush)
  val in2 = HoldUnlessWithFlush(io.in2, lsu_update, io.flush)

  val completed = RegInit(true.B)
  when (lsu_update) {
    completed := false.B
  }

  val is_mem = (uop.fu_code === FU_MEM)
  val is_load = (uop.mem_code === MEM_LD || uop.mem_code === MEM_LDU)
  val is_store = (uop.mem_code === MEM_ST)

  val s_idle :: s_wait_r :: s_wait_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val req = io.dmem.req
  val resp = io.dmem.resp

  val addr = (in1 + SignExt32_64(uop.imm))(31, 0)
  val addr_offset = addr(2, 0)
  val wdata = in2

  val mmio = RegInit(false.B)
  when (uop.valid) {
    mmio := (addr(31) === 0.U)
  }

  val mask = MuxLookup(addr_offset, 0.U, Array(
    "b000".U -> "b11111111".U(8.W),
    "b001".U -> "b11111110".U(8.W),
    "b010".U -> "b11111100".U(8.W),
    "b011".U -> "b11111000".U(8.W),
    "b100".U -> "b11110000".U(8.W),
    "b101".U -> "b11100000".U(8.W),
    "b110".U -> "b11000000".U(8.W),
    "b111".U -> "b10000000".U(8.W)
  ))
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "b00000001".U(8.W),
    MEM_HALF  -> "b00000011".U(8.W),
    MEM_WORD  -> "b00001111".U(8.W),
    MEM_DWORD -> "b11111111".U(8.W)
  ))

  req.bits.addr := Cat(addr(31, 3), Fill(3, 0.U))
  req.bits.ren := is_load
  req.bits.wdata := (wdata << (addr_offset << 3))(63, 0)
  req.bits.wmask := mask & ((wmask << addr_offset)(7, 0))
  req.bits.wen := is_store
  req.bits.user := 0.U
  req.bits.id := 0.U
  req.valid := uop.valid && (state === s_idle) &&
               (is_load || is_store) && (lsu_update || !completed)

  resp.ready := true.B  // must be always true due to potential flush signal

  val load_data = WireInit(UInt(64.W), 0.U)
  val store_valid = RegInit(false.B)

  switch (state) {
    is (s_idle) {
      when (is_load && req.fire()) {
        state := s_wait_r
        store_valid := false.B
      } .elsewhen (is_store && req.fire()) {
        state := s_wait_w
        store_valid := true.B
      }
    }
    is (s_wait_r) {
      when (resp.fire()) {
        load_data := resp.bits.rdata >> (addr_offset << 3)
        if (ZhoushanConfig.DebugLsu) {
          printf("%d: [LOAD ] pc=%x addr=%x rdata=%x -> %x\n", DebugTimer(),
                 uop.pc, addr, resp.bits.rdata, resp.bits.rdata >> (addr_offset << 3))
        }
        completed := true.B
        state := s_idle
      }
    }
    is (s_wait_w) {
      when (resp.fire()) {
        if (ZhoushanConfig.DebugLsu) {
          printf("%d: [STORE] pc=%x addr=%x wdata=%x -> %x wmask=%x\n", DebugTimer(),
                 uop.pc, addr, in2, req.bits.wdata, req.bits.wmask)
        }
        completed := true.B
        state := s_idle
      }
    }
  }

  // when flush, invalidate the current load/store request
  when (io.flush) {
    completed := true.B
    state := s_idle
  }

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(uop.mem_code === MEM_LD, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  ldu_out := Mux(uop.mem_code === MEM_LDU, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  load_out := MuxLookup(uop.mem_code, 0.U, Array(
    MEM_LD  -> ld_out,
    MEM_LDU -> ldu_out
  ))

  io.ecp := 0.U.asTypeOf(new ExCommitPacket)
  io.ecp.store_valid := store_valid
  io.ecp.mmio := mmio
  io.ecp.rd_data := load_out
  io.busy := req.valid || (state === s_wait_r && !resp.fire()) || (state === s_wait_w && !resp.fire())

  // raise an addr_unaligned exception
  //    half  -> offset = 111
  //    word  -> offset = 101/110/111
  //    dword -> offset != 000
  val addr_unaligned = RegInit(false.B)
  addr_unaligned := Mux(uop.fu_code === FU_MEM,
    MuxLookup(uop.mem_size, false.B, Array(
      MEM_HALF  -> (addr_offset === "b111".U),
      MEM_WORD  -> (addr_offset.asUInt() > "b100".U),
      MEM_DWORD -> (addr_offset =/= "b000".U)
    )), false.B)
  // todo: add this exception in CSR unit

}
