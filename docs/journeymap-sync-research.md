# Sharing JourneyMap maps between players on one GTNH 2.9.0 server

Research for the Akliz group server, 2026-11-01. Nothing was built. Both repos and the Prism instances were only read.

## Summary

- **The license is not a blocker.** JourneyMap 5.2.23 for 1.7.10, the version GTNH 2.9.0 ships, is LGPL 2.1 with public source. The jars in the pack are byte-identical to TeamJM's GitHub release. Reading and writing its files, calling its classes, and Mixins into it are all allowed, and you can publish such a mod. Decompiling isn't needed because the source is on GitHub. The "All Rights Reserved" terms on journeymap.info cover the current JourneyMap 6 line, not 5.2.x.
- **Someone has already built this.** [donkey-tt/JourneySync](https://github.com/donkey-tt/JourneySync) (MIT, first release 2026-10-25) is a GTNH 1.7.10 mod for both sides. It syncs JourneyMap tiles and waypoints through the server. It is 8 days old, has no downloads or stars, targets JourneyMap 5.2.10 on GTNH 2.8.x, and doesn't sync the `topo` map.
- **JourneyMap holds region images in memory for hours and writes them back whole.** A tile written to disk while JourneyMap has that region loaded is ignored, and the next save overwrites it. Any sync design has to account for that. Section 3.1 shows a safe way to do it with JourneyMap's public methods.
- **Recommendation.** Review and test JourneySync first on the local 2.9.0 server copy. If it fails, build our own sync mod, reusing chunk-overlay's networking and test scripts and following the design in section 3.1. Shared waypoints alone and an offline merge script are smaller fallbacks.

## 1. License

### 1.1 Which builds the pack uses, and who publishes them

| Jar | Where | SHA-1 |
|---|---|---|
| `journeymap-1.7.10-5.2.23-fairplay.jar` | `C:\Users\jacks\GTNH-backups\journeymap-fairplay-5.2.23\` (what 2.9.0 ships) | `1b604cfc7a887644f33f1b06e2dbeb846e6f5c6d` |
| `journeymap-1.7.10-5.2.23-unlimited.jar` | Your 2.9.0 Prism instance `mods\` (swapped in today at 16:53) | `a2a6c7de12703ff6da34afb3c634704892f2a6ec` |
| `JourneyMapServer1.0.5_MC1.7.10.jar` | Akliz server `mods\` (read from the 2.9.0 backup) | `819295fc54db5305fd863a8ac56d00d2f9f7c12d` |

Both JourneyMap jars match the assets of release [5.2.23 on TeamJM/journeymap-legacy](https://github.com/TeamJM/journeymap-legacy/releases) byte for byte. I downloaded the release and compared hashes. The release also ships `journeymap-1.7.10-5.2.23-unlimited-sources.jar`.

[TeamJM/journeymap-legacy](https://github.com/TeamJM/journeymap-legacy) is TeamJM's own repository, not a GTNH fork. Mysticdrew, a JourneyMap author, makes most commits and tags releases. GTNH developers contribute too (eigenraven, Alexdoru, mist475, Algent and others), and the build uses GTNH's ExampleMod buildscript (`gtnh.settings.blowdryerTag` in `gradle.properties`). The 5.2.x line has Mixins (`journeymap.client.mixin.MixinLoadingPlugin`), which TechBrew's old 5.1.x builds didn't have.

FairPlay and Unlimited come from the same source. `FeatureManager` picks a policy set at runtime: FairPlay lets servers turn off radar and cave mapping, and Unlimited ignores that. The license is the same for both.

JourneyMap Server 1.0.5 lists mysticdrew and techbrew as authors in `mcmod.info`. The jar has no license file, and I found no public source. Mysticdrew's [journeymap-bukkit](https://github.com/mysticdrew/journeymap-bukkit) repo is MIT but holds only a build file and a `plugin.yml`. With no license grant, treat JourneyMap Server as all rights reserved. None of the options below need to touch it. They only read the world ID it sends, which JourneyMap exposes as `JourneymapClient.getInstance().getCurrentWorldId()`.

### 1.2 What the license says

- The repo's [`LICENSE`](https://github.com/TeamJM/journeymap-legacy/blob/master/LICENSE) is the GNU LGPL 2.1. Mysticdrew added it on 2023-03-06, and GitHub reports the repo as `LGPL-2.1`.
- The `license.txt` inside both jars says "Copyright (c) 2011-2017 Techbrew LLC … LGPL v2.1". It adds that JourneyMap "may only be distributed in a Modpack" under the conditions on journeymap.info. That condition governs redistributing the JourneyMap jar, which none of these options do.
- The Java files still have old 2017 headers saying "All Rights Reserved" (for example `RegionImageCache.java`). The explicit grant in `LICENSE` and `license.txt` comes later and from the same rights holder, so LGPL 2.1 is the reading to rely on. If you publish a mod widely, a one-line confirmation from TeamJM on their Discord would settle it.
- The [JourneyMap licensing page](https://teamjm.github.io/journeymap-docs/6.0.x/about/licensing/) says JourneyMap is all rights reserved, forbids modifying it "even for personal use", allows decompiling to learn how it works, and forbids extracting code from the jar. It also says TeamJM's GitHub projects are covered by their own licenses. This page describes JourneyMap 6, which is a separate, closed-source line. See 1.4.

### 1.3 What each kind of use may do under LGPL 2.1

| Use | Allowed? | Notes |
|---|---|---|
| A separate mod that reads or writes JourneyMap's PNG and JSON files | Yes | No JourneyMap code is copied. The files are the player's own map data. This is fine under any JourneyMap license, including JourneyMap 6's. |
| A mod that calls JourneyMap's public classes (`RegionImageCache`, `WaypointStore`, `JourneymapClient`, `FileHandler`) | Yes | LGPL §5 says such a program, "in isolation, is not a derivative work of the Library". You choose your own license. Don't copy JourneyMap source into your mod; if you do, that part stays LGPL. |
| A real JourneyMap client API on 1.7.10 | No API on 5.2.23 | The 5.2.23 jar has no `journeymap.client.api` or `journeymap.api` package. TeamJM's [journeymap-api](https://github.com/TeamJM/journeymap-api) has a `1.7.10_2.0.0` branch, but that is for JourneyMap 6 (see 1.4). On 5.2.x, "the API" means JourneyMap's own public classes, which is what Navigator and JourneySync use. |
| Mixins or ASM patches into JourneyMap classes at runtime | Yes | LGPL §2 allows modifying the library. Patching at load time doesn't even distribute a modified JourneyMap. GTNH's Navigator already mixes into `Fullscreen`, `MiniMap`, `TextureCache`, `WaypointManager` and `RenderWaypointBeacon`. |
| Decompiling to learn the file format | Allowed, and not needed | The source is on GitHub and in the `-sources.jar`. LGPL §6 also requires that combined works allow reverse engineering. |
| Forking JourneyMap or sending a pull request | Yes | You could add a "reload region from disk" hook or sharing upstream. GTNH people already land PRs there. |
| A private mod for the friends' server | Yes | Giving friends a jar counts as distribution. If your mod contains no JourneyMap code, there are no LGPL duties. If it contains copied or modified JourneyMap code, give your friends the source. |
| Publishing the mod (GitHub, CurseForge, Modrinth) | Yes | Same rules. Don't bundle the JourneyMap jar or classes. MIT, LGPL or GPL all work for your own code. Navigator is LGPL-3.0 and VisualProspecting is MIT. |

This is a reading of the license texts, not legal advice.

### 1.4 JourneyMap 6 on 1.7.10

TeamJM now publishes JourneyMap 6 for 1.7.10 on CurseForge, for example [journeymap-forge-1.7.10-6.0.2](https://www.curseforge.com/minecraft/mc-mods/journeymap/files/8658232) from 2026-08-15, marked "All Rights Reserved". [Navigator added JourneyMap 6 support](https://github.com/GTNewHorizons/Navigator/pull/20) in July 2026, and Navigator 1.1.10 detects it through `journeymap/api/v2/client/IClientAPI`. GTNH 2.9.0 still ships 5.2.23.

If the pack or your group ever moves to JourneyMap 6:

- Mixins and file-format hacks against its internals would breach its license, which forbids modification. The [journeymap-api](https://github.com/TeamJM/journeymap-api) license lets you write code that uses the API, but not redistribute the API classes.
- JourneyMap 6 has server-managed "global" waypoints when JourneyMap runs on the server, plus waypoint sharing through chat ([server docs](https://teamjm.github.io/journeymap-docs/6.0.x/server/multiplayer/), [waypoint docs](https://teamjm.github.io/journeymap-docs/6.0.x/client/waypoints/)). Neither feature shares explored terrain.

So a 5.2.x tile-sync mod would need rework, or would have to stick to files only, if you switch to 6.

## 2. Existing solutions

| Project | MC / loader | What it shares | License | Fit for this group |
|---|---|---|---|---|
| [donkey-tt/JourneySync](https://github.com/donkey-tt/JourneySync) 2.0.0 | 1.7.10 Forge (GTNH), both sides | Tiles and waypoints, through the server | MIT | Close match. Details below |
| [CivPlatform/map-sync](https://github.com/CivPlatform/map-sync) | Modern Fabric | Chunk map data through a separate sync server, drawn into JourneyMap, VoxelMap and Xaero's | GPL-3.0 | Not usable on 1.7.10. Good design reference: "Map-Sync tracks a timestamp per chunk" so older data never overwrites newer |
| JourneyMap Shared Maps (terut) | Modern | Explored terrain through the server, both sides, optionally per FTB team | Unknown | Only a [ModpackIndex listing](https://www.modpackindex.com/mod/170631/journeymap-shared-maps). Not 1.7.10 |
| SharedAtlasJM | NeoForge 1.21.1 | Which chunks each player explored, drawn as coloured squares; no images | Unknown | The idea behind option 3.4. Not 1.7.10 |
| JourneyMap Waypoint Syncing (JMWS) | Modern | Waypoints | Unknown | Its author stopped at 1.2.6 because JourneyMap 6 now syncs waypoints itself |
| JourneyMap 6 built-in | 6.x, including 1.7.10 6.0.x | Server "global" waypoints, chat sharing | ARR | Waypoints only, and needs JourneyMap 6 on client and server. GTNH 2.9.0 uses 5.2.23 |
| [JourneyMap docs: map a multiplayer server](https://teamjm.github.io/journeymap-docs/6.0.x/tools/multiplayer-server/) | 5.1.3+ | Copy the server world, auto-map it in singleplayer, zip the result and hand it out | n/a | Manual. Maps the whole world, not what players explored |
| [GTNH-Web-Map](https://github.com/GTNewHorizons/GTNH-Web-Map) | 1.7.10 Forge, server only | A Dynmap fork; renders the world on the server into a browser map | Apache-2.0 | Not JourneyMap, but a shared map. See 3.5 |
| VisualProspecting 1.5.42 | GTNH | [Team sharing](https://github.com/GTNewHorizons/VisualProspecting) of ore veins and fluids for GTNHLib teams, replayed on login | MIT | Ores only. Its `ProspectionSharing` packets (split by size, first/last flags) show the same upload-merge-replay flow a tile sync needs |
| Navigator 1.1.10 `JMWaypointManager` | GTNH | One temporary "active" waypoint per layer, kept in memory | LGPL-3.0 | Not persistent shared waypoints. See 3.3 |

GitHub search also returns repos like `Journeymap-Full-Version` that offer "free full version" downloads. Those are not TeamJM's. Get JourneyMap only from TeamJM's GitHub releases, CurseForge or Modrinth.

### 2.1 JourneySync in detail

From the README and the 2.0.0 sources jar (about 4,900 lines, read in part):

- **How it works.** On join, the server sends a manifest of SHA-1 hashes for every tile, and the client downloads what it lacks and uploads what the server lacks. While you play, it watches JourneyMap's tile folders and uploads changed tiles after a 2 s settle time. Transfers are split into ACKed packets of 24 KB or less, with a token-bucket rate limit. The server keeps one merged copy under `<world>/journeysync/`.
- **Merge rule.** In `PixelMerge.mergePng`, every non-transparent incoming pixel replaces the stored pixel, and transparent pixels keep the stored one. Coverage only grows, but the last upload wins wherever both have pixels. A player whose tile includes old terrain can overwrite newer terrain from someone else in the same 512×512 tile. The client-side guards (`ShadowStore`, `UploadGuards`) block uploads that lost content. I didn't confirm whether they also stop stale chunks from winning.
- **Applying downloads.** It writes the file, then replaces the live texture in place with `TextureImpl.setImage`, through reflection in `JmBridge`. That skips JourneyMap's write lock. If JourneyMap has unsaved chunk renders in that region, they can be lost or race. Waypoints are applied with `WaypointStore.instance().reset()`.
- **Waypoints.** Owner and revision per waypoint. Only the owner or an op can delete. Deletes propagate as tombstones.
- **Gaps for us.** `TileKey` accepts only `day`, `night` and numeric cave slices, so `topo` tiles (28 tiles in your map) don't sync. It was built and tested against JourneyMap 5.2.10 on GTNH 2.8.x. Every reflection target it uses exists in 5.2.23 with the same signature: `FileHandler.getJMWorldDir(Minecraft)`, `RegionImageCache.getRegionImageSet(RegionCoord)`, `RegionImageSet.getHolder`, `ImageHolder.getTexture`, `TextureImpl.setImage(BufferedImage, boolean)`, `JourneymapClient.toggleTask`, and the `multithreadTaskController` field. Server handlers run on Netty threads under one lock, including PNG decode and encode.
- **Trust.** One author, 28 commits between 2026-10-24 and 2026-10-28, 0 downloads, no issues. It runs on your server and on every friend's PC. Read the code and build the jar yourself from a reviewed commit rather than using the release jar. Network paths are validated by a strict regex in `TileKey.parse`, which is the path traversal check I looked for first.

## 3. Technical options

Numbers below come from your merged map, `Minecraft~Server_c1f080ef~…`: 646 tiles and 46 MB. Day has 186 tiles (20 MB), night 186 (19 MB), caves 246 (3 MB) and topo 28 (under 1 MB). The median tile is 31 KB and the largest 353 KB. Waypoints take 173 KB in 109 files. PNGs are already compressed, so gzip saves nothing.

Forge 1.7.10 limits client-to-server custom payloads to about 32 KB, so every option that uploads splits data into parts. chunk-overlay's `Payloads.PART_SIZE` is 30,000 bytes.

The folder name starts with the name each player gave the server in their server list (`Minecraft~Server_`, `GGG_`, …). Only the world ID suffix matches between players. A mod should ask JourneyMap with `FileHandler.getJMWorldDir(mc)` instead of building the name.

### How JourneyMap 5.2.23 reads and writes tiles

From the 5.2.23 source:

- `ImageHolder.getTexture()` reads a region PNG from disk once, when JourneyMap first needs that region and map type. After that the image lives in memory. New chunk renders are drawn into it (`partialImageUpdate`, opaque composite at the chunk's 16×16 offset).
- `ImageHolder.writeImageToFile()` writes the whole in-memory image over the file. Writes happen on the 30 s auto-flush when dirty (`RegionImageCache.flushInterval`), in `stopMapping()`, in `DataCache.purge()`, when a region is evicted from the cache, and in a JVM shutdown hook.
- Regions are evicted with `expireAfterAccess(regionCacheAge, TimeUnit.SECONDS)`, where `regionCacheAge` is `flushInterval / 2` = 15,000. That value was meant as milliseconds but is passed as seconds, so a region stays in memory for about 4 hours after last use, which in practice means the whole session.
- **What this means for sync.** If a tile file changes on disk while JourneyMap has that region in memory, the map doesn't show the change. And if the player then maps any chunk in that region, JourneyMap's next flush overwrites the downloaded file with its own copy. `RegionImageCache.clear()` is no fix either: it clears the holders before invalidating them, so unsaved renders are thrown away.
- **A safe reload exists.** `JourneymapClient.stopMapping()` flushes every dirty image synchronously. About a tick later `MappingMonitorTask` calls `startMapping()`, which calls `reset()`. That runs `DataCache.purge()` (flush, then drop every region image so it reloads from disk) and `WaypointStore.instance().reset()` (reload waypoint JSON). So you call `stopMapping()`, merge the downloaded tiles into the files on disk, and let mapping restart. All of these methods are public. I haven't run this sequence in game yet, so it is the first thing to test.
- **When the folder becomes known.** Mapping starts on the first client tick that has a world. On a multiplayer server the folder depends on the world ID packet from JourneyMap Server. When it arrives, `setCurrentWorldId` stops mapping, and mapping restarts in the right folder. A sync mod should wait until `getCurrentWorldId()` is non-null before touching files.
- **Waypoint format.** One pretty-printed JSON file per waypoint in `waypoints/`, named `<id>.json`, where `id` is `name_x,y,z`. Fields are `id, name, icon, x, y, z, r, g, b, enable, type, origin, dimensions`. The same name at the same spot from two players gets the same id.

### 3.1 File sync through the server (your proposed design, adjusted)

**How it works.** A mod on both sides. The server stores one merged map under the world folder. The client uploads tiles that changed, the server merges them, and on login it sends the client what changed since its last sync. The client applies downloads with the stop, merge, restart sequence above, so it never fights JourneyMap's cache.

Changes to the plan as you described it:

- **Upload every few minutes, not on logout.** On logout the connection is closing and the game may be quitting, so a large upload there is unreliable. Upload changed tiles every few minutes, after JourneyMap's 30 s flush has written them, and do a best-effort final pass.
- **Apply downloads in the background.** Don't try to beat JourneyMap to the folder at login. A first sync is 46 MB, so download into a staging folder, then apply in one stop, merge, restart step when it's complete. Later syncs are small.
- **Use per-chunk timestamps for "newer on top".** JourneyMap's PNGs have no timestamps. The client can tell which 16×16 chunk cells it re-rendered by comparing its tile with the last version it got from the server. It uploads only those cells with the current time. The server keeps a 32×32 grid of times per tile, and the newest cell wins. That is what map-sync does, and it fixes JourneySync's last-upload-wins problem. Merge transparency the way your Pillow script did: an empty pixel never erases a filled one.
- **Sync every map type.** That means `day`, `night`, `topo` and cave slices `0` to `15`, for every `DIM*`.

| | |
|---|---|
| License | Fine. It uses files plus public JourneyMap methods, with no copied code. |
| Effort | 1 to 2 weeks for a tested mod. You can reuse chunk-overlay's optional-on-both-sides handshake (`HelloMessage`, `PROTOCOL`), part splitting and reassembly (`Payloads`, `SnapshotPartMessage`), `MainThreadQueue`, the functional test setup and `pack-server.ps1` / `pack-client.ps1`. New work is the tile store, the merge, upload diffing, rate limiting and the apply step. |
| Risks | The stop and restart causes a short hitch and reloads map textures. Keep it to a few times per session. Two clients have to be tested at once. Server disk grows by about the map size, 50 MB now. PNG decode and encode must run off the server thread. |
| Bandwidth | First sync for a new player is about 46 MB. Rate-limited to 512 KB/s, that takes about 90 s in the background. After that, a session exploring new ground changes perhaps 20 to 100 tile files across map types, 0.5 to 7 MB at whole-tile size. Per-cell uploads are much smaller. |
| Who needs it | The server, plus each player who wants the shared map. Players without it are unaffected. |

### 3.2 Live sync by patching JourneyMap's tile cache

**How it works.** The same server side as 3.1. On the client, a Mixin or accessor into `ImageHolder` draws received chunk cells straight into the in-memory image under JourneyMap's own `writeLock`, the same way JourneyMap draws its own renders. Then it marks the image dirty. No stop and restart, and other players' exploring shows up within seconds.

| | |
|---|---|
| License | Allowed under LGPL 2.1, as Navigator's Mixins show. Not allowed against JourneyMap 6. |
| Effort | 3 to 5 days on top of 3.1. `partialImageUpdate` and `writeLock` are package-private, so you need a Mixin accessor. |
| Risks | It breaks if JourneyMap's internals change in a 5.2.x update. Threading: map tasks write the same images from worker threads. JourneySync's `setImage` route skips the lock. |
| Bandwidth | The same totals as 3.1, spread over the session. |
| Who needs it | The same as 3.1. |

Treat this as a later upgrade to 3.1, not a separate option.

### 3.3 Shared waypoints only

**How it works.** The server keeps a list of shared waypoints with owner, revision and delete tombstones, like JourneySync. Clients add or remove them through `WaypointStore.instance().save(waypoint)` and `remove(waypoint)`, which are public and write the JSON for you, or write the JSON and call `WaypointStore.instance().reset()`. Skip `Death` waypoints, and leave `enable` (the show or hide toggle) as each player's own setting. Mark shared ones with a name prefix or colour, because 5.2.x has no waypoint groups.

Navigator's `JMWaypointManager` doesn't fit. It holds one temporary waypoint per layer in memory, for things like VisualProspecting's "go to this vein" marker, and never saves it to JourneyMap's store.

| | |
|---|---|
| License | Fine. |
| Effort | 2 to 4 days, using chunk-overlay's networking. |
| Risks | Low. Name clashes. Some players may not want their waypoints shared, so add an opt-in per waypoint, such as a name prefix. |
| Bandwidth | Under 200 KB for all 109 waypoints, then a few bytes per change. |
| Who needs it | The server plus each player who wants it. |

This is a useful first piece of 3.1 and can ship on its own.

### 3.4 Other players' explored area as a Navigator layer

**How it works.** The server collects which chunks each player has mapped. That can be reported by the client mod, or approximated on the server from where players have been. Clients draw those chunks as a Navigator layer, either as tinted squares (like SharedAtlasJM, and like chunk-overlay's `ChunkLayer` and `ChunkStep`) or as other players' tile images drawn on top.

| | |
|---|---|
| License | Fine. It only uses the Navigator API. |
| Effort | Squares: 2 to 3 days, mostly copied from chunk-overlay. Images: 1 to 2 weeks, plus texture memory for up to 46 MB of images. |
| Risks | Navigator shows one layer at a time. Turning this on turns off VisualProspecting's ore layer, ServerUtilities' claims layer and chunk-overlay's layer. Layers draw over JourneyMap's tiles, so images would cover your own newer terrain. Squares only say that someone has been there. They don't show terrain. |
| Bandwidth | Squares: a few KB per player. Images: the same as 3.1. |
| Who needs it | The server plus each viewer. |

Weak for this goal. It's only worth doing as a "who explored here" view.

### 3.5 Server-side rendering instead of JourneyMap

**How it works.** [GTNH-Web-Map](https://github.com/GTNewHorizons/GTNH-Web-Map), GTNH's Dynmap fork, runs on the server. It renders chunks from the world and serves a browser map on its own port. Akliz supports Dynmap: you [create a forwarded port in Command Center](https://help.akliz.net/docs/install-dynmap-a-web-based-dynamic-map). Sending server renders into JourneyMap instead would mean writing a renderer that matches JourneyMap's colours. That is a big job with no payoff over 3.1.

| | |
|---|---|
| License | Apache-2.0. Install it as is. |
| Effort | About an hour to install and configure, plus `/dynmap fullrender` time. |
| Risks | CPU and disk on the Akliz server. Hi-res maps "use quite a bit of disk space", per its README. It renders everything loaded, not just what players explored. It's a browser tab, not the in-game map or minimap. Its latest release is `0.4-beta-1` (2026-06-09). |
| Bandwidth | Only to browsers viewing the map. Nothing goes to Minecraft clients. |
| Who needs it | The server only. |

A good complement if people want a shared overview map. It doesn't share the JourneyMap view in game.

### 3.6 Offline merge tool

**How it works.** A script (Python with Pillow, or a small Java CLI so friends don't need Python) takes several map folders and writes one merged folder. Each player zips their `journeymap\data\mp\<name>_c1f080ef…` folder and shares it on Discord or Drive. One person merges, and everyone copies the result back. It extends the merge that already worked once. Pair folders on the world ID suffix, not the server-list name. Lay higher-priority pixels over lower ones with alpha compositing, and union waypoints by `id`. Without per-chunk times, "newer" has to mean a per-player priority you pick, or each tile file's modified time.

| | |
|---|---|
| License | Fine. It only touches the data files. |
| Effort | Half a day, building on the existing script. |
| Risks | Everyone must close the game before copying and before restoring. JourneyMap's shutdown hook and flushes overwrite files that change under a running game. It's manual, so it'll happen rarely. Per-tile priority can keep an old view of a base that has since changed. |
| Bandwidth | A 46 MB zip per player per round. |
| Who needs it | Only the person running the merge. No mods. |

## 4. Recommendation

Ranked for a handful of friends on one Akliz server, where everyone already runs GTNH 2.9.0 with JourneyMap 5.2.23:

1. **Evaluate JourneySync.** It does most of 3.1 already, under MIT, for this exact pack family. Before anyone installs it, read `ClientSyncManager`, `ServerSyncManager` and `JmBridge` in full. Build it yourself from a reviewed commit, and test it on the local 2.9.0 server copy (`gtnh-local-server-2.9.0`, 127.0.0.1:25568) with two clients. If it works, the gaps are small patches you can send upstream: `topo` in `TileKey`, per-chunk timestamps in `PixelMerge`, and applying downloads through JourneyMap's lock or the stop, merge, restart sequence.
2. **If JourneySync fails review or testing, build 3.1 with 3.3,** as a new repo next to chunk-overlay, reusing its networking, config and test scripts. Ship shared waypoints first because they're small and low risk, then tiles. Add 3.2 later if the stop-and-restart hitch bothers anyone.
3. **Offline merge tool (3.6)** as a stopgap or fallback. No mod risk, but manual.
4. **GTNH-Web-Map (3.5)** if a browser overview is wanted as well. It doesn't replace JourneyMap sharing.
5. **Navigator layer (3.4)** last. One layer at a time makes it compete with VisualProspecting.

### First build step

Use a 1-day test to decide between 1 and 2, because both depend on the same unknown: how JourneyMap 5.2.23 behaves when tiles change under it.

1. Build JourneySync 2.0.0 from source. Put it in the server copy (`scripts\pack-server.ps1 -Setup`) and in two test clients. The local server copy needs `online-mode=false` so a second offline account can join. The `-Pjoin` dev client from chunk-overlay works as the second client, with a different username.
2. Client A explores new chunks, and client B should see them without relogging. Then check the four cases that matter: B is mapping inside the same region when A's tile arrives, B logs in for the first time (46 MB), A and B both re-map the same chunk, and a `topo` tile.
3. Separately, in chunk-overlay's dev client (no networking), check the stop, merge, restart sequence: `stopMapping()`, write a changed PNG, wait a tick, and confirm the map shows it and the file isn't overwritten. That decides how option 2 applies downloads if JourneySync doesn't hold up.

## Sources

- JourneyMap 5.2.x source, license and releases: [TeamJM/journeymap-legacy](https://github.com/TeamJM/journeymap-legacy), [LICENSE](https://github.com/TeamJM/journeymap-legacy/blob/master/LICENSE), [releases](https://github.com/TeamJM/journeymap-legacy/releases). Code cited from `journeymap-1.7.10-5.2.23-unlimited-sources.jar`: `model/RegionImageCache`, `model/ImageHolder`, `model/ImageSet`, `model/RegionImageSet`, `data/DataCache`, `JourneymapClient`, `task/main/MappingMonitorTask`, `io/FileHandler`, `waypoint/WaypointStore`, `model/Waypoint`, `feature/FeatureManager`.
- JourneyMap 6 and its docs: [licensing](https://teamjm.github.io/journeymap-docs/6.0.x/about/licensing/), [server multiplayer](https://teamjm.github.io/journeymap-docs/6.0.x/server/multiplayer/), [waypoints](https://teamjm.github.io/journeymap-docs/6.0.x/client/waypoints/), [mapping a multiplayer server](https://teamjm.github.io/journeymap-docs/6.0.x/tools/multiplayer-server/), [CurseForge 1.7.10-6.0.2](https://www.curseforge.com/minecraft/mc-mods/journeymap/files/8658232), [TeamJM/journeymap-api](https://github.com/TeamJM/journeymap-api) (branch `1.7.10_2.0.0`).
- GTNH mods: [Navigator](https://github.com/GTNewHorizons/Navigator) (LGPL-3.0; `JMWaypointManager`, `JourneyMapVersion`, [PR #20](https://github.com/GTNewHorizons/Navigator/pull/20)), [VisualProspecting](https://github.com/GTNewHorizons/VisualProspecting) (MIT; `ProspectionSharing`, Team Sharing), [GTNH-Web-Map](https://github.com/GTNewHorizons/GTNH-Web-Map) (Apache-2.0).
- Other projects: [donkey-tt/JourneySync](https://github.com/donkey-tt/JourneySync) (MIT; read from the 2.0.0 sources jar), [CivPlatform/map-sync](https://github.com/CivPlatform/map-sync) (GPL-3.0), [JourneyMap Shared Maps listing](https://www.modpackindex.com/mod/170631/journeymap-shared-maps), [SharedAtlasJM listing](https://www.modpackindex.com/mod/158191/sharedatlasjm), [JMWS on CurseForge](https://www.curseforge.com/minecraft/mc-mods/journeymap-waypoint-syncing), [mysticdrew/journeymap-bukkit](https://github.com/mysticdrew/journeymap-bukkit).
- Akliz: [Install Dynmap](https://help.akliz.net/docs/install-dynmap-a-web-based-dynamic-map).
- Local files read: the jars and map folder listed in 1.1 and section 3, the 2.9.0 server backup, and chunk-overlay's README, `Network`, `SnapshotPartMessage`, `Payloads`, `ChunkLayer` and `dependencies.gradle`.
