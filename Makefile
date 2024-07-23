default: verilog

verilog:
	@sed -i -E 's/Chisel\./chisel3\./g' difftest/src/main/scala/Difftest.scala
	@mkdir -p build/rtl
	mill -i Zhoushan.runMain zhoushan.TopMain -td build/rtl --target systemverilog --split-verilog --full-stacktrace
	@mkdir -p build/macro
	@mv build/rtl/sram_array_*.sv build/macro/
	@mv build/rtl/ClockGate.sv build/macro/

idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init
	cd rocket-chip && git submodule update --init cde hardfloat

comp:
	@sed -i -E 's/Chisel\./chisel3\./g' difftest/src/main/scala/Difftest.scala
	mill -i Zhoushan.compile

help:
	mill -i Zhoushan.runMain zhoushan.TopMain --help

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: clean comp verilog
