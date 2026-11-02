# Chunk-cell sync

How this fork shares JourneyMap maps. It replaces upstream JourneySync's tile engine, which watched JourneyMap's PNG files and uploaded whole 512×512 tiles.

## Why the file-based design fails

JourneyMap 5.2.x loads a region image from disk once, keeps it in memory for about 4 hours (`RegionImageCache` passes 15,000 to `expireAfterAccess` in seconds, though it was meant as milliseconds), and writes the whole image back every 30 s while it changes. A tile written to disk while JourneyMap holds that region is never displayed, and the next flush overwrites it. In the two-client test, two players in the same region kept overwriting each other's new pixels for as long as both stayed there: the server held 78,848 explored pixels in tile (5, 5), Developer's file had 56,064, Alex's 45,056.

## Units

A **cell** is one chunk of one map type: 16×16 pixels. A region tile holds 32×32 cells. Map types are `day`, `night`, `topo` and cave slices `0` to `15`, the same names JourneyMap uses for its folders, in every dimension.

## Client

**Capture.** A Mixin at the return of `ChunkRenderController.renderChunk(RegionCoord, MapType, ChunkMD)`. JourneyMap paints a chunk into a reusable 16×16 buffer and copies it into the region's in-memory image in that method's `finally` block, after it has already called `setChunkImage`. So the final pixels only exist once `renderChunk` returns (an early version hooked `setChunkImage` and saw the previous render, or nothing on new ground). After a successful surface render the hook reads the chunk's day, night and topo cells, since JourneyMap draws all three together; after a cave or topo render it reads that map type. It copies each cell's 256 pixels into a pending map, replacing an older pending copy. JourneyMap re-renders nearby chunks all the time, so a cell is only queued if its pixels differ from the last copy sent.

**Upload.** Every 2 s the client groups pending cells by region tile and sends them. Payload: a 128-byte mask of which cells are included, and a PNG of those cells stacked into a 16-pixel-wide strip. Packets stay under 30 kB; a batch that doesn't fit is split by cells.

**Apply.** Received cells are drawn into JourneyMap's own image whenever JourneyMap has that region loaded, through `ImageHolder.partialImageUpdate` (which takes the holder's write lock) and then `finishPartialImageUpdates` (which marks the image dirty and refreshes the texture). JourneyMap then writes the file itself on its next flush. When the region isn't loaded, the cells are merged into the PNG on disk.

The two paths race when JourneyMap loads a region while a disk merge is running. A Mixin wraps `ImageHolder.getTexture()`, where JourneyMap reads the file, in a lock per file path, and records the holder as loaded; another on `ImageHolder.clear()` removes it. The apply step takes the same lock, then either draws into the registered holder or merges on disk. So JourneyMap never reads a half-written file, and a merge never lands in a file JourneyMap has already loaded.

Applying never calls `setChunkImage`, so received cells are never captured and sent back.

**Merge rule.** A received pixel replaces the local one unless it is transparent. That's how JourneyMap composites its own chunk renders (`SRC_OVER`, opaque), so an unexplored pixel never erases an explored one.

## Server

**Store.** `<world>/journeysync/cells/DIM<d>/<type>/<x>,<z>.png` plus a `.rev` file with 32×32 revision numbers, one per cell. A server-wide revision counter goes up by one for every cell that changes. A small LRU cache keeps recently used tiles in memory.

**Merge.** Live uploads overwrite the server's pixels where the upload isn't transparent, the same rule as the client. Arrival order decides which observation is newest, so client clocks don't matter. A cell whose pixels didn't change keeps its revision and isn't broadcast. Otherwise it gets the next revision and the merged cell goes to every other player.

**Seeding.** Maps players drew before installing the mod are uploaded once as *fill-only* patches: they only fill pixels the server has never seen, so an old map never overwrites newer shared data.

**Handshake.** At login the server greets every player with its protocol version and the shared map's id. A client that gets no greeting sends nothing, so the mod is optional on both sides. Once JourneyMap has settled on this world's folder (it moves the folder when JourneyMap Server's world id arrives, so the client waits up to 15 s for the id, and starts over if the folder still moves), the client says hello.

**Catch-up.** The client remembers, per server world, the last revision it has fully received. Its hello carries that number, and the server queues every cell with a higher revision, tile by tile, followed by a mark saying "everything up to revision N has been sent". The client stores N once it has applied everything before the mark. A new player starts at 0 and gets the whole map.

**Threads.** Network handlers only queue work. One server worker thread does merges, disk IO, catch-up and sending, at a configurable rate per player. One client worker thread does the same on the client side.

## Bandwidth

| What | Size |
|---|---|
| One changed cell | about 0.3 to 1 kB as part of a strip PNG |
| A player exploring | a few kB per second while moving, nothing while standing still |
| New player's first sync | about the size of the shared map (46 MB for the test map), rate-limited |
| Seeding an existing local map | its size, once per player per server |

## What stays from upstream

Waypoint sync (owner, revision and tombstones), `/jsync status|pause|resume`, and the config file. The protocol is version 4; upstream's versions 1 and 2 don't talk to it.

## Not handled yet

- A player who deletes only part of their JourneyMap data keeps the stored revision, so the deleted part comes back only with `/jsync resync`. If the whole map folder is gone, the client notices on join and asks for everything.
- Per-team maps. Every player on the server shares one map.
- Turning `downloadEnabled` off still advances the stored revision, so cells sent while it was off aren't sent again later. Use `/jsync resync` after turning it back on.
