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

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.FirtoolOption

object TopMain extends App {

  if (ZhoushanConfig.EnableDifftest) {
    (new circt.stage.ChiselStage).execute(args, Seq(
      chisel3.stage.ChiselGeneratorAnnotation(() => new SimTop())
    ))
  } else {
    (new circt.stage.ChiselStage).execute(args, Seq(
      FirtoolOption("-O=release"),
      FirtoolOption("--disable-all-randomization"),
      FirtoolOption("--disable-annotation-unknown"),
      FirtoolOption("--strip-debug-info"),
      FirtoolOption("--lower-memories"),
      FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
      FirtoolOption("--lowering-options=noAlwaysComb," +
        " disallowPortDeclSharing, disallowLocalVariables," +
        " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
        " disallowExpressionInliningInPorts, disallowMuxInlining"),
      ChiselGeneratorAnnotation(() => new RealTop())
    ))
  }
}
