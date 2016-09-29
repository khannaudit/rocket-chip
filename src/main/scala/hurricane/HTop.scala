// See LICENSE for license details.

package hurricane 

import Chisel._
import cde.{Parameters, Field}
import rocket.Util._
import util._
import testchipip._
import coreplex._
import uncore.tilelink2._
import uncore.tilelink._
import uncore.agents._
import uncore.devices.NTiles
import junctions._
import hbwif._
import rocketchip._

case object BuildHTop extends Field[Parameters => HUpTop]

/* Hurricane Upstream Chisel Top */
class HUpTop(q: Parameters) extends BaseTop(q)
    with PeripheryBootROM
    with PeripheryDebug
    with PeripheryCoreplexLocalInterrupter
    with HurricaneExtraTopLevel
    with HurricaneIF
    with Hbwif {
  topLevelSCRBuilder.addControl("coreplex_reset", UInt(1))
  for (i <- 0 until p(NTiles) - 1) {
    topLevelSCRBuilder.addControl(s"core_${i}_reset", UInt(1))
  }
  topLevelSCRBuilder.addControl("pmu_reset", UInt(1))
  override lazy val module = Module(new HUpTopModule(p, this, new HUpTopBundle(p)))
}

class HUpTopBundle(p: Parameters) extends BaseTopBundle(p)
    with PeripheryBootROMBundle
    with PeripheryDebugBundle
    with PeripheryCoreplexLocalInterrupterBundle
    with HurricaneIFBundle
    with HbwifBundle
//TODOHurricane: add DRAM I/Os here

class HUpTopModule[+L <: HUpTop, +B <: HUpTopBundle]
    (p: Parameters, l: L, b: => B) extends BaseTopModule(p, l, b)
    with PeripheryBootROMModule
    with PeripheryDebugModule
    with PeripheryCoreplexLocalInterrupterModule
    with HurricaneExtraTopLevelModule
    with HurricaneIFModule
    with HbwifModule
    with HardwiredResetVector {
  val multiClockCoreplexIO = coreplexIO.asInstanceOf[MultiClockCoreplexBundle]

  coreplex.clock := clock
  coreplex.reset := ResetSync(scr.control("coreplex_reset")(0).toBool, coreplex.clock)

  multiClockCoreplexIO.tcrs.dropRight(1).zipWithIndex foreach { case (tcr, i) =>
    tcr.clock := clock
    tcr.reset := ResetSync(scr.control(s"core_${i}_reset")(0).toBool, tcr.clock)
  }
  multiClockCoreplexIO.tcrs.last.reset := scr.control(s"pmu_reset")(0).toBool

  // Hbwif connections
  hbwifFastClock := clock
}

/////

trait HurricaneExtraTopLevel extends LazyModule {
  implicit val p: Parameters
  val pDevices: ResourceManager[AddrMapEntry]
  val topLevelSCRBuilder: SCRBuilder = new SCRBuilder

  pDevices.add(AddrMapEntry(s"HSCRFile", MemSize(BigInt(p(HSCRFileSize)), MemAttr(AddrMapProt.RW))))
}

trait HurricaneExtraTopLevelModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val hbwifFastClock: Clock = Wire(Clock())
  val pBus: TileLinkRecursiveInterconnect
  val outer: HurricaneExtraTopLevel

  //SCR file generation
  val scr = outer.topLevelSCRBuilder.generate(edgeMMIOParams)
  val scrArb = Module(new ClientUncachedTileLinkIOArbiter(2)(edgeMMIOParams))
  scrArb.io.in(0) <> pBus.port("HSCRFile")
  val lbscrTL = scrArb.io.in(1)
  scr.io.tl <> scrArb.io.out
}

/////

trait HurricaneIF extends LazyModule {
  implicit val p: Parameters
  val pBusMasters: RangeManager

  pBusMasters.add("lbwif", 1)
}

trait HurricaneIFBundle extends HasPeripheryParameters {
  implicit val p: Parameters
  val serial = new SerialIO(p(NarrowWidth))
  val host_clock = Bool(OUTPUT)
}

trait HurricaneIFModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val numLanes = p(HbwifKey).numLanes

  val outer: HurricaneIF
  val io: HurricaneIFBundle
  val coreplexIO: BaseCoreplexBundle
  val lbscrTL: ClientUncachedTileLinkIO
  val hbwifIO = Wire(Vec(numLanes, new ClientUncachedTileLinkIO()(edgeMMIOParams)))

  val lbwifWidth = p(NarrowWidth)
  val lbwifParams = p.alterPartial({ case TLId => "LBWIF" })
  val switcherParams = p.alterPartial({ case TLId => "Switcher" })

  val unmapper = Module(new ChannelAddressUnmapper(nMemChannels)(edgeMemParams))
  val switcher = Module(new ClientUncachedTileLinkIOSwitcher(
    nMemChannels, numLanes+1)(switcherParams))
  val lbwif = Module(
    new ClientUncachedTileLinkIOBidirectionalSerdes(lbwifWidth)(lbwifParams))

  unmapper.io.in <> coreplexIO.master.mem
  switcher.io.in <> unmapper.io.out

  def scrRouteSel(addr: UInt) = UIntToOH(p(GlobalAddrMap).isInRegion("io:pbus:HSCRFile",addr))
  val scr_router = Module(new ClientUncachedTileLinkIORouter(2,scrRouteSel)(edgeMMIOParams))
  val (r_start, r_end) = outer.pBusMasters.range("lbwif")

  lbwif.io.tl_manager <> switcher.io.out(0)
  scr_router.io.in <> lbwif.io.tl_client
  lbscrTL <> scr_router.io.out(1)
  coreplexIO.slave(r_start) <> scr_router.io.out(0)

  val slowio_module = Module(new SlowIO(p(SlowIOMaxDivide))(UInt(width=p(NarrowWidth))))

  lbwif.io.serial.in <> slowio_module.io.in_fast
  slowio_module.io.out_fast <> lbwif.io.serial.out
  slowio_module.io.in_slow <> io.serial.in
  io.serial.out <> slowio_module.io.out_slow
  io.host_clock := slowio_module.io.clk_slow
  // TODOHurricane - wire slowio divider to SCRs

  val ser = (0 until numLanes) map { i =>
    hbwifIO(i) <> switcher.io.out(i+1)
  }
  // TODOHurricane - make the switcher configurable via SCR
}
