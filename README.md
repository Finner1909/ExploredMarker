# ExploredMarker

**Your live map is drawing a narrow corridor while your players see ten times
further. This explains why, and the plugin is the smaller half of the answer.**

A ~40-line Paper/Purpur plugin. But the code is not the point — **the diagnosis
is**. If you read this page, understand your own map, and close the tab without
downloading anything, that is a completely successful outcome.

---

## The symptom

You run BlueMap (or another renderer) with `min-inhabited-time` set above `0`,
because you do not want your entire pre-generated world exposed on a public map
before anybody has been there. Sensible.

Then you notice:

- Players have travelled a long way, but the map shows a **thin ribbon** along
  their route.
- The unrendered area is **black at every zoom level**, not low-detail — the
  tiles simply do not exist.
- **A full force-render changes nothing.** `/bluemap update force`, purge and
  re-render, deleting the tile store — same result.
- Nothing is wrong in the logs. No errors, no warnings.

At this point it is natural to blame the renderer. It is not the renderer.

## The diagnosis

BlueMap's own shipped `map.conf` defines the option like this (verbatim):

> **`min-inhabited-time`** — The minimum `inhabitedTime` value that a chunk must
> have to be rendered. The `inhabitedTime` value of a chunk refers to the
> cumulative number of ticks players have been near this chunk. If you set this
> to a value greater than 0, BlueMap will only render chunks that players have
> visited already. Default is 0 (all generated chunks).

The renderer is doing precisely that. The problem is the word **"near"**.

`inhabitedTime` is not a "a player has seen this chunk" counter. In vanilla
server code, `ServerChunkCache.tickChunks()` does:

```java
List<LevelChunk> list = this.tickingChunks;
this.collectTickingChunks(list);          // only chunks with a *ticking* holder
...
for (LevelChunk levelchunk : list) {
    levelchunk.incrementInhabitedTime(elapsed);
    ...
}
```

`collectTickingChunks` walks the spawn-candidate chunks and keeps only those
where `getTickingChunk() != null`. That set is sized by
`DistanceManager.updateSimulationDistance(...)`.

**So `inhabitedTime` accrues inside `simulation-distance`, never inside
`view-distance`.** Those are two different numbers in `server.properties`, and
on a tuned server they are very different numbers, because lowering
simulation-distance is the standard way to cut CPU without cutting how far
people can see.

Work the arithmetic through with an example pair of values — substitute your own
from `server.properties`:

| Setting | Value | Radius | Diameter a player experiences |
|---|---|---|---|
| `simulation-distance` | 6 | ~96 blocks | **192 blocks get inhabited time** |
| `view-distance` | 20 | ~320 blocks | **640 blocks are visible** |

Your players see a 640-block-wide world. Your map is allowed to draw a
192-block-wide one. The other 70% of what they looked at is, as far as the
region files are concerned, **never visited** — and a renderer that honours
`min-inhabited-time` is correct to leave it black. Re-rendering cannot help,
because the input data says the chunks were never inhabited.

Vanilla's default `simulation-distance` is 10 and default `view-distance` is 10,
so an untouched server hides this bug completely. It only appears once you tune
them apart — which is exactly what a server with more than a couple of players
does.

## The evidence

Measured on a small Purpur server, comparing two adjacent region files against
the tiles the renderer had produced for them (a BlueMap hires tile covers 4
chunks, so a 32×32-chunk region maps to 256 tiles):

| Region | Chunks with `inhabitedTime > 0` | Hires tiles rendered |
|---|---|---|
| `r.0.0.mca` (contains spawn) | 844 / 1024 | 228 / 256 |
| `r.1.1.mca` (adjacent; crossed, not lingered in) | **0 / 1024** | **0 / 256** |

Zero of 1024. Not "few". That region was crossed on the ground and in plain
sight — but always from beyond the simulation radius, so not one chunk in it
ever ticked. A subsequent full force-update produced **zero** new tiles, which
is the measurement that clears the renderer entirely: given the same input, it
made the same correct decision.

### Reproduce it on your own server

1. Read `view-distance` and `simulation-distance` out of `server.properties`. If
   they differ, you have the conditions for this.
2. Pick somewhere players demonstrably travel through but do not linger, and
   find the region file covering it: `region = floor(chunk / 32)`,
   `chunk = floor(block / 16)`.
3. Count the chunks in that `.mca` with a non-zero `InhabitedTime` (any NBT
   region reader will do), and count the tiles your renderer wrote for the same
   area.
4. If the tile count tracks the inhabited count rather than tracking where
   players have actually been, this is your bug.

## Your options

Four ways out. **Pick on your own priorities — three of them are not this
plugin, and one of them is the right answer for most people.**

**1. Set `min-inhabited-time: 0`.** Free, instant, no plugin. Everything that
has been *generated* renders — including terrain no player has ever seen. If you
pre-generated your world, this reveals all of it. If your map is private, or you
never pre-generated, **this is the correct answer and you should stop here.**

**2. Raise `simulation-distance` to match `view-distance`.** Fixes the cause,
costs real CPU: mob spawning, block ticks, entity ticks and redstone all scale
with it. On a small server with headroom this is clean and needs no plugin. On
anything CPU-bound it is not an option, which is why the two settings diverged
in the first place.

**3. Accept it.** A corridor map is a legitimate aesthetic. Some servers *want*
the map to show only the well-trodden routes.

**4. This plugin.** Keeps `min-inhabited-time` above 0 (so unvisited world stays
hidden) and keeps simulation-distance low (so the server stays fast), by
widening what counts as "visited" from the ticking radius to the view radius.

## What ExploredMarker does

Every 10 seconds, for each online player, it walks the chunks within the
server's view distance and — **only for chunks that are already loaded, and only
where `inhabitedTime` is still 0** — sets `inhabitedTime = 1`.

One tick is enough to clear a `min-inhabited-time: 1` threshold. It is
negligible next to the millions of ticks that drive regional difficulty.

That is the whole plugin. No config file, no commands, no permissions, no
listeners, no database, no dependencies beyond the server API.

### Why it is safe

- **It only touches loaded chunks.** It calls `World#isChunkLoaded` before
  `World#getChunkAt`, so it can never load or generate terrain. Ground no player
  has been near keeps `inhabitedTime = 0` and can never reach the map. **The
  privacy property of `min-inhabited-time` is preserved** — the definition of
  "visited" widens from the ticking radius to the view radius, it is not removed.
- **Write-once.** Chunks already above 0 are skipped, so a stationary player
  costs one integer comparison per chunk and no writes at all.
- **It never lowers a value**, so genuine accumulated play time cannot be erased
  and regional difficulty is not reset.
- **It never backfills.** Ground explored *before* you install it stays black
  until somebody goes near it again. Retrofitting would mean loading those
  chunks, which is the one thing this refuses to do.

### Cost

The sweep is `(2r+1)²` `isChunkLoaded` calls per player per 10 seconds — about
1,700 comparisons at view-distance 20 — on the main thread, where Bukkit chunk
access is required to be. Once an area is stamped, the writes stop and only the
comparisons remain. If you run view-distance 32 with a large player count and
care about microseconds, measure it; otherwise it disappears into the noise.

### Known limitations

- **It does not backfill** (see above). This is deliberate.
- **It marks what a player could see, not what they looked at.** Chunks behind
  the player get marked too. That is the intended trade — it matches the
  renderer to the player's field of view, not to their gaze.
- **Interaction with `min-inhabited-time` above 1.** Values greater than 1 will
  still hide these chunks. Use `min-inhabited-time: 1` with this plugin.
- **It writes `inhabitedTime`, which also feeds regional difficulty.** Going
  from 0 to 1 tick is far too small to be observable, but it is a shared field
  and you should know it is shared.
- **No commands, no config, no toggle.** If you want it off, remove the jar.

## Install

1. Drop the jar in `plugins/`.
2. Restart.
3. Set `min-inhabited-time: 1` in your map config, if it is not there already.
4. Have somebody walk around. Chunks appear as the renderer next updates them.

Console line on success:

```
[ExploredMarker] Explored-area marking active - the map will cover what players can see (20 chunks)
```

The number in brackets is whatever your server reports as `view-distance`.

## Build

Requires JDK 25 for the Paper 26.2 API as pinned. There is no Gradle wrapper
checked in; use a local Gradle 9.x, or:

```bash
docker run --rm -v "$PWD":/w -w /w gradle:jdk25 gradle --no-daemon build
```

Output: `build/libs/ExploredMarker-1.0.0.jar`.

Targeting an older server? Lower both the `paper-api` coordinate and the Java
toolchain version in `build.gradle`. The code uses only
`Chunk#getInhabitedTime` / `#setInhabitedTime`, `World#isChunkLoaded` and the
Bukkit scheduler, all of which are long-standing API.

## Compatibility

- **Server:** Paper, and Paper forks such as Purpur. It needs the Bukkit
  `Chunk#setInhabitedTime` API, so plain Spigot/CraftBukkit is untested.
- **Built against:** Paper API 26.2, Java 25.
- **Map plugin:** developed against BlueMap, but it touches no map-plugin API
  whatsoever — it only writes a vanilla chunk field. Anything that renders based
  on `inhabitedTime` benefits identically.
- **Java:** 25 as configured; the source itself has no modern-Java requirement.

## Support

**None.** This is published because the diagnosis was worth writing down, not
because anybody intends to maintain a product. It is MIT — fork it, copy the
forty lines into your own plugin, or just take the explanation and fix your
server without it. All three are fine uses of this repository.

Issues and PRs may go unanswered. Please do not treat this as a maintained
dependency.

## Credits

Diagnosis, implementation and documentation developed with AI assistance.

## Licence

MIT — see [LICENSE](LICENSE).
