# Shuttle TCM 地址映射、Banking 与跨 Hart 可见性说明

本文总结当前 `ShuttleTile` 中 TCM 的行为。讨论默认以
`tcmParams.size = 2097152` 为例，也就是：

```text
2097152 = 0x200000 = 2 MiB
```

## 1. 参数如何制导物理地址生成

TCM 参数主要有三个：

```scala
tcmParams.base
tcmParams.size
tcmParams.banks
```

TCM 的初始地址窗口由下面这个 `AddressSet` 描述：

```scala
AddressSet(tcmParams.base, tcmParams.size - 1)
```

如果 2 MiB TCM 的 base 是 `0x70000000`，那么原始窗口是：

```text
0x70000000 - 0x701fffff
```

每个 tile 上的 `AddressOffsetter` 会计算：

```scala
replicationSize = (1 << log2Ceil(p(NumTiles))) * tcmParams.size
mask            = tcmParams.size - 1
offset          = replicationSize
io.base         = tcmParams.base + tcmParams.size * hartId
```

`AddressOffsetter` 的匹配和改写逻辑是：

```scala
contains(addr) = ((addr ^ io.base) & ~mask) == 0
adjusted       = addr | offset
```

这里 `mask = size - 1` 的含义是：匹配以 `io.base` 为起点、大小为
`size` 的一个窗口。

### NumTiles = 1

如果只有一个核：

```text
replicationSize = 0x200000
hart0 io.base   = 0x70000000
```

hart0 的本地低地址窗口是：

```text
0x70000000 - 0x701fffff
```

经过 `AddressOffsetter` 后会变成：

```text
0x70200000 - 0x703fffff
```

### NumTiles = 2

如果有两个核：

```text
replicationSize = 0x400000

hart0 io.base = 0x70000000
hart1 io.base = 0x70200000
```

hart0 自己的低地址窗口：

```text
0x70000000 - 0x701fffff
  -> AddressOffsetter -> 0x70400000 - 0x705fffff
```

hart1 自己的低地址窗口：

```text
0x70200000 - 0x703fffff
  -> AddressOffsetter -> 0x70600000 - 0x707fffff
```

之后，`tcmMasterReplicator` 会创建 alias 区域，并把 alias 折回本地
TCM。它使用：

```scala
local  = tcmParams.addressSet
region = tcmParams.addressSet.widen((replicationSize << 1) - tcmParams.size)
```

两个核时，这个 region 是：

```text
0x70000000 - 0x707fffff
```

有效的 replication bits 是：

```text
0x600000
```

`tcmMasterReplicator` 还会把低半区减掉：

```scala
TLFilter.mSubtract(AddressSet(tcmParams.base, replicationSize - 1))
```

两个核时，被减掉的是：

```text
0x70000000 - 0x703fffff
```

所以当前本地 master 路径上真正可见的是高地址 alias：

```text
0x70400000 - 0x707fffff
```

这个高地址 alias 再由 `RegionReplicator` 折回本地 TCM SRAM：

```text
0x70400000 - 0x705fffff -> 本 tile 的 TCM 存储体
0x70600000 - 0x707fffff -> 本 tile 的 TCM 存储体
```

这里有一个重要 caveat：Shuttle 的 PMA/TLB 会根据下游 TileLink manager
参数判断物理地址是否合法。由于当前 master 路径把低地址区域减掉了，软件
直接访问低窗口时，有可能在请求到达 `AddressOffsetter` 之前就被 PMA/TLB
判成 access fault。当前更直接可见、更稳妥的是高地址 alias。

## 2. Multi-Bank 连接形状与带宽

TCM bank 的生成代码形状如下：

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

如果：

```text
banks = 2
CacheBlockBytes = 64
```

那么两个 bank 是按 cache line 粒度交错的：

```text
bank0: addr bit 6 = 0
bank1: addr bit 6 = 1
```

例如：

```text
bank0: 0x70000000 - 0x7000003f, 0x70000080 - ...
bank1: 0x70000040 - 0x7000007f, 0x700000c0 - ...
```

增大 `tcmParams.banks` 不会让一次访问的数据宽度变大。每个 bank 仍然是一个
独立的 `TLRAM`：

```scala
beatBytes = shuttleParams.tileBeatBytes
```

如果 tile bus 是 512-bit，也就是 `tileBeatBytes = 64`，那么每个 bank 都是
64-byte beat。`banks = 2` 的含义是两个独立的 64-byte bank，而不是一条
128-byte 的数据通路。

当前本地请求拓扑可以近似理解为：

```text
frontend/dcache -> tensor_xbar --\
tensor tcmslaveNode -------------> tlMasterXbar -> TCM bank0
tensor sgtcmslaveNode ----------/              \-> TCM bank1
rocc atlNode -------------------/
vector atlNode -----------------/
```

这不是一个单一的物理 TCM RAM 端口；但是，带宽是否提升取决于流量能不能真的
并行打到多个 bank。

`banks = 2` 后带宽没有翻倍，常见原因有：

1. 单个 scalar/DCache 访存流通常只有一条 TileLink A 请求流。DCache 输出本身
   是一条 `tl_out.a`。

2. 对 DCache 来说，TCM 更像 uncached/MMIO 路径。`TLRAM` 支持 `Get`/`Put`，
   但不支持 `AcquireB`，所以 DCache 不会把它当普通 cacheable memory。
   uncached 路径使用 `IOHandler`，默认 `nMMIOs = 1`，因此这条路径基本是串行的。

3. 两个请求源必须在同一个周期打到不同 bank，才能看到 bank 并行。如果 vector
   和 accelerator 的访问从同一个 cache-line 相位开始，可能每周期都冲突到同一个
   bank：

```text
cycle 0: vector -> bank0, accelerator -> bank0
cycle 1: vector -> bank1, accelerator -> bank1
```

如果把一个流错开一条 cache line，才更容易看到并行：

```text
cycle 0: vector -> bank0, accelerator -> bank1
cycle 1: vector -> bank1, accelerator -> bank0
```

4. 任何一个共享的 512-bit edge、arbiter、`TLBuffer`、`TLFragmenter`，或者某个
   无法每周期发请求的 source，都可能在 TCM bank 之前成为瓶颈。

所以：

```text
banks = 2 增加的是后端 bank 并行能力；
它不保证单一请求流带宽翻倍；
如果多个请求源在 bank 之前已经被串行化，它也不会让带宽翻倍。
```

最直接的 debug 方法是在每个 TCM bank 的 `in.a.fire` 上加计数器，同时统计有多少
周期发生了多个 bank 同时 fire。

## 3. 为什么当前其他 Hart 看不到这块 TCM

当前代码里，TCM RAM 挂在每个 tile 自己的 `tlMasterXbar` 后面：

```scala
tcm.node := TLFragmenter(...) := TLBuffer() :=
  tcmMasterReplicator(shuttleParams.tcm) := tlMasterXbar.node
```

这意味着 TCM 是本 tile 内部 master 的本地目标。其他 hart 的请求不会进入这个
tile 私有的 `tlMasterXbar`，所以 hart0 不能通过当前路径访问 hart1 的 TCM。

代码里有一段被注释掉的路径，意图是通过 tile slave port 暴露 TCM：

```scala
// (tcmSlaveXbar
//   := tcmSlaveReplicator(shuttleParams.tcm)
//   := tcmSlaveReplicator(shuttleParams.sgtcm)
//   := TLFilter({ m => ... expose only this tile's slice ... })
//   := tlSlaveXbar.node)
```

还有一段被注释掉的 TCM 实例化路径，会把 TCM 放到 `tcmSlaveXbar` 后面：

```scala
// tcm.node := TLFragmenter(...) := TLBuffer() := tcmSlaveXbar.get
```

这些逻辑当前都没有启用。另外，现在 `tcmSlaveXbar` 只有在 SGTCM 存在时才会创建：

```scala
val tcmSlaveXbar = ((shuttleParams.sgtcm.isDefined)).option(TLXbar())
```

所以如果配置里只有 TCM、没有 SGTCM，那么根本没有用于暴露 TCM 的 slave xbar。

如果希望其他 hart 能看到每个 hart 的 TCM 窗口，TODO 大致如下。

1. 当 TCM 或 SGTCM 任意一个存在时，都创建 `tcmSlaveXbar`：

```scala
val tcmSlaveXbar =
  (shuttleParams.tcm.isDefined || shuttleParams.sgtcm.isDefined).option(TLXbar())
```

2. 把 TCM RAM 实例放到 `tcmSlaveXbar` 后面，让本地 master 和外部 slave-port
   流量访问同一个物理 TCM 实体。

3. 恢复 slave-port 路径：

```scala
tcmSlaveXbar
  := tcmSlaveReplicator(shuttleParams.tcm)
  := tcmSlaveReplicator(shuttleParams.sgtcm)
  := TLFilter(...)
  := tlSlaveXbar.node
```

4. 保留或更新 per-tile 的 `TLFilter`，让每个 tile 只暴露属于自己的低地址 slice：

```text
tile0 exposes 0x70000000 - 0x701fffff
tile1 exposes 0x70200000 - 0x703fffff
```

5. 明确最终的软件可见地址约定：

```text
低窗口:  per-hart global TCM window，用于跨 hart 访问
高 alias: 当前 master-side replicator 使用的本地 alias
```

之后需要保证 PMA/TLB 看到的 manager 地址范围和这个软件约定一致。如果本地代码也要
使用低窗口访问自己的 TCM，那么低窗口不能在 core 的 PMA/TLB 视角中不可见。

如果这些连接都恢复正确，两个 hart、每个 hart 2 MiB TCM 时，预期行为是：

```text
hart0 access 0x70000000 - 0x701fffff -> hart0 TCM
hart0 access 0x70200000 - 0x703fffff -> hart1 TCM

hart1 access 0x70000000 - 0x701fffff -> hart0 TCM
hart1 access 0x70200000 - 0x703fffff -> hart1 TCM
```

最后需要一个 directed test：每个 hart 往自己的 TCM 写不同 pattern，然后互相读对方
窗口，确认读到的是对方 tile 的 TCM，而不是本地 alias。
