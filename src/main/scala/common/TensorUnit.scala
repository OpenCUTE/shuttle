package shuttle.common

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile._
import shuttle.dmem._

abstract class ShuttleTensorUnit(implicit p: Parameters) extends LazyModule with HasNonDiplomaticTileParameters {
  val module: ShuttleTensorUnitModuleImp
  val tlNode: TLNode = TLIdentityNode()
  val atlNode: TLNode = TLIdentityNode()
  val tcmslaveNode: TLNode = TLIdentityNode()
  val sgtcmslaveNode: TLNode = TLIdentityNode()
  val nptwports = 3
//   val cutev3: LazyRoCC
}



class ShuttleTensorUnitModuleImp(outer: ShuttleTensorUnit) extends LazyModuleImp(outer) with HasCoreParameters {
  val vector_io = IO(new ShuttleVectorCoreIO)
  val io_sg_base = IO(Input(UInt(coreMaxAddrBits.W)))
  val sgSize = outer.tileParams.asInstanceOf[ShuttleTileParams].sgtcm.map(_.size)
  val rocc_io = IO(new RoCCIO(nPTWPorts = outer.nptwports, nRoCCCSRs = 0))
}


class RoccWapper(opcodes: OpcodeSet,ptwports: Int)(implicit p: Parameters) extends LazyRoCC(opcodes,ptwports){
  override lazy val module = new RoccWapperImp(this)
}


class RoccWapperImp(outer: RoccWapper) extends LazyRoCCModuleImp(outer){
  val wapperio = IO(Flipped(new RoCCIO(outer.nPTWPorts, outer.roccCSRs.size)))
  wapperio <> io
}