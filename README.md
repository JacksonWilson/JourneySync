# JourneySync (cell-sync fork)

A Forge 1.7.10 mod for GT New Horizons that shares JourneyMap maps and waypoints between every player on a server. Each chunk anyone's JourneyMap draws is sent to the server, merged into one shared map, and drawn into everyone else's JourneyMap, on the fullscreen map and the minimap, within a few seconds.

This is a fork of [donkey-tt/JourneySync](https://github.com/donkey-tt/JourneySync) (MIT). Upstream syncs whole 512×512 tile files by watching JourneyMap's folder. This fork replaces that with chunk-level sync through Mixins into JourneyMap 5. The reason: JourneyMap keeps region images in memory for hours and writes them back whole, so with upstream, two players in the same region keep overwriting each other's newest pixels. [docs/cell-sync.md](docs/cell-sync.md) has the design and the test that showed it. Waypoint sharing is upstream's, unchanged. The protocol version is 3, so this fork and upstream don't talk to each other.

Requires JourneyMap 5.2.x for 1.7.10 (GTNH 2.9.0 ships 5.2.23) and UniMixins, which GTNH includes.

## Install

Put the jar in the `mods` folder of the server and of every player who wants the shared map. The mod is optional on both sides:

- Players without it can join a server that has it. They just don't get the shared map.
- A player with it can join a server without it. After 10 seconds without an answer it stops trying, and `/jsync status` says the server doesn't have JourneySync.
- Different builds can play together. They sync if their protocol versions match; otherwise chat says the versions differ and that player doesn't sync.

The first time a player joins:

1. The server sends them the whole shared map, at up to 1 MB/s by default. The game stays playable while it downloads.
2. Their client uploads the map they already had for this server, once. It only fills places the shared map has never seen, so an old map never overwrites newer data.

After that, a rejoining player only gets the chunks that changed while they were away.

## What players see

Chat says `JourneySync: syncing the shared map` on join, then `shared map up to date (N chunks received)`, and after the first join, `uploaded your existing map (N region files)`.

| Command | Effect |
|---|---|
| `/jsync status` | Sync state, chunks received and sent, how far the one-time upload has got, and the stored revision |
| `/jsync pause`, `/jsync resume` | Stop or restart sending your chunks. Other players' chunks still arrive |
| `/jsync resync` | Download the whole shared map again, for example after deleting your JourneyMap data |

Waypoints in JourneyMap's waypoint folder are shared too. Only the player who created one, or an op, can delete it.

## Config

`config/journeysync.cfg`:

| Section | Key | Default | What it does |
|---|---|---|---|
| general | `uploadEnabled` | `true` | Send the chunks your JourneyMap draws |
| general | `downloadEnabled` | `true` | Draw other players' chunks and their waypoints |
| general | `seedExistingMap` | `true` | Upload your existing map once per server, as fill-only |
| general | `logDebug` | `false` | Verbose logging |
| transfer | `uploadIntervalMillis` | `2000` | How often new chunks are sent |
| transfer | `uploadKBps` | `256` | Upload limit in KB/s (0 = unlimited) |
| server | `acceptUploads` | `true` | Server: merge chunks players send |
| server | `sendKBpsPerPlayer` | `1024` | Server: how fast the map is sent to each player |
| server | `tileCache` | `32` | Server: region tiles kept in memory, about 1 MB each |

## Server data

`<world>/journeysync/`:

- `cells/DIM<d>/<type>/<x>,<z>.png` and `.rev`: the shared map and a revision number per chunk.
- `cells.json`: the shared map's id and revision counter.
- `waypoints.json`: shared waypoints.

Deleting the folder starts a new shared map. Clients notice the new id and start over, uploading their own maps again.

## Building and testing

```powershell
.\dev.ps1 build                         # compile, unit tests, jar in build\libs
.\dev.ps1 spotlessApply                 # GTNH formatting
.\scripts\two-clients.ps1 -Start        # dev server on 127.0.0.1:25570 plus muted clients "Developer" and "Alex"
.\scripts\two-clients.ps1 -Stop
python scripts\compare-maps.py --kind day   # explored pixels per tile: each client and the server
.\scripts\optional-test.ps1             # a client without the mod joins; a client with it joins a server without it
```

`-PwithoutJourneySync` on `runClient` or `runServer` leaves this mod out of that run, for testing mixed setups.

Put `journeymap-1.7.10-5.2.23-unlimited.jar` in `libs/` for dev runs. Without it, the build compiles against Modrinth's JourneyMap 5.2.20. The two-client script drives players over RCON, which needs [gtnh-rcon-fix](../gtnh-rcon-fix) built next to this repo, because vanilla 1.7.10 RCON drops the connection after login. The dev server needs `run/server/server.properties` with `server-ip=127.0.0.1`, `online-mode=false` and `enable-rcon=true`, and an `eula.txt` you accept yourself.

Tested on 2026-11-01 with two dev clients and JourneyMap 5.2.23. Both players were in the same region tile, and one explored new ground there. The other client and the server had every new pixel within 40 seconds, for day, night and topo. A new player who joined later got the whole map, including tiles they had never been near. An existing map was uploaded once, and a shared waypoint appeared for the other player. Not yet tested on the full GTNH pack or on the Akliz server.
