package dana

import Chisel._

class SRAMInterface(
  val dataWidth: Int,
  val numReadPorts: Int,
  val numWritePorts: Int,
  val numReadWritePorts: Int,
  val sramDepth: Int
) extends Bundle {
  override def clone = new SRAMInterface(
    dataWidth = dataWidth,
    numReadPorts = numReadPorts,
    numWritePorts = numWritePorts,
    numReadWritePorts = numReadWritePorts,
    sramDepth = sramDepth
  ).asInstanceOf[this.type]
  // Data Input
  val din = Vec.fill(numReadWritePorts){UInt(OUTPUT, width = dataWidth)}
  val dinW = Vec.fill(numWritePorts){UInt(OUTPUT, width = dataWidth)}
  // Data Output
  val dout = Vec.fill(numReadWritePorts){UInt(INPUT, width = dataWidth)}
  val doutR = Vec.fill(numReadPorts){UInt(INPUT, width = dataWidth)}
  // Addresses
  val addr = Vec.fill(numReadWritePorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  val addrR = Vec.fill(numReadPorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  val addrW = Vec.fill(numWritePorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  // Write enable
  val we = Vec.fill(numReadWritePorts){Bool(OUTPUT)}
  val weW = Vec.fill(numWritePorts){Bool(OUTPUT)}
}

class SRAM (
  val dataWidth: Int = 8,
  val sramDepth: Int = 64,
  val numReadPorts: Int = 0,
  val numWritePorts: Int = 0,
  val numReadWritePorts: Int = 2
) extends Module {
  val io = new SRAMInterface(
    numReadPorts = numReadPorts,
    numWritePorts = numWritePorts,
    numReadWritePorts = numReadWritePorts,
    dataWidth = dataWidth,
    sramDepth = sramDepth
  ).flip
  val mem = Mem(UInt(width = dataWidth), sramDepth)
  val buf = Vec.fill(numReadWritePorts){Reg(UInt(width = dataWidth))}
  val bufR = Vec.fill(numReadPorts){Reg(UInt(width = dataWidth))}

  for (i <- 0 until numReadPorts) {
    bufR(i) := mem(io.addrR(i))
    io.doutR(i) := bufR(i)
  }

  for (i <- 0 until numWritePorts) {
    when (io.weW(i)) {
      mem(io.addrW(i)) := io.dinW(i)
    }
  }

  for (i <- 0 until numReadWritePorts) {
    when (io.we(i)) {
      mem(io.addr(i)) := io.din(i)
    }
    buf(i) := mem(io.addr(i))
    io.dout(i) := buf(i)
  }
}

class SRAMElementInterface(
  val dataWidth: Int,
  val sramDepth: Int,
  val numPorts: Int,
  val elementWidth: Int
) extends Bundle {
  override def clone = new SRAMElementInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth,
    numPorts = numPorts,
    elementWidth = elementWidth
  ).asInstanceOf[this.type]
  val we = Vec.fill(numPorts){ Bool(OUTPUT) }
  val din = Vec.fill(numPorts){ UInt(OUTPUT, width = elementWidth)}
  val addr = Vec.fill(numPorts){ UInt(OUTPUT,
    width = log2Up(sramDepth * dataWidth / elementWidth))}
  val dout = Vec.fill(numPorts){ UInt(INPUT, width = dataWidth)}
}

// A special instance of the generic SRAM that allows for masked
// writes to the SRAM. Reads happen normally, but writes happen using
// a 2-cyle read-modify-write operation. Due to the nature of this
// operation, each write port needs an associated read port.
// Consequently, this only has RW ports.
class SRAMElement (
  val dataWidth: Int = 32,
  val sramDepth: Int = 64,
  val elementWidth: Int = 8,
  val numPorts: Int = 1
) extends Module {
  val io = new SRAMElementInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth,
    numPorts = numPorts,
    elementWidth = elementWidth
  ).flip
  val sram = Module(new SRAM(
    dataWidth = dataWidth,
    sramDepth = sramDepth,
    numReadPorts = numPorts,
    numWritePorts = numPorts,
    numReadWritePorts = 0
  ))

  val addr = Vec.fill(numPorts){ new Bundle{
    val addrHi = UInt(width = log2Up(sramDepth))
    val addrLo = UInt(width = log2Up(dataWidth / elementWidth))
  }}

  val writePending = Vec.fill(numPorts){new Bundle{
    val valid = Reg(Bool(), init = Bool(false))
    val data = Reg(UInt(width = elementWidth))
    val addrHi = Reg(UInt(width = log2Up(sramDepth)))
    val addrLo = Reg(UInt(width = log2Up(dataWidth / elementWidth)))
  }}

  val tmp = Vec.fill(numPorts){
    Vec.fill(dataWidth / elementWidth){ UInt(width = elementWidth) }}
  val forwarding = Vec.fill(numPorts){ Bool() }

  // Combinational Logic
  for (i <- 0 until numPorts) {
    // Assign the addresses
    addr(i).addrHi := io.addr(i).toBits()(
      log2Up(sramDepth * dataWidth / elementWidth) - 1,
      log2Up(dataWidth / elementWidth))
    addr(i).addrLo := io.addr(i).toBits()(
      log2Up(dataWidth / elementWidth) - 1, 0)
    // Connections to the sram
    sram.io.weW(i) := writePending(i).valid
    sram.io.dinW(i) := tmp(i).toBits()
    sram.io.addrR(i) := addr(i).addrHi
    io.dout(i) := sram.io.doutR(i)
    // Defaults
    forwarding(i) := Bool(false)
    tmp(i) := sram.io.doutR(i)
    sram.io.addrW(i) := writePending(i).addrHi
    when (writePending(i).valid) {
      for (j <- 0 until dataWidth / elementWidth) {
        when (UInt(j) === writePending(i).addrLo) {
          tmp(i)(j) := writePending(i).data
        } .elsewhen(addr(i).addrHi === writePending(i).addrHi &&
            io.we(i) &&
            UInt(j) === addr(i).addrLo) {
          tmp(i)(j) := io.din(i)
          forwarding(i) := Bool(true)
        } .otherwise {
          tmp(i)(j) := sram.io.doutR(i).toBits()((j+1) * elementWidth - 1, j * elementWidth)
        }
      }
    }
  }

  // Sequential Logic
  for (i <- 0 until numPorts) {
    // Assign the pending write data
    writePending(i).valid := Bool(false)
    when ((io.we(i)) && (forwarding(i) === Bool(false))) {
      writePending(i).valid := Bool(true)
      writePending(i).data := io.din(i)
      writePending(i).addrHi := addr(i).addrHi
      writePending(i).addrLo := addr(i).addrLo
    }
  }
}

class SRAMTests(uut: SRAM, isTrace: Boolean = true)
    extends Tester(uut, isTrace) {
  // Generate a local copy of the memory in a vector
  val copy = Array.fill(uut.sramDepth){0}
  for (i <- 0 until uut.sramDepth) {
    copy(i) = rnd.nextInt((Math.pow(2, uut.dataWidth) - 1).toInt)
  }
  // Write all the data into the memory
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.we(0), 1)
    poke(uut.io.addr(0), i)
    poke(uut.io.din(0), copy(i))
    step(1)
    poke(uut.io.we(0), 0)
  }
  // Verify that all the data is correct
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.addr(1), i)
    step(1)
    expect(uut.io.dout(1), copy(i))
  }
}

class SRAMElementTests(uut: SRAMElement, isTrace: Boolean = true)
    extends Tester(uut, isTrace) {
  // Extracts an element from a block
  def extractElement(block: Int, element: Int): Int = {
    if (element == 0)
      block & ~((~0) << uut.elementWidth)
    else
      (block >> (element * uut.elementWidth)) & ~((~0) << uut.elementWidth)
  }

  // Generate some random local data
  val copy = Array.fill(uut.sramDepth){0}
  for (i <- 0 until uut.sramDepth) {
    copy(i) = rnd.nextInt((Math.pow(2, uut.dataWidth) - 1).toInt)
  }

  // No forwarding test
  println("[INFO] Sequential writes, no forwarding")
  for (i <- 0 until uut.sramDepth) {
    for (j <- 0 until (uut.dataWidth / uut.elementWidth)) {
      poke(uut.io.we(0), 1)
      poke(uut.io.addr(0), ((i << log2Up(uut.dataWidth / uut.elementWidth)) + j))
      poke(uut.io.din(0), extractElement(copy(i), j))
      step(1)
      poke(uut.io.we(0), 0)
      step(1)
    }
  }

  step(1)
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.addr(0), (i << log2Up(uut.dataWidth / uut.elementWidth)))
    step(1)
    printf("%08x =? %08x", copy(i), peek(uut.io.dout(0)))
    if (!expect(uut.io.dout(0), copy(i)))
      printf(" X\n")
    else
      printf("\n")
  }

  // Forwarding Test
  println("[INFO] Sequential writes, all forwards")
  for (i <- 0 until uut.sramDepth) {
    copy(i) = rnd.nextInt((Math.pow(2, uut.dataWidth) - 1).toInt)
  }
  for (i <- 0 until uut.sramDepth) {
    for (j <- 0 until (uut.dataWidth / uut.elementWidth)) {
      poke(uut.io.we(0), 1)
      poke(uut.io.addr(0), ((i << log2Up(uut.dataWidth / uut.elementWidth)) + j))
      poke(uut.io.din(0), extractElement(copy(i), j))
      step(1)
    }
  }
  poke(uut.io.we(0), 0)

  step(1)
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.addr(0), (i << log2Up(uut.dataWidth / uut.elementWidth)))
    step(1)
    printf("%08x =? %08x", copy(i), peek(uut.io.dout(0)))
    if (!expect(uut.io.dout(0), copy(i)))
      printf(" X\n")
    else
      printf("\n")
  }

  // Random Forwarding Test
  println("[INFO] Sequential writes, random forwards")
  for (i <- 0 until uut.sramDepth) {
    copy(i) = rnd.nextInt((Math.pow(2, uut.dataWidth) - 1).toInt)
  }
  for (i <- 0 until uut.sramDepth) {
    for (j <- 0 until (uut.dataWidth / uut.elementWidth)) {
      poke(uut.io.we(0), 1)
      poke(uut.io.addr(0), ((i << log2Up(uut.dataWidth / uut.elementWidth)) + j))
      poke(uut.io.din(0), extractElement(copy(i), j))
      step(1)
      if (rnd.nextInt(2) == 1) {
        poke(uut.io.we(0), 0)
        step(1)
      }
    }
  }
  poke(uut.io.we(0), 0)

  step(1)
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.addr(0), (i << log2Up(uut.dataWidth / uut.elementWidth)))
    step(1)
    printf("%08x =? %08x", copy(i), peek(uut.io.dout(0)))
    if (!expect(uut.io.dout(0), copy(i)))
      printf(" X\n")
    else
      printf("\n")
  }
}