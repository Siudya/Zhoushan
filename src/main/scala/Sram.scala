package zhoushan

import chisel3._
import chisel3.util._

// S011HD1P_X32Y2D128 from SoC team
trait SramParameters {
  val SramAddrWidth = 6
  val SramDepth = 64
  val SramDataWidth = 128
}

class Sram(id: Int) extends Module with SramParameters {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val wen = Input(Bool())
    val addr = Input(UInt(SramAddrWidth.W))
    val wdata = Input(UInt(SramDataWidth.W))
    val rdata = Output(UInt(SramDataWidth.W))
  })

  val sram = SyncReadMem(SramDepth, UInt(SramDataWidth.W))

  when (io.en) {
    when (io.wen) {
      sram.write(io.addr, io.wdata)
      io.rdata := DontCare
    } .otherwise {
      io.rdata := sram.read(io.addr)
    }
  } .otherwise {
    io.rdata := DontCare
  }

  if (Settings.DebugMsgSram) {
    when (io.en) {
      when (io.wen) {
        printf("%d: SRAM[%d] addr=%x wdata=%x\n", DebugTimer(), id.U, io.addr, io.wdata)
      } .otherwise {
        when (id.U >= 20.U) {
          printf("%d: SRAM[%d] addr=%x rdata=%x\n", DebugTimer(), id.U, io.addr, io.rdata)
        }
      }
    }
  }
}
