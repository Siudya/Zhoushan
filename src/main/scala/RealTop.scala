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
import xs.utils.dft.{HasIjtag, SimpleDftCore, SimpleDftParams}

class RealTop extends RawModule with HasIjtag with SimpleDftCore {
  val mName = "RealTop"
  val dftParams = SimpleDftParams()
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new OscpuSocAxiIO
    val slave = Flipped(new OscpuSocAxiIO)
  })

  private val (core, crossbar, core2axi) = withClockAndReset(masterClock, masterReset) {
    (
      Module(new Core),
      Module(new CoreBusCrossbarNto1(4)),
      Module(new CoreBus2Axi(new OscpuSocAxiIO))
    )
  }

  crossbar.io.in <> core.io.core_bus
  core2axi.in <> crossbar.io.out
  core2axi.out <> io.master

  val slave = io.slave
  slave.aw.ready    := false.B
  slave.w.ready     := false.B
  slave.b.valid     := false.B
  slave.b.bits.resp := 0.U
  slave.b.bits.id   := 0.U
  slave.ar.ready    := false.B
  slave.r.valid     := false.B
  slave.r.bits.resp := 0.U
  slave.r.bits.data := 0.U
  slave.r.bits.last := false.B
  slave.r.bits.id   := 0.U

  if(dftInit) {
    println("DFT components are inserted")
  }
}
