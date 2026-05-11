# Shuttle TCM Addressing, Banking, and Sharing Notes

This note summarizes the current TCM behavior in `ShuttleTile`, with the concrete
case of `tcmParams.size = 2097152` (`0x200000`, 2 MiB) in mind.

## 1. Parameter-Guided Physical Address Generation

The TCM parameter object provides:

```scala
tcmParams.base
tcmParams.size
tcmParams.banks
```

The address window is initially described as:

```scala
AddressSet(tcmParams.base, tcmParams.size - 1)
```

For a 2 MiB TCM at `0x70000000`, this is:

```text
0x70000000 - 0x701fffff
```

The per-tile address adjuster computes:

```scala
replicationSize = (1 << log2Ceil(p(NumTiles))) * tcmParams.size
mask            = tcmParams.size - 1
offset          = replicationSize
io.base         = tcmParams.base + tcmParams.size * hartId
```

Then `AddressOffsetter` checks:

```scala
contains(addr) = ((addr ^ io.base) & ~mask) == 0
adjusted       = addr | offset
```

So `mask = size - 1` means "match one `size`-byte window whose base is
`io.base`".

For `NumTiles = 1`:

```text
replicationSize = 0x200000
hart0 io.base   = 0x70000000

hart0 local low window:
  0x70000000 - 0x701fffff

AddressOffsetter maps it to:
  0x70200000 - 0x703fffff
```

For `NumTiles = 2`:

```text
replicationSize = 0x400000

hart0 io.base = 0x70000000
hart1 io.base = 0x70200000

hart0 local low window:
  0x70000000 - 0x701fffff
  -> AddressOffsetter -> 0x70400000 - 0x705fffff

hart1 local low window:
  0x70200000 - 0x703fffff
  -> AddressOffsetter -> 0x70600000 - 0x707fffff
```

After that, `tcmMasterReplicator` creates an alias region and folds the alias
back to the local TCM. It uses:

```scala
local  = tcmParams.addressSet
region = tcmParams.addressSet.widen((replicationSize << 1) - tcmParams.size)
```

With two tiles, that makes the region:

```text
0x70000000 - 0x707fffff
```

and the effective replication bits are:

```text
0x600000
```

The master replicator also subtracts the low replicated region:

```scala
TLFilter.mSubtract(AddressSet(tcmParams.base, replicationSize - 1))
```

For two tiles, the subtracted region is:

```text
0x70000000 - 0x703fffff
```

So the region visible to local masters through this path is the high alias:

```text
0x70400000 - 0x707fffff
```

That high alias is folded back to the physical local TCM SRAM by
`RegionReplicator`:

```text
0x70400000 - 0x705fffff -> local TCM storage
0x70600000 - 0x707fffff -> local TCM storage
```

The important caveat is that Shuttle's PMA/TLB checks use the downstream
TileLink manager parameters. Because the low region is subtracted in the current
master path, software-visible access to the low window may be rejected before the
request reaches `AddressOffsetter`, depending on the final manager view on that
edge. The high alias is the more directly visible region in the current local
master path.

## 2. Multi-Bank Shape and Bandwidth

The TCM bank loop is:

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

For `banks = 2` and `CacheBlockBytes = 64`, the two banks are interleaved at
cache-line granularity:

```text
bank0: addr bit 6 = 0
bank1: addr bit 6 = 1
```

Example:

```text
bank0: 0x70000000 - 0x7000003f, 0x70000080 - ...
bank1: 0x70000040 - 0x7000007f, 0x700000c0 - ...
```

Increasing `tcmParams.banks` does not make one access wider. Each bank is still
a `TLRAM` with:

```scala
beatBytes = shuttleParams.tileBeatBytes
```

For a 512-bit tile bus, this means each bank has a 64-byte beat. Two banks give
two independent 64-byte banks, not one 128-byte data beat.

The current local request shape is approximately:

```text
frontend/dcache -> tensor_xbar --\
tensor tcmslaveNode -------------> tlMasterXbar -> TCM bank0
tensor sgtcmslaveNode ----------/              \-> TCM bank1
rocc atlNode -------------------/
vector atlNode -----------------/
```

This is not a single physical TCM RAM port, but bandwidth only improves when the
traffic can actually use multiple output banks in parallel.

The common reasons `banks = 2` does not double observed bandwidth are:

1. A single scalar/DCache stream usually emits one TileLink A request stream.
   The DCache output connects as one `tl_out.a` stream.

2. TCM is treated as uncached/MMIO-like by the DCache because the TLRAM supports
   `Get`/`Put` but not `AcquireB`. The uncached path uses `IOHandler`, and the
   default `nMMIOs = 1` makes this path effectively serial.

3. Two request sources must hit different banks in the same cycle. If vector and
   accelerator streams start at the same cache-line phase, they can conflict:

```text
cycle 0: vector -> bank0, accelerator -> bank0
cycle 1: vector -> bank1, accelerator -> bank1
```

Offsetting one stream by one cache line can expose the potential bank parallelism:

```text
cycle 0: vector -> bank0, accelerator -> bank1
cycle 1: vector -> bank1, accelerator -> bank0
```

4. Any single shared 512-bit edge, arbiter, `TLBuffer`, `TLFragmenter`, or source
   that cannot issue every cycle can become the bottleneck before the TCM banks.

Therefore, `banks = 2` increases backend bank parallelism. It does not guarantee
2x bandwidth for one request stream, and it does not help when multiple streams
are already serialized before the banked TCM managers.

The direct way to debug this is to count `in.a.fire` per TCM bank and also count
how often multiple banks fire in the same cycle.

## 3. Why Other Harts Cannot Currently See This TCM

In the current code, TCM RAMs are attached behind each tile's local
`tlMasterXbar`:

```scala
tcm.node := TLFragmenter(...) := TLBuffer() :=
  tcmMasterReplicator(shuttleParams.tcm) := tlMasterXbar.node
```

That makes the TCM a local per-tile target for masters inside the same tile.
Another hart's requests do not enter this tile's local `tlMasterXbar`, so hart0
cannot reach hart1's TCM through this path.

There is a commented-out path intended to expose TCM through the tile slave port:

```scala
// (tcmSlaveXbar
//   := tcmSlaveReplicator(shuttleParams.tcm)
//   := tcmSlaveReplicator(shuttleParams.sgtcm)
//   := TLFilter({ m => ... expose only this tile's slice ... })
//   := tlSlaveXbar.node)
```

There is also a commented-out TCM instantiation path that places TCM behind
`tcmSlaveXbar`:

```scala
// tcm.node := TLFragmenter(...) := TLBuffer() := tcmSlaveXbar.get
```

Those pieces are currently disabled. In addition, `tcmSlaveXbar` is only created
when SGTCM exists:

```scala
val tcmSlaveXbar = ((shuttleParams.sgtcm.isDefined)).option(TLXbar())
```

So in a TCM-only configuration there is no slave xbar for TCM exposure at all.

To let other harts see per-hart TCM windows, the TODO is:

1. Create `tcmSlaveXbar` when either TCM or SGTCM exists:

```scala
val tcmSlaveXbar =
  (shuttleParams.tcm.isDefined || shuttleParams.sgtcm.isDefined).option(TLXbar())
```

2. Move the TCM RAM instantiation behind `tcmSlaveXbar`, so local masters and
   external slave-port traffic access the same physical TCM instance.

3. Restore the slave-port path:

```scala
tcmSlaveXbar
  := tcmSlaveReplicator(shuttleParams.tcm)
  := tcmSlaveReplicator(shuttleParams.sgtcm)
  := TLFilter(...)
  := tlSlaveXbar.node
```

4. Keep or update the per-tile `TLFilter` so each tile exposes only its own low
   per-hart slice:

```text
tile0 exposes 0x70000000 - 0x701fffff
tile1 exposes 0x70200000 - 0x703fffff
```

5. Decide the intended local software contract:

```text
low window:  per-hart global TCM window, useful for cross-hart access
high alias:  local alias used by the current master-side replicator
```

Then make sure PMA/TLB manager visibility matches that contract. If local code
is expected to use the low window, the low window must not be invisible to the
core's PMA/TLB checks.

After this is wired correctly, the expected two-hart behavior for 2 MiB TCMs is:

```text
hart0 access 0x70000000 - 0x701fffff -> hart0 TCM
hart0 access 0x70200000 - 0x703fffff -> hart1 TCM

hart1 access 0x70000000 - 0x701fffff -> hart0 TCM
hart1 access 0x70200000 - 0x703fffff -> hart1 TCM
```

The final step should be a directed test that writes distinct patterns from each
hart into its own TCM window, reads the other hart's window, and checks that the
observed data is from the other tile's TCM rather than a local alias.
