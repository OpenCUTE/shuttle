# Shuttle Tensor Unit 集成说明

本文整理 `generators/shuttle` 目录中和 tensor unit 相关的 Shuttle 侧实现，
重点覆盖实例化方式、参数配置入口、RoCC wrapper 接法、TileLink 拓扑、
TCM/SGTCM 访问路径，以及当前代码中需要注意的限制。

当前 `generators/shuttle` 只提供 tensor unit 的抽象接入框架和 TileLink
连接方式；具体的 `ShuttleTensorUnit` 实现类，以及把
`ShuttleCoreParams.tensor` 填成 `Some(...)` 的 Config fragment，需要由外部
generator 或本地集成层提供。

主要源文件：

- `src/main/scala/common/TensorUnit.scala`
- `src/main/scala/common/Parameters.scala`
- `src/main/scala/common/Tile.scala`
- `src/main/scala/common/VectorUnit.scala`
- `src/main/scala/dmem/SGTCM.scala`

## 1. Tensor Unit 抽象接口

Shuttle 侧的 tensor unit 定义为一个抽象 `LazyModule`：

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

这几个 TileLink node 都先声明成通用的 `TLIdentityNode`，由具体 tensor
实现决定背后接真实的 client node、manager node，或者继续保持透传。

当前 Shuttle tile 对这些 node 的使用方式如下：

- `atlNode`：tensor unit 自己的 tile-local TL master 路径，直接进入
  `tlMasterXbar`。
- `tlNode`：抽象类里有声明，但当前 `ShuttleTile` 的 tensor 路径没有把它接
  出去。
- `tcmslaveNode`：tensor unit 暴露给 tile 内部访问的本地 slave 窗口，
  挂在 `tensor_xbar` 后面。
- `sgtcmslaveNode`：同样是 tensor unit 暴露的本地 slave 窗口，挂在
  `tensor_xbar` 后面，名字上对应 SGTCM 相关用途。

模块实现侧的非 diplomatic IO 是：

```scala
class ShuttleTensorUnitModuleImp(outer: ShuttleTensorUnit)
    extends LazyModuleImp(outer) with HasCoreParameters {
  val vector_io = IO(new ShuttleVectorCoreIO)
  val io_sg_base = IO(Input(UInt(coreMaxAddrBits.W)))
  val sgSize = outer.tileParams.asInstanceOf[ShuttleTileParams].sgtcm.map(_.size)
  val rocc_io = IO(new RoCCIO(nPTWPorts = outer.nptwports, nRoCCCSRs = 0))
}
```

含义如下：

- `vector_io` 复用 Shuttle 的 vector 执行接口。tensor 指令 issue、完成、
  replay、vector CSR、TLB 请求、scalar check 等，都从这条接口和 core
  pipeline 交互。
- `rocc_io` 通过一个 `LazyRoCC` wrapper 接入 core 的 RoCC 命令、响应、
  busy、interrupt 和 PTW 端口。
- `io_sg_base` 按设计意图应当传入当前 hart 的 SGTCM base 地址；但当前代码
  有重复赋值问题，见第 7 节。
- `sgSize` 从 `ShuttleTileParams.sgtcm.map(_.size)` 取值，给 tensor 模块感知
  SGTCM 大小。

`TensorUnit.scala` 里还定义了一个 RoCC pass-through wrapper：

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

注意这里代码里的名字是 `RoccWapper`/`RoccWapperImp`，保留了原拼写。
`ShuttleTileModuleImp` 后面会把 wrapper module 强制 cast 成
`RoccWapperImp`，所以 `buildroccwarper` 返回的最好就是这个 wrapper，
或者至少要保持兼容。

## 2. 参数配置入口

Tensor 参数入口定义在 `Parameters.scala`：

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

字段作用：

- `build`：构造具体 tensor unit 的 `LazyModule`。
- `buildroccwarper`：构造 RoCC wrapper，用于把 custom RoCC 指令接入
  tensor unit。
- `vLen`、`vfLen`、`vfh`、`decoder`、`issueVConfig`、`vExts`：语义上和
  vector 参数类似，用来描述 vector/tensor 指令解码和 vector CSR 相关能力。

`generators/shuttle` 当前没有提供类似 `WithTensorUnit` 的 Config fragment。
外部集成通常需要在 Config 中改写 `ShuttleTileAttachParams.tileParams.core`：

```scala
case tp: ShuttleTileAttachParams => tp.copy(tileParams = tp.tileParams.copy(
  core = tp.tileParams.core.copy(
    vector = Some(ShuttleCoreVectorParams(
      build = p => new MyUnusedVectorUnit()(p), // tensor 模式下不会实例化
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

这里 `vector = Some(...)` 在当前实现中仍然很关键。原因是：

```scala
override val useVector = vector.isDefined
override def vLen = vector.map(_.vLen).getOrElse(0)
override def vfLen = vector.map(_.vfLen).getOrElse(0)
override def vExts = vector.map(_.vExts).getOrElse(Nil)
```

也就是说，`ShuttleCoreParams.useVector` 目前只看 `core.vector`，不看
`core.tensor`。而 `ShuttleCore` 创建 `core.io.vector`、启用 vector decoder、
创建第二个 DTLB 请求端口等逻辑，都是由 `usingVector` 控制的。

因此在当前代码下，tensor 模式通常仍需要把 `core.vector` 配成 `Some(...)`，
这样 core 侧 vector/tensor pipeline 才会打开。真正的 standalone
`ShuttleVectorUnit` 不会被实例化，因为 `Tile.scala` 会在 tensor 存在时把
`vector_unit` 设为 `None`。

TCM/SGTCM 相关配置已经有现成 fragment：

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

- `WithTCM` 设置 `ShuttleTileParams.tcm`。
- `WithSGTCM` 设置 `ShuttleTileParams.sgtcm`。
- `WithShuttleTileBeatBytes` 设置 tile 内 TL 总线的 beat byte 数。

## 3. `ShuttleTile` 内的实例化方式

`ShuttleTile` 总是创建一个 `tensor_xbar`：

```scala
val tensor_xbar = LazyModule(new TLXbar)
```

当 `shuttleParams.core.tensor` 为 `Some(...)` 时，tile 会实例化 tensor unit
和对应 RoCC wrapper：

```scala
val tensor_unit = shuttleParams.core.tensor.map(t => LazyModule(t.build(p)))
val RoccWapper = shuttleParams.core.tensor.map(t => LazyModule(t.buildroccwarper(p)))
```

RoCC 列表的选择逻辑是：

```scala
val roccs = if (tensor_unit.size != 0) {
  Seq(RoccWapper.get)
} else {
  p(BuildRoCC).map(_(p))
}
```

也就是说，只要启用了 tensor unit，普通的 `BuildRoCC` 列表就不会被使用，
`roccs` 只包含 tensor 的 wrapper。

vector unit 的选择逻辑是：

```scala
val vector_unit = shuttleParams.core.tensor match {
  case Some(t) => None // tensor unit is used, so no vector unit
  case None => shuttleParams.core.vector.map(v => LazyModule(v.build(p)))
}
```

这带来三个直接结果：

1. tensor unit 会替代 standalone `ShuttleVectorUnit`。
2. tensor RoCC wrapper 会替代普通 `BuildRoCC` accelerator 列表。
3. tensor 实现同时拿到 core 的 vector pipeline 接口和 RoCC 接口；访存则应
   通过 diplomatic TL node 完成，而不是走 RoCC HellaCache memory port。

## 4. 非 diplomatic 接线

在 `ShuttleTileModuleImp` 中，tensor unit 和 core 的连接是：

```scala
outer.tensor_unit.foreach { t =>
  core.io.vector.get <> t.module.vector_io
  val sgtcmParams = outer.shuttleParams.sgtcm
  t.module.io_sg_base := sgtcmParams.map { sgtcm =>
    sgtcm.base.U + outer.hartIdSinkNode.bundle * sgtcm.size.U
  }.getOrElse(0.U)
}
```

RoCC wrapper 和 tensor module 的连接是：

```scala
outer.RoccWapper.map { roccwapper =>
  roccwapper.module.asInstanceOf[RoccWapperImp].wapperio <>
    outer.tensor_unit.get.module.rocc_io
}
```

普通 RoCC plumbing 仍然负责命令路由、响应仲裁、busy、interrupt、CSR 和 PTW：

```scala
val nPTWPorts = 2 + roccs.map(_.nPTWPorts).sum
...
ptwPorts(0) <> core.io.ptw_tlb
ptwPorts(1) <> outer.frontend.module.io.ptw
...
rocc.module.io.cmd <> cmdRouter.io.out(i)
respArb.io.in(i) <> Queue(rocc.module.io.resp)
```

其中 PTW 端口布局为：

- `ptwPorts(0)`：core DTLB。
- `ptwPorts(1)`：frontend ITLB。
- `ptwPorts(2...)`：RoCC wrapper 的 PTW ports，也就是 tensor 通过
  `rocc_io.ptw` 看到的端口。

RoCC 的 HellaCache memory port 被显式禁用：

```scala
rocc.module.io.mem := DontCare
rocc.module.io.mem.req.ready := false.B
assert(!rocc.module.io.mem.req.valid)
```

因此 tensor 访存不要使用 `rocc_io.mem`，而应该通过 `atlNode`、RoCC wrapper
的 `tlNode`、`tcmslaveNode` 或 `sgtcmslaveNode` 等 diplomatic TL 路径完成。

## 5. TileLink 拓扑

下面的图都按请求方向从左到右描述。

### 5.1 Tensor 自身 `atlNode` master 路径

tensor unit 自己的 `atlNode` 直接接入 tile-local `tlMasterXbar`：

```text
tensor_unit.atlNode
  -> TLBuffer
  -> tlMasterXbar
```

对应代码：

```scala
tensor_unit.foreach { tu => (tlMasterXbar.node
  :=* TLBuffer()
  :=* tu.atlNode) }
```

这条直接的 tensor `atlNode` 路径没有经过 `tcmAdjusterNode`。

`ShuttleTensorUnit` 自己的 `tlNode` 当前没有在 `ShuttleTile` 中接出。如果
需要向系统外侧发起 master 请求，当前可用的是 tensor RoCC wrapper 的
`tlNode`：

```text
tensor_rocc_wrapper.tlNode
  -> tlOtherMastersNode
  -> tile masterNode / outer system
```

对应代码和普通 RoCC `tlNode` 一样：

```scala
roccs.map(_.tlNode).foreach { tl => tlOtherMastersNode :=* tl }
```

### 5.2 Tensor RoCC wrapper 的 `atlNode` 路径

tensor RoCC wrapper 的 `atlNode` 先经过 TCM/SGTCM address adjuster，再接入
`tlMasterXbar`：

```text
tensor_rocc_wrapper.atlNode
  -> tcmAdjusterNode(sgtcm)
  -> tcmAdjusterNode(tcm)
  -> tlMasterXbar
```

代码写法是：

```scala
roccs.map(_.atlNode).foreach { atl =>
  tlMasterXbar.node :=*
    tcmAdjusterNode(shuttleParams.tcm) :=*
    tcmAdjusterNode(shuttleParams.sgtcm) :=*
    atl
}
```

Diplomacy 表达式按请求流向理解时，需要从右往左读。

### 5.3 Frontend/DCache 通过 `tensor_xbar`

当前 tile 把 frontend 和 dcache 的 master 流量先接入 `tensor_xbar`，再由
`tensor_xbar` 接到 `tlMasterXbar`：

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

对应代码片段：

```scala
(tensor_xbar.node
  := TLBuffer()
  := tcmAdjusterNode(shuttleParams.tcm)
  := tcmAdjusterNode(shuttleParams.sgtcm)
  := TLWidthWidget(tileParams.icache.get.fetchBytes)
  := frontend.masterNode)

(tensor_xbar.node
  := TLBuffer()
  := tcmAdjusterNode(shuttleParams.tcm)
  := tcmAdjusterNode(shuttleParams.sgtcm)
  := TLWidthWidget(tileParams.dcache.get.rowBits/8)
  := dcache.node)

tlMasterXbar.node :=* tensor_xbar.node
```

tensor unit 暴露的两个 slave node 也挂在 `tensor_xbar` 后面：

```text
tensor_xbar
  -> TLBuffer
  -> tensor_unit.tcmslaveNode

tensor_xbar
  -> TLBuffer
  -> tensor_unit.sgtcmslaveNode
```

对应代码：

```scala
tensor_unit.foreach { tu => (tu.tcmslaveNode
  :=* TLBuffer()
  :=* tensor_xbar.node) }

tensor_unit.foreach { tu => (tu.sgtcmslaveNode
  :=* TLBuffer()
  :=* tensor_xbar.node) }
```

所以，frontend/DCache 发出的请求进入 `tensor_xbar` 后，既可以访问 tensor
unit 暴露的本地 slave 窗口，也可以继续前往 `tlMasterXbar`，再去访问本地
TCM/SGTCM 或片外系统总线。

整体近似拓扑如下：

```text
frontend.masterNode ----\
dcache.node -------------> tensor_xbar ---> tlMasterXbar ---> local TCM/SGTCM or outer bus
tensor tcmslaveNode <----/
tensor sgtcmslaveNode <--/

tensor atlNode ---------------------------> tlMasterXbar
tensor RoCC atlNode -> adjusters ---------> tlMasterXbar
tensor RoCC tlNode -----------------------> tlOtherMastersNode
```

### 5.4 TCM 路径

当 `shuttleParams.tcm` 为 `Some(...)` 时，TCM 由多个 `TLRAM` bank 组成，
挂在 `tlMasterXbar` 后面：

```text
tlMasterXbar
  -> tcmMasterReplicator(tcm)
  -> TLBuffer
  -> TLFragmenter(tileBeatBytes, CacheBlockBytes)
  -> TLRAM bank b
```

对应 bank loop：

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

TCM bank 以 `CacheBlockBytes` 为粒度交织。增大 `banks` 会增加独立 bank 数，
但不会让单个 TL beat 变宽。每个 TCM bank 的 beat 宽度仍是：

```scala
beatBytes = shuttleParams.tileBeatBytes
```

例如 `CacheBlockBytes = 64` 且 `banks = 2` 时，可以近似理解为：

```text
bank0: addr bit 6 = 0
bank1: addr bit 6 = 1
```

### 5.5 SGTCM 路径

SGTCM 参数定义为：

```scala
case class ShuttleSGTCMParams(
  base: BigInt,
  size: BigInt,
  banks: Int) extends TCMParams
```

当前 `tcmSlaveXbar` 只在 SGTCM 存在时创建：

```scala
val tcmSlaveXbar = ((shuttleParams.sgtcm.isDefined)).option(TLXbar())
```

普通 SGTCM `node` 的访问路径是：

```text
tlMasterXbar
  -> tcmMasterReplicator(sgtcm)
  -> tcmSlaveXbar
  -> TLWidthWidget(tileBeatBytes)
  -> SGTCM.node
```

对应代码：

```scala
val sgtcm = LazyModule(new SGTCM(
  address = AddressSet(base, mask),
  beatBytes = sgtcmParams.banks,
  devOverride = Some(device),
  devName = Some(s"Core $tileId SGTCM")
))

sgtcm.node := TLWidthWidget(shuttleParams.tileBeatBytes) := tcmSlaveXbar.get
```

这里 `sgtcmParams.banks` 被作为 `SGTCM` 的 `beatBytes` 传入。`SGTCM.node`
支持 `Get`、`PutPartial` 和 `PutFull`，transfer size 范围是 1 byte 到
`beatBytes`。

`SGTCM` 还暴露了一个按 lane/byte 拆开的 `sgnode`：

```scala
val sgnode = TLManagerNode(Seq.tabulate(beatBytes) { i =>
  TLSlavePortParameters.v1(... beatBytes = 1 ...)
})
```

tile 里只把这条 `sgnode` 路径接给 standalone vector unit：

```scala
sgtcm.sgnode :*= sgtcmXbar.node
vector_unit.foreach { vu => sgtcmXbar.node :=* vu.sgNode.get }
```

但 tensor 模式下 `vector_unit = None`，所以当前 `SGTCM.sgnode` 这条专用
lane/bank 路径并不会接给 tensor unit。`ShuttleTensorUnit.sgtcmslaveNode`
只是 tensor 自己暴露在 `tensor_xbar` 后面的 slave node，和 `SGTCM.sgnode`
不是同一条路径。

## 6. TCM/SGTCM 地址复制和 address adjuster

`ShuttleTile` 里有三类 helper node：

- `tcmAdjusterNode`
- `tcmSlaveReplicator`
- `tcmMasterReplicator`

它们的共同背景是让每个 hart 的本地 TCM/SGTCM slice 在多 tile 系统中按
replicated region 的方式组织。

复制区域大小计算为：

```scala
val replicationSize = (1 << log2Ceil(p(NumTiles))) * tcmParams.size
```

`tcmAdjusterNode(params)` 创建一个 `AddressOffsetter`：

```scala
val tcm_adjuster =
  LazyModule(new AddressOffsetter(tcmParams.size - 1, replicationSize))

tcm_adjuster.module.io.base :=
  tcmParams.base.U + tcmParams.size.U * hartIdSinkNode.bundle
```

`AddressOffsetter` 的逻辑是：如果请求地址落在当前 hart 的本地 TCM/SGTCM
窗口内，并且 downstream manager 能接受 OR 上 replication offset 后的地址，
则把 `in.a.bits.address` 调整成 `in.a.bits.address | offset.U`。

`tcmMasterReplicator(params)` 创建 `RegionReplicator`，并过滤掉低地址
replicated range：

```scala
tcm_master_replicator.node :*=*
  TLFilter(TLFilter.mSubtract(AddressSet(tcmParams.base, replicationSize - 1)))
```

当前 active wiring 中，本地 TCM/SGTCM memory 主要从 `tlMasterXbar` 经过
`tcmMasterReplicator` 访问。通过 `tcmSlaveReplicator` 和 `tlSlaveXbar` 把
TCM/SGTCM 暴露到 tile slave port 的代码目前在注释中，没有启用。

## 7. 当前实现注意事项

- `generators/shuttle` 只提供抽象 tensor 接入框架，不包含具体 tensor unit
  实现，也没有提供设置 `core.tensor` 的 Config fragment。
- `buildroccwarper` 的类型是 `Parameters => LazyRoCC`，但
  `ShuttleTileModuleImp` 会把 module cast 成 `RoccWapperImp`。如果返回普通
  `LazyRoCC`，很可能和这处 cast 不兼容。
- `ShuttleCoreParams.useVector`、`vLen`、`vfLen`、`vfh`、`vExts` 当前只读取
  `core.vector`，不读取 `core.tensor`。tensor 模式仍需要填 `core.vector`，
  以便 core 创建 `core.io.vector` 并启用 vector decoder 路径。
- tensor 模式下，`BuildRoCC` accelerator 列表会被 tensor wrapper 替代。
  如果需要 tensor 和其它 RoCC 同时存在，需要修改 `roccs` 构造逻辑。
- `ShuttleTensorUnit.tlNode` 当前没有被 `ShuttleTile` 接出。tile-local master
  流量应使用 tensor `atlNode`；向外部系统发 master 请求则可使用 RoCC
  wrapper 的 `tlNode`。
- RoCC HellaCache memory interface 被禁用，并断言不能发请求。tensor 访存
  应使用 diplomatic TileLink node。
- `io_sg_base` 在 `ShuttleTileModuleImp` 中被赋值两次：先赋成
  `sgtcm.base + hartId * sgtcm.size`，后面又赋成常量 `0x78000000L.U`。按
  Chisel last-connect 语义，后面的常量会成为有效连接。依赖 per-hart SGTCM
  base 前需要先修正或确认这处逻辑。
- `SGTCM.sgnode` 的 lane/bank 专用路径只接给 standalone `ShuttleVectorUnit`
  的 `sgNode`。tensor 模式会禁用 standalone vector unit，因此当前 tensor
  无法通过这条路径访问 `SGTCM.sgnode`。
