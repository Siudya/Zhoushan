/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._

class StoreQueueEntry extends Bundle with AxiParameters {
  val addr = UInt(AxiAddrWidth.W)
  val wdata = UInt(AxiDataWidth.W)
  val wmask = UInt((AxiDataWidth / 8).W)
  val wsize = UInt(2.W)
  val valid = Bool()
}

class StoreQueue extends Module with ZhoushanConfig {
  val entries = StoreQueueSize

  val io = IO(new Bundle {
    // flush request from ROB
    val flush = Input(Bool())
    // from EX stage - LSU
    val in_st = Flipped(new CacheBusIO)
    val in_ld = Flipped(new CacheBusIO)
    // to data cache
    val out_st = new CacheBusIO   // id = ZhoushanConfig.SqStoreId
    val out_ld = new CacheBusIO   // id = ZhoushanConfig.SqLoadId
    // deq request from ROB
    val deq_req = Input(Bool())
    val sqEmpty = Output(Bool())
  })

  val sq = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new StoreQueueEntry))))
  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = RegInit(false.B)
  val empty = (enq_ptr.value === deq_ptr.value) && !maybe_full
  val full = (enq_ptr.value === deq_ptr.value) && maybe_full
  io.sqEmpty := empty

  /* ---------- State Machine -------- */

  val deq_idle :: deq_wait :: Nil = Enum(2)
  val deq_state = RegInit(deq_idle)

  val enq_idle :: enq_wait :: Nil = Enum(2)
  val enq_state = RegInit(enq_idle)

  val flush_idle :: flush_wait :: Nil = Enum(2)
  val flush_state = RegInit(flush_idle)

  /* ---------- Store Logic ---------- */

  // dequeue

  val deq_req_counter = RegInit(UInt((log2Up(entries) + 1).W), 0.U)
  val deq_req_empty = (deq_req_counter === 0.U)

  val deq_fire = WireInit(false.B)
  val deq_valid = !empty && !deq_req_empty
  val deq_ready = io.out_st.req.ready && (deq_state === deq_idle)
  deq_fire := deq_valid && deq_ready

  when (io.deq_req && !deq_fire) {
    deq_req_counter := deq_req_counter + 1.U
  } .elsewhen (!io.deq_req && deq_fire) {
    deq_req_counter := deq_req_counter - 1.U
  }

  when (deq_fire) {
    sq(deq_ptr.value).valid := false.B
    deq_ptr.inc()

    if (DebugStoreQueue) {
      printf("%d: [SQ - D] idx=%d v=%x addr=%x wdata=%x wmask=%x\n", DebugTimer(), deq_ptr.value,
             sq(deq_ptr.value).valid, sq(deq_ptr.value).addr, sq(deq_ptr.value).wdata, sq(deq_ptr.value).wmask)
    }
  }

  switch (deq_state) {
    is (deq_idle) {
      when (deq_fire) {
        deq_state := deq_wait
      }
    }
    is (deq_wait) {
      when (io.out_st.resp.fire) {
        deq_state := deq_idle
      }
    }
  }

  // enqueue (be careful that enqueue is done after dequeue)

  val enq_valid = io.in_st.req.valid
  val enq_ready = (!full || deq_fire) && (enq_state === enq_idle) && (flush_state === flush_idle)
  val enq_fire = enq_valid && enq_ready

  when (enq_fire) {
    val enq = Wire(new StoreQueueEntry)
    enq.addr := io.in_st.req.bits.addr
    enq.wdata := io.in_st.req.bits.wdata
    enq.wmask := io.in_st.req.bits.wmask
    enq.wsize := io.in_st.req.bits.size
    enq.valid := true.B
    sq(enq_ptr.value) := enq
    enq_ptr.inc()

    if (DebugStoreQueue) {
      printf("%d: [SQ - E] idx=%d v=%x addr=%x wdata=%x wmask=%x\n", DebugTimer(), enq_ptr.value,
             enq.valid, enq.addr, enq.wdata, enq.wmask)
    }
  }

  switch (enq_state) {
    is (enq_idle) {
      when (enq_fire) {
        enq_state := enq_wait
      }
    }
    is (enq_wait) {
      when (io.in_st.resp.fire) {
        enq_state := enq_idle
      }
    }
  }

  when (enq_fire =/= deq_fire) {
    maybe_full := enq_fire
  }

  /* ---------- Load Logic ----------- */

  val load_addr_match = WireInit(VecInit(Seq.fill(entries)(false.B)))

  for (i <- 0 until entries) {
    load_addr_match(i) := sq(i).valid && (sq(i).addr === io.in_ld.req.bits.addr)
  }

  val load_hit = Cat(load_addr_match.reverse).orR
  // todo: optimize the load logic

  /* ---------- Flush ---------------- */

  def flush_all() = {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
    for (i <- 0 until entries) {
      sq(i).valid := false.B
    }
  }

  switch (flush_state) {
    is (flush_idle) {
      // no flush signal
      when (io.flush) {
        when (deq_req_empty && !io.deq_req) {
          flush_all()
          if (DebugStoreQueue) {
            printf("%d: [SQ - F] SQ empty - OK\n", DebugTimer())
          }
        } .otherwise {
          flush_state := flush_wait
          if (DebugStoreQueue) {
            printf("%d: [SQ - F] SQ not empty - deq_req_counter=%d\n", DebugTimer(), deq_req_counter)
          }
        }
      }
    }
    is (flush_wait) {
      // flush signal, handle remaining deq request
      when (deq_req_empty) {
        flush_all()
        flush_state := flush_idle
        if (DebugStoreQueue) {
          printf("%d: [SQ - F] SQ empty - OK, clear all deq req\n", DebugTimer())
        }
      }
    }
  }

  /* ----- Cachebus Handshake -------- */

  io.in_st.req.ready       := enq_ready
  io.in_ld.req.ready       := io.out_ld.req.ready && !load_hit && (flush_state === flush_idle)

  io.in_st.resp.valid      := (enq_state === enq_wait)
  io.in_st.resp.bits.rdata := 0.U
  io.in_st.resp.bits.id    := 0.U

  io.in_ld.resp.valid      := io.out_ld.resp.valid
  io.in_ld.resp.bits.rdata := io.out_ld.resp.bits.rdata
  io.in_ld.resp.bits.id    := 0.U

  io.out_st.req.valid      := deq_valid && (deq_state === deq_idle)
  io.out_st.req.bits.addr  := sq(deq_ptr.value).addr
  io.out_st.req.bits.wdata := sq(deq_ptr.value).wdata
  io.out_st.req.bits.wmask := sq(deq_ptr.value).wmask
  io.out_st.req.bits.wen   := true.B
  io.out_st.req.bits.size  := sq(deq_ptr.value).wsize
  io.out_st.req.bits.id    := SqStoreId.U

  io.out_st.resp.ready     := (deq_state === deq_wait)

  io.out_ld.req.valid      := io.in_ld.req.valid && !io.in_ld.req.bits.wen && !load_hit && (flush_state === flush_idle)
  io.out_ld.req.bits.addr  := io.in_ld.req.bits.addr
  io.out_ld.req.bits.wdata := 0.U
  io.out_ld.req.bits.wmask := 0.U
  io.out_ld.req.bits.wen   := false.B
  io.out_ld.req.bits.size  := io.in_ld.req.bits.size
  io.out_ld.req.bits.id    := SqLoadId.U

  io.out_ld.resp.ready     := io.in_ld.resp.ready
}
