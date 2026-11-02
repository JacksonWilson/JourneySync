# JourneySync

A Forge 1.7.10 mod for GT New Horizons that turns JourneyMap into a team map:

- **One shared map.** Every chunk anyone's JourneyMap draws goes to the server, is merged into one map, and shows up in everyone else's JourneyMap, fullscreen and minimap, within a few seconds.
- **Shared waypoints.** Waypoints are shared with everyone on the server.
- **Chunk loading and claims layer.** A map layer showing which chunks are force-loaded and by what, which are loaded right now, and ServerUtilities claims coloured by team.

It's optional on both sides, needs JourneyMap 5.2.x for 1.7.10 (GTNH 2.9.0 ships 5.2.23), and uses UniMixins and Navigator, which GTNH already has.

It started as a fork of [donkey-tt/JourneySync](https://github.com/donkey-tt/JourneySync) (MIT). The map sync was rebuilt to work per chunk through Mixins into JourneyMap, instead of watching JourneyMap's files. JourneyMap keeps region images in memory for hours and writes them back whole, so with file watching, two players in the same region kept overwriting each other's newest pixels. [docs/cell-sync.md](docs/cell-sync.md) has the design.

## Install

Put the jar in the `mods` folder of the server and of every player who wants the shared map and the layer.

- Players without it can join a server that has it. They just don't get the shared map or the layer.
- A player with it can join a server without it. The server never greets them, so their client sends nothing, and `/jsync status` says the server doesn't have JourneySync. The layer's button says so too.
- Different builds can play together if their protocol versions match. Otherwise chat says the versions differ, and that player doesn't sync.

The first time a player joins:

1. The server sends them the whole shared map, at up to 1 MB/s by default. The game stays playable while it downloads.
2. Their client uploads the map they already had for this server, once. It only fills places the shared map has never seen, so an old map never overwrites newer data.

After that, a rejoining player only gets the chunks that changed while they were away.

## Shared map and waypoints

Chat says `JourneySync: syncing the shared map` on join, then `shared map up to date (N chunks received)`, and after the first join, `uploaded your existing map (N region files)`.

JourneyMap names each server's map folder after its entry in your server list, so renaming the entry starts a new, empty folder for the same world. Before your first upload, JourneySync looks for other folders with the same JourneyMap Server world id and fills your current map from them. It only fills empty pixels, so your current map wins, and it copies waypoints your current folder doesn't have. Chat then says `added your map from N older folder(s) for this world`. Folders without a world id are left alone, because "Minecraft Server" is the default name for every server you add.

| Command | Effect |
|---|---|
| `/jsync status` | Sync state, chunks received and sent, how far the one-time upload has got, and the stored revision |
| `/jsync pause`, `/jsync resume` | Stop or restart sending your chunks. Other players' chunks still arrive |
| `/jsync resync` | Download the whole shared map again, for example after deleting your JourneyMap data |

Waypoints in JourneyMap's waypoint folder are shared too. Only the player who created one, or an op, can delete it.

## Chunk loading and claims layer

Open the fullscreen map (`J`) and click the chunk grid button in the toolbar. The minimap shows the layer too while it's on. For the dimension you're in it shows:

| On the map | Meaning |
|---|---|
| Team colour fill with a thin outline in the same colour | Claimed by that team, and loaded. The outline runs around each block of the team's claims |
| Faint team colour fill | Claimed by that team, not loaded |
| Light grey wash | Loaded, not claimed |
| Orange outline | Around each block of force-loaded chunks |

Hovering a chunk shows its coordinates, the claiming team, whether it's loaded, and every ticket that holds it: the mod, the owner, and what holds it, such as `Chunk Loader at 160, 64, -32` for a GregTech chunk loader.

What each player sees:

- Ops, and players listed in `seeAllPlayers`, see everything in their dimension.
- Everyone else sees their own team's claims, the tickets they or their team own, and whether those chunks are loaded. Other teams' claims, other players' tickets and loaded chunks elsewhere are never sent to them.

The client only asks for data while the layer is on and a map is showing. The server collects each dimension at most once a second however many players ask. One snapshot covers the whole dimension, so panning doesn't send requests.

ServerUtilities 2.4.22 has its own claims layer. Navigator shows one layer at a time, so turning this one on turns that one off.

## Admin command

`/jsyncadmin` is for ops and works from the server console:

- `/jsyncadmin map`: the shared map's id, tile count and revision, and how many players are syncing.
- `/jsyncadmin chunks [dimension]`: what the chunk layer shows an op for a dimension. Loaded, force-loaded and claimed chunk counts, every ticket with its owner and chunk count, claims per team, and how long collecting took. Without a dimension it uses yours, or the overworld from the console.

## Config

`config/journeysync.cfg`:

| Section | Key | Default | What it does |
|---|---|---|---|
| general | `uploadEnabled` | `true` | Send the chunks your JourneyMap draws |
| general | `downloadEnabled` | `true` | Draw other players' chunks and their waypoints |
| general | `seedExistingMap` | `true` | Upload your existing map once per server, as fill-only |
| general | `chunkLayerRefreshSeconds` | `5` | How often the chunk layer asks for fresh data while it's showing |
| general | `logDebug` | `false` | Verbose logging |
| transfer | `uploadIntervalMillis` | `2000` | How often new chunks are sent |
| transfer | `uploadKBps` | `256` | Upload limit in KB/s (0 = unlimited) |
| server | `acceptUploads` | `true` | Merge chunks players send |
| server | `sendKBpsPerPlayer` | `1024` | How fast the map is sent to each player |
| server | `tileCache` | `32` | Region tiles kept in memory, about 1 MB each |
| server | `opsSeeAll` | `true` | Ops see every team's claims, every ticket and every loaded chunk on the layer |
| server | `seeAllPlayers` | empty | Player names or UUIDs that see everything on the layer, like ops. One per line |
| server | `chunkRequestIntervalMillis` | `2000` | Layer requests from one player sooner than this are ignored |

## Server data

`<world>/journeysync/`:

- `cells/DIM<d>/<type>/<x>,<z>.png` and `.rev`: the shared map and a revision number per chunk.
- `cells.json`: the shared map's id and revision counter.
- `waypoints.json`: shared waypoints.

Deleting the folder starts a new shared map. Clients notice the new id and start over, uploading their own maps again.

## How it works

```
 client                                              server
 ──────                                              ──────
                       <── ServerHello (protocol, map id)       on login
 JourneyMap ready ───> Hello (map id, last revision)
                       <── catch-up: cell patches, waypoints, sync mark
 renderChunk (Mixin) ─> cell patches ──────────────> merge into the shared map
                       <── merged cells ────────────  to every other player
 layer showing ──────> ChunkRequest ───────────────> collect, filter for the viewer
                       <── snapshot (gzipped blob) ─
```

Everything uses the `journeysync` channel. Received map cells are drawn into JourneyMap's in-memory region image through a Mixin on `ImageHolder`, under a lock per file, so JourneyMap never overwrites them; details in [docs/cell-sync.md](docs/cell-sync.md).

## Building and testing

All commands run from the repo root in PowerShell. `dev.ps1` runs Gradle at below-normal CPU priority, so builds don't stutter a running game.

```powershell
.\dev.ps1 build                         # compile, unit tests, jar in build\libs
.\dev.ps1 spotlessApply                 # GTNH formatting (the build fails without it)
.\dev.ps1 runServer -Pft                # functional tests inside a headless dev server
.\scripts\smoke.ps1                     # chunk layer smoke test: demo server, client checks the layer, quits
.\scripts\two-clients.ps1 -Start        # dev server on 127.0.0.1:25570 plus muted clients "Developer" and "Alex"
.\scripts\two-clients.ps1 -Stop
python scripts\compare-maps.py --kind day   # explored pixels per tile: each client and the server
.\scripts\optional-test.ps1             # a client without the mod joins; a client with it joins a server without it
.\scripts\pack-test.ps1 -Setup          # full GTNH 2.9.0: local server copy plus a Prism test instance
```

| Layer | What it covers |
|---|---|
| Unit tests (27) | Tile ids, cell merge rules, the cell patch codec, the server's cell store, the chunk snapshot format and blob splitting, chunk outlines, and the layer's visibility rules for ops, team members and ticket owners |
| Functional tests (`runServer -Pft`) | A headless dev server with ServerUtilities: a GregTech-style chunk-loader ticket, the collected snapshot, ticket descriptions, block names, visibility for a non-op fake player, the wire format and `/jsyncadmin chunks`. Every server code path runs on a real dedicated server, so a client-only call fails here instead of on Akliz |
| Layer smoke test (`smoke.ps1`) | A dev client claims and chunk-loads the chunk it stands in, opens the fullscreen map with the layer on, hovers a chunk and reads its tooltip, toggles the layer, and checks the minimap. Report and screenshots in `run\client` |
| Two clients (`two-clients.ps1`) | Live map sync between two players, catch-up for a new player, seeding an existing map, waypoints |
| Mixed setups (`optional-test.ps1`) | A client without JourneySync on a server with it, and the other way round |
| Full pack (`pack-test.ps1`) | The real 2.9.0 server copy and a Prism instance with your account |

`-PwithoutJourneySync` on `runClient` or `runServer` leaves this mod out of that run.

Put `journeymap-1.7.10-5.2.23-unlimited.jar` in `libs/` for dev runs; without it the build compiles against Modrinth's JourneyMap 5.2.20. The two-client script drives players over RCON, which needs [gtnh-rcon-fix](https://github.com/JacksonWilson/gtnh-rcon-fix) built next to this repo, because vanilla 1.7.10 RCON drops the connection after login. The dev server needs `run/server/server.properties` with `server-ip=127.0.0.1`, `online-mode=false` and `enable-rcon=true`, and an `eula.txt` you accept yourself.

Build notes:

- The build template is GTNH's [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10). Code may use Java 17 syntax (Jabel) but only Java 8 library APIs, because the Akliz server runs Java 8.
- Navigator 1.1.10 needs GTNHLib 0.11, so `dependencies.gradle` pins the GTNH 2.9.0 versions, GTNHLib 0.11.54 and GTNHExtLib 1.0.5. Windows Defender quarantines GTNHExtLib 1.0.4.

## Test results

On 2026-11-01, with dev clients and JourneyMap 5.2.23: two players in the same region tile, one exploring new ground there. The other client and the server had every new pixel within 40 seconds, for day, night and topo. A new player who joined later got the whole map, including tiles they had never been near. An existing map was uploaded once, and a shared waypoint appeared for the other player.

Full GTNH 2.9.0 (220 mods, JourneyMap 5.2.23 unlimited, Navigator, VisualProspecting), using a local copy of the real server and a Prism test instance. The Mixins applied cleanly. A real 646-tile map (46 MB, 168,082 chunk cells) was uploaded in 2 min 25 s at the default 256 KB/s, with no server lag warnings. After the client's map was wiped, rejoining downloaded the whole shared map in 81 s, and every tile matched the server: 43,028,480 explored pixels on both. All 109 waypoints reached the server.

After the chunk layer was merged in, the full pack ran again with two players: the user's account and an offline Prism account, both on the server copy. The first player's 646-tile map was uploaded in 2 min 26 s. The second player had no map, and catch-up sent them 137,773 chunks, with the rest arriving live. Both clients and the server then held the same 43,038,720 explored pixels across every tile and map type. Both players were then put in creative and teleported 350 blocks apart in one region tile on new ground; both clients and the server had all 241,920 new pixels within 60 seconds. `/jsyncadmin chunks` reported 1,004 loaded, 176 force-loaded and 184 claimed chunks in the overworld, collected in 8.5 ms.

The chunk layer was tested before the merge, with JourneyMap 5.2.20, 5.2.21 and 5.2.23, and against the full 2.9.0 server copy (797 loaded, 176 force-loaded and 184 claimed chunks in the overworld, collected in about 1.5 ms).
