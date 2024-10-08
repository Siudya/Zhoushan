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
import zhoushan.Constant._

class Fence extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val ecp = Output(new ExCommitPacket)
    val fenceI = Output(Bool())
  })

  val uop = io.uop

  io.fenceI := uop.valid && (uop.fu_code === s"b$FU_SYS".U) && (uop.sys_code === s"b$SYS_FENCEI".U)

  /* when fence.i is commited
   *  1. mark it as a system jump
   *  2. flush the pipeline
   *  3. go to the instruction following fence.i
   */

  io.ecp := 0.U.asTypeOf(new ExCommitPacket)
  io.ecp.jmp_valid := true.B
  io.ecp.jmp := true.B
  io.ecp.jmp_pc := uop.npc
  io.ecp.mis := true.B

}
