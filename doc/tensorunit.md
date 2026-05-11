# Shuttle Tensor Unit Integration Notes

This note documents the tensor-unit integration points that live inside
`generators/shuttle`.  The directory currently provides the Shuttle-side
interface and TileLink topology for a tensor unit; it does not contain a concrete
`ShuttleTensorUnit` implementation or a config fragment that fills
`ShuttleCoreParams.tensor`.

Key source files:

- `src/main/scala/common/TensorUnit.scala`
- `src/main/scala/common/Parameters.scala`
- `src/main/scala/common/Tile.scala`
- `src/main/scala/common/VectorUnit.scala`
- `src/main/scala/dmem/SGTCM.scala`

## 1. Tensor Unit Contract

`ShuttleTensorUnit` is an abstract `LazyModule` hook:

```scala
abstract class ShuttleTensorUnit(implicit p: Parameters)
    extends LazyModule with HasNonDiplomaticTileParameters {
  val module: ShuttleTensorUnitModuleImp
  val tlNode: TLNode = TLIdentityNode()
  val atlNode: TLNode = TLIdentityNode()
  val tcmslaveNode: TLNode = TLIdentityNode()
  val sgtcmslaveNode: TLNode = TLIdentityNode()
  val nptwports = 3
}
```

The four TileLink nodes are intentionally generic `TLIdentityNode`s.  A concrete
tensor implementation can attach real client or manager nodes behind them.
Shuttle wires them as follows:

- `atlNode`: tensor-side TL master path into the tile-local master xbar.
- `tlNode`: declared on the abstract tensor unit, but not connected by the
  current `ShuttleTile` tensor path.
- `tcmslaveNode`: tensor-hosted local slave window reachable from
  `tensor_xbar`.
- `sgtcmslaveNode`: tensor-hosted local slave window reachable from
  `tensor_xbar`.

The module implementation exposes three non-diplomatic interfaces:

```scala
class ShuttleTensorUnitModuleImp(outer: ShuttleTensorUnit)
    extends LazyModuleImp(outer) with HasCoreParameters {
  val vector_io = IO(new ShuttleVectorCoreIO)
  val io_sg_base = IO(Input(UInt(coreMaxAddrBits.W)))
  val sgSize = outer.tileParams.asInstanceOf[ShuttleTileParams].sgtcm.map(_.size)
  val rocc_io = IO(new RoCCIO(nPTWPorts = outer.nptwports, nRoCCCSRs = 0))
}
```

- `vector_io` reuses the Shuttle vector execution interface.  Tensor issue,
  completion, replay, vector CSR, TLB, and scalar-check interactions all pass
  through this interface.
- `rocc_io` is connected through a `LazyRoCC` wrapper so custom RoCC opcodes can
  deliver commands and PTW traffic to the tensor unit.
- `io_sg_base` is intended to carry the per-hart SGTCM base address.  See the
  caveat in Section 7 before relying on it.

`RoccWapper` is a simple pass-through `LazyRoCC`:

```scala
class RoccWapper(opcodes: OpcodeSet, ptwports: Int)(implicit p: Parameters)
    extends LazyRoCC(opcodes, ptwports) {
  override lazy val module = new RoccWapperImp(this)
}

class RoccWapperImp(outer: RoccWapper) extends LazyRoCCModuleImp(outer) {
  val wapperio = IO(Flipped(new RoCCIO(outer.nPTWPorts, outer.roccCSRs.size)))
  wapperio <> io
}
```

The current tile code later casts the wrapper module to `RoccWapperImp`, so
`buildroccwarper` should return this wrapper or a compatible subclass.

## 2. Parameter And Config Path

The tensor entry in `ShuttleCoreParams` is:

```scala
case class ShuttleCoreTensorParams(
  build: Parameters => ShuttleTensorUnit,
  buildroccwarper: Parameters => LazyRoCC,
  vLen: Int,
  vfLen: Int,
  vfh: Boolean,
  decoder: Parameters => RocketVectorDecoder,
  issueVConfig: Boolean,
  vExts: Seq[String])

case class ShuttleCoreParams(
  ...
  vector: Option[ShuttleCoreVectorParams] = None,
  tensor: Option[ShuttleCoreTensorParams] = None,
  ...
)
```

`build` constructs the tensor `LazyModule`; the tile wraps it with
`LazyModule(t.build(p))`.  `buildroccwarper` constructs the RoCC wrapper used for
custom instruction dispatch and PTW ports.

`generators/shuttle` does not define a `WithTensor...` config fragment.  A
downstream generator normally enables tensor support by rewriting
`ShuttleTileAttachParams.tileParams.core`:

```scala
case tp: ShuttleTileAttachParams => tp.copy(tileParams = tp.tileParams.copy(
  core = tp.tileParams.core.copy(
    vector = Some(ShuttleCoreVectorParams(
      build = _ => ???,              // not instantiated when tensor is present
      vLen = ...,
      vfLen = ...,
      vfh = ...,
      decoder = p => new MyVectorDecoder()(p),
      issueVConfig = ...,
      vExts = ...
    )),
    tensor = Some(ShuttleCoreTensorParams(
      build = p => new MyTensorUnit(...)(p),
      buildroccwarper = p => new RoccWapper(OpcodeSet.custom0, ptwports = 3)(p),
      vLen = ...,
      vfLen = ...,
      vfh = ...,
      decoder = p => new MyVectorDecoder()(p),
      issueVConfig = ...,
      vExts = ...
    ))
  )
))
```

The `vector = Some(...)` part is important in the current implementation.  The
Shuttle core creates `core.io.vector` only when `CoreParams.useVector` is true,
and `ShuttleCoreParams.useVector` currently checks only `vector.isDefined`, not
`tensor.isDefined`.  The core also reads the vector decoder and `issueVConfig`
from `shuttleParams.vector`.  In practice, tensor configurations need vector
params to enable the core-side vector/tensor pipeline, even though the standalone
`ShuttleVectorUnit` is disabled when tensor is present.

TCM-related config fragments are already provided:

```scala
class WithTCM(
  address: BigInt = 0x70000000L,
  size: BigInt = 64L << 10,
  banks: Int = 4,
  ...)

class WithSGTCM(
  address: BigInt = 0x78000000L,
  size: BigInt = 8L << 10,
  banks: Int = 32,
  ...)

class WithShuttleTileBeatBytes(beatBytes: Int, ...)
```

`WithTCM` fills `ShuttleTileParams.tcm`; `WithSGTCM` fills
`ShuttleTileParams.sgtcm`; `WithShuttleTileBeatBytes` controls the local tile TL
beat width.

## 3. Instantiation In `ShuttleTile`

The tile always creates a `tensor_xbar`:

```scala
val tensor_xbar = LazyModule(new TLXbar)
```

When `shuttleParams.core.tensor` is defined:

```scala
val tensor_unit = shuttleParams.core.tensor.map(t => LazyModule(t.build(p)))
val RoccWapper = shuttleParams.core.tensor.map(t => LazyModule(t.buildroccwarper(p)))

val roccs = if (tensor_unit.size != 0) {
  Seq(RoccWapper.get)
} else {
  p(BuildRoCC).map(_(p))
}

val vector_unit = shuttleParams.core.tensor match {
  case Some(t) => None
  case None => shuttleParams.core.vector.map(v => LazyModule(v.build(p)))
}
```

This has three integration consequences:

1. The tensor unit replaces the standalone vector unit.  `ShuttleVectorUnit` is
   not instantiated when `core.tensor` is present.
2. The tensor RoCC wrapper replaces the normal `BuildRoCC` sequence.  Existing
   `BuildRoCC` accelerators are ignored unless the tile code is extended to
   compose them with the tensor wrapper.
3. The tensor implementation receives the core's vector pipeline interface and
   a RoCC interface, but its TL memory traffic is expected to use diplomatic TL
   nodes rather than the RoCC HellaCache memory port.

## 4. Non-Diplomatic Wiring

Inside `ShuttleTileModuleImp`, tensor mode wires the core and wrapper like this:

```scala
outer.tensor_unit.foreach { t =>
  core.io.vector.get <> t.module.vector_io
  val sgtcmParams = outer.shuttleParams.sgtcm
  t.module.io_sg_base := sgtcmParams.map { sgtcm =>
    sgtcm.base.U + outer.hartIdSinkNode.bundle * sgtcm.size.U
  }.getOrElse(0.U)
}

outer.RoccWapper.map { roccwapper =>
  roccwapper.module.asInstanceOf[RoccWapperImp].wapperio <>
    outer.tensor_unit.get.module.rocc_io
}
```

The generic RoCC plumbing still handles command routing, responses, busy,
interrupt, and PTW ports:

```scala
val nPTWPorts = 2 + roccs.map(_.nPTWPorts).sum
...
ptwPorts(0) <> core.io.ptw_tlb
ptwPorts(1) <> outer.frontend.module.io.ptw
...
rocc.module.io.cmd <> cmdRouter.io.out(i)
respArb.io.in(i) <> Queue(rocc.module.io.resp)
```

The RoCC memory port is explicitly disabled:

```scala
rocc.module.io.mem := DontCare
rocc.module.io.mem.req.ready := false.B
assert(!rocc.module.io.mem.req.valid)
```

So tensor memory access should be implemented through `atlNode`, `tlNode`,
`tcmslaveNode`, or `sgtcmslaveNode`, not through `rocc_io.mem`.

## 5. TileLink Topology

The diagrams below show request flow from left to right.

### 5.1 Tensor Master Path

The tensor unit's `atlNode` is a tile-local master path into `tlMasterXbar`:

```text
tensor_unit.atlNode
  -> TLBuffer
  -> tlMasterXbar
```

This is implemented as:

```scala
tlMasterXbar.node :=* TLBuffer() :=* tu.atlNode
```

Unlike the RoCC `atlNode` path below, this direct tensor `atlNode` connection
does not pass through `tcmAdjusterNode`.

The tensor unit's own `tlNode` is not connected in the current tile code.  If
the tensor RoCC wrapper exposes a normal RoCC `tlNode`, that wrapper node is
attached to the tile's outward master path:

```text
tensor_rocc_wrapper.tlNode
  -> tlOtherMastersNode
  -> tile masterNode / outer system
```

This follows the same path used by normal RoCC `tlNode`s.

### 5.2 RoCC Wrapper ATL Path

The tensor RoCC wrapper's `atlNode` is connected through TCM/SGTCM address
adjusters before joining `tlMasterXbar`:

```text
tensor_rocc_wrapper.atlNode
  -> tcmAdjusterNode(sgtcm)
  -> tcmAdjusterNode(tcm)
  -> tlMasterXbar
```

The corresponding diplomatic expression is:

```scala
tlMasterXbar.node :=*
  tcmAdjusterNode(shuttleParams.tcm) :=*
  tcmAdjusterNode(shuttleParams.sgtcm) :=*
  atl
```

The source code reads right-to-left in request-flow terms.

### 5.3 Frontend And DCache Through `tensor_xbar`

Instruction fetch and scalar data-cache traffic enter `tensor_xbar` before they
can continue to `tlMasterXbar`:

```text
frontend.masterNode
  -> TLWidthWidget(fetchBytes)
  -> tcmAdjusterNode(sgtcm)
  -> tcmAdjusterNode(tcm)
  -> TLBuffer
  -> tensor_xbar

dcache.node
  -> TLWidthWidget(dcache row bytes)
  -> tcmAdjusterNode(sgtcm)
  -> tcmAdjusterNode(tcm)
  -> TLBuffer
  -> tensor_xbar

tensor_xbar
  -> tlMasterXbar
```

The tensor unit's local slave nodes are also behind `tensor_xbar`:

```text
tensor_xbar
  -> TLBuffer
  -> tensor_unit.tcmslaveNode

tensor_xbar
  -> TLBuffer
  -> tensor_unit.sgtcmslaveNode
```

So frontend/DCache requests, and any other traffic entering `tensor_xbar`, can
route either to tensor-provided local slave windows or onward to the normal tile
master xbar.

An approximate tensor-enabled local topology is:

```text
frontend.masterNode ----\
dcache.node -------------> tensor_xbar ---> tlMasterXbar ---> local TCM/SGTCM or outer bus
tensor tcmslaveNode <----/
tensor sgtcmslaveNode <--/

tensor atlNode ---------------------------> tlMasterXbar
tensor RoCC atlNode -> adjusters ---------> tlMasterXbar
tensor RoCC tlNode -----------------------> tlOtherMastersNode
```

### 5.4 TCM Path

When `shuttleParams.tcm` is defined, TCM banks are ordinary `TLRAM`s behind
`tlMasterXbar`:

```text
tlMasterXbar
  -> tcmMasterReplicator(tcm)
  -> TLBuffer
  -> TLFragmenter(tileBeatBytes, CacheBlockBytes)
  -> TLRAM bank b
```

The bank loop is:

```scala
for (b <- 0 until tcmParams.banks) {
  val base = tcmParams.base + b * p(CacheBlockBytes)
  val mask = tcmParams.size - 1 - (tcmParams.banks - 1) * p(CacheBlockBytes)
  val tcm = LazyModule(new TLRAM(
    address = AddressSet(base, mask),
    beatBytes = shuttleParams.tileBeatBytes,
    atomics = true,
    ...
  ))
  tcm.node := TLFragmenter(shuttleParams.tileBeatBytes, p(CacheBlockBytes)) :=
    TLBuffer() := tcmMasterReplicator(shuttleParams.tcm) := tlMasterXbar.node
}
```

Banks are interleaved at `CacheBlockBytes` granularity.  Increasing `banks`
creates more independent banks; it does not make each TL beat wider.  Each TCM
bank still has `beatBytes = shuttleParams.tileBeatBytes`.

### 5.5 SGTCM Path

`ShuttleSGTCMParams` mirrors `TCMParams`:

```scala
case class ShuttleSGTCMParams(
  base: BigInt,
  size: BigInt,
  banks: Int) extends TCMParams
```

The tile creates `tcmSlaveXbar` only when SGTCM is defined:

```scala
val tcmSlaveXbar = ((shuttleParams.sgtcm.isDefined)).option(TLXbar())
```

The normal SGTCM node is reached through `tlMasterXbar`:

```text
tlMasterXbar
  -> tcmMasterReplicator(sgtcm)
  -> tcmSlaveXbar
  -> TLWidthWidget(tileBeatBytes)
  -> SGTCM.node
```

The current `SGTCM` implementation uses `banks` as the normal TL beat width:

```scala
val sgtcm = LazyModule(new SGTCM(
  address = AddressSet(base, mask),
  beatBytes = sgtcmParams.banks,
  ...
))
sgtcm.node := TLWidthWidget(shuttleParams.tileBeatBytes) := tcmSlaveXbar.get
```

Inside `SGTCM`, the normal `node` supports `Get`, `PutPartial`, and `PutFull`
with transfer sizes from 1 byte up to `beatBytes`.

`SGTCM` also exposes a per-byte/lane `sgnode`:

```scala
val sgnode = TLManagerNode(Seq.tabulate(beatBytes) { i =>
  TLSlavePortParameters.v1(... beatBytes = 1 ...)
})
```

The tile connects this `sgnode` through `sgtcmXbar` only to the standalone vector
unit:

```scala
sgtcm.sgnode :*= sgtcmXbar.node
vector_unit.foreach { vu => sgtcmXbar.node :=* vu.sgNode.get }
```

Since `vector_unit` is `None` when tensor is present, the dedicated SGTCM
`sgnode` path is not connected to tensor mode by the current Shuttle tile code.
The tensor unit's `sgtcmslaveNode` is a separate tensor-hosted slave path behind
`tensor_xbar`; it is not the same as `SGTCM.sgnode`.

## 6. TCM Address Replication And Adjusters

The helper functions in `ShuttleTile` exist to make each hart's local TCM/SGTCM
slice look replicated across a multi-tile system.

For both TCM and SGTCM:

```scala
val replicationSize = (1 << log2Ceil(p(NumTiles))) * tcmParams.size
```

`tcmAdjusterNode(params)` creates an `AddressOffsetter` with:

```scala
new AddressOffsetter(tcmParams.size - 1, replicationSize)
io.base := tcmParams.base.U + tcmParams.size.U * hartId
```

For matching local addresses, `AddressOffsetter` ORs in the replication offset
if the downstream edge has a manager for the adjusted address.

`tcmMasterReplicator(params)` creates a `RegionReplicator` and filters out the
low replicated range:

```scala
tcm_master_replicator.node :*=*
  TLFilter(TLFilter.mSubtract(AddressSet(tcmParams.base, replicationSize - 1)))
```

In the current active wiring, local TCM/SGTCM memory is reached from
`tlMasterXbar` through `tcmMasterReplicator`.  The slave-port exposure path
through `tcmSlaveReplicator` and `tlSlaveXbar` is present in comments but not
enabled.

## 7. Current Caveats

- `generators/shuttle` supplies only the abstract tensor integration shell.  The
  concrete tensor unit and the config fragment that sets `core.tensor` must come
  from another generator or local integration layer.
- `buildroccwarper` is typed as `Parameters => LazyRoCC`, but
  `ShuttleTileModuleImp` casts the resulting module to `RoccWapperImp`.  A
  generic `LazyRoCC` implementation will not work unless it is compatible with
  that cast.
- `ShuttleCoreParams.useVector`, `vLen`, `vfLen`, `vfh`, and `vExts` currently
  read only `core.vector`, not `core.tensor`.  Tensor mode still needs
  `core.vector` populated so the core creates `core.io.vector` and uses the
  vector decoder path.
- In tensor mode, `BuildRoCC` accelerators are replaced by the tensor wrapper.
  Extra RoCCs require extending the `roccs` construction.
- `ShuttleTensorUnit.tlNode` is currently unused by `ShuttleTile`; use the
  tensor `atlNode` for tile-local master traffic or the RoCC wrapper `tlNode`
  for outward/system master traffic.
- The RoCC HellaCache memory interface is disabled with an assertion that it
  never issues requests.  Tensor memory traffic should use the diplomatic TL
  nodes.
- `io_sg_base` is assigned twice in `ShuttleTileModuleImp`: first to
  `sgtcm.base + hartId * sgtcm.size`, then later to the constant
  `0x78000000L.U`.  With Chisel last-connect behavior, the later constant is the
  effective value.  Audit or fix this before relying on per-hart SGTCM base
  addressing.
- The dedicated `SGTCM.sgnode` lane/bank path is wired only to
  `ShuttleVectorUnit.sgNode`.  Tensor mode disables `ShuttleVectorUnit`, so this
  path is not currently available to tensor through the Shuttle tile integration.
