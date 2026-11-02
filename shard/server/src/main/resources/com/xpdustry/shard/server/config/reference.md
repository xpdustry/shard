# Shard config reference

Generated from `ConfigKeys` by `ConfigSchema`; do not edit. Precedence, low to high: defaults, `shard.yml`,
`SHARD__<SECTION>__<KEY>`, `worlds/<id>.yml`, `SHARD__WORLDS__<ID>__<SECTION>__<KEY>`. World layers may only set
world-scoped keys. A `live` key applies on `config reload`; a `restart` key is reported as pending until a restart.

## server

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `server.name` | a string of at most 100 characters | `"Server"` | host | live | The server name shown in the client's server list. |
| `server.description` | a string of at most 100 characters | `""` | host | live | The line under the name in the server list; empty for none (vanilla wrote "off"). |
| `server.motd` | a string of at most 1024 characters | `""` | world | live | The message sent to a player when they join; empty for none (vanilla wrote "off"). |

## net

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `net.bind` | an IP address literal | `127.0.0.1` | host | restart | The game listener's address. The dev box binds 127.0.0.1 only; a VM or host may bind any address. |
| `net.port` | an integer in [0, 65535] | `6567` | host | restart | The game port, TCP and UDP; 0 picks a free one (tests). |
| `net.player-limit` | an integer >= 0 | `30` | host | live | The most players on the host at once; 0 for no limit. Admins may join past it. |
| `net.allow-custom-clients` | true or false | `false` | host | live | Whether clients that report a modified build may join. |
| `net.packet-spam-limit` | an integer >= 1 | `300` | host | live | Packets a connection may send in 3 s before it is blacklisted and kicked. |
| `net.chat-spam-limit` | an integer >= 1 | `20` | host | live | Chat packets a connection may send in 2 s before it is blacklisted and kicked. |
| `net.snapshot-interval` | a duration from 16ms to 5s (like 250ms, 30s, 10m, 3h, 7d) | `200ms` | world | live | How often entity snapshots go to clients. |

## host

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `host.worlds` | a list of at most 1024 strings | `[]` | host | restart | The worlds hosted at startup, one entry each: "[<id>=]<map> [<mode>]". The map is a name from maps.vanilla-jar or maps.directory (its file name, or its name tag with _ for spaces, any case); the id defaults to the file name in lower case; the mode is survival, sandbox, attack, pvp or editor (default survival). Empty: nothing until the host console command. |
| `host.join` | one of fewest, first, round-robin | `fewest` | host | live | The world a new player joins: fewest (the running world with the fewest players), first (the first running world in host.worlds order) or round-robin. |
| `host.world-player-limit` | an integer >= 0 | `0` | world | live | The most players in one world at once, counting transfers on their way in; 0 for no limit. New in Shard. |

## maps

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `maps.vanilla-jar` | a string of at most 1024 characters | `""` | host | restart | A vanilla v160.7 server or desktop jar, whose 18 default maps (maps/default/*.msav) may then be hosted. Empty for none. |
| `maps.directory` | a string of at most 1024 characters | `"maps"` | host | restart | A directory of custom .msav maps, relative to the working directory (vanilla's config/maps). It may be missing. |

## intents

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `intents.inbox-capacity` | an integer in [64, 1048576] | `4096` | host | live | Inputs a world's inbox holds between two ticks. Worlds created after a reload read it. New in Shard. |
| `intents.mailbox-bound` | an integer in [1, 65536] | `64` | host | live | Reliable intents one session may have waiting for its world; past it the session is kicked, since reliable input is never dropped. Worlds created after a reload read it. New in Shard. |
| `intents.mailbox-quantum` | an integer in [1, 65536] | `16` | host | live | Entries one session's mailbox moves into its world's inbox per turn of the round-robin pump. New in Shard. |
| `intents.inbox-reserve` | an integer in [0, 1048576] | `512` | host | live | Inbox slots intents never take, kept for joins, leaves and other sources: an eighth of the default capacity. It must stay below intents.inbox-capacity. New in Shard. |

## admin

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `admin.whitelist` | true or false | `false` | host | live | Whether only whitelisted players may join. |
| `admin.uuid-change-limit` | an integer >= 0 | `10` | host | live | Distinct UUIDs one address may present within admin.uuid-change-period before the address is banned; 0 or 1 disables the check. |
| `admin.uuid-change-period` | a duration from 1m to 30d (like 250ms, 30s, 10m, 3h, 7d) | `3h` | host | live | The window of admin.uuid-change-limit. |
| `admin.kick-cooldown` | a duration from 0s to 1d (like 250ms, 30s, 10m, 3h, 7d) | `30s` | host | live | How long a kicked, banned or vote-kicked player must wait to rejoin (vanilla hard-codes 30 s). |

## chat

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `chat.anti-spam` | true or false | `true` | host | live | Whether chat and block interaction are rate-limited, and repeat offenders kicked. |
| `chat.message-rate-limit` | a duration from 0s to 1h (like 250ms, 30s, 10m, 3h, 7d) | `0s` | host | live | The least time between two messages from one player; 0 to disable. |
| `chat.message-spam-kick` | an integer >= 0 | `3` | host | live | Messages inside the rate limit before the player is kicked; 0 to disable. |
| `chat.show-connect-messages` | true or false | `true` | world | live | Whether joins and leaves are announced in chat. |
| `chat.log-commands` | true or false | `true` | host | live | Whether player commands are logged. |
| `chat.retention` | a duration from 0s to 3650d (like 250ms, 30s, 10m, 3h, 7d) | `30d` | host | live | How long the chat log keeps messages; 0 to keep none. New in Shard. |

## game

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `game.strict` | true or false | `true` | world | live | Whether player positions are corrected and duplicate names or ids are refused. |
| `game.votekick` | true or false | `true` | world | live | Whether players may vote to kick. |
| `game.interact-rate-window` | a duration from 1s to 1h (like 250ms, 30s, 10m, 3h, 7d) | `6s` | world | live | The window of the block interaction rate limit. |
| `game.interact-rate-limit` | an integer >= 1 | `25` | world | live | Block interactions allowed per window. |
| `game.interact-rate-kick` | an integer >= 1 | `60` | world | live | Block interactions inside one window that get the player kicked. |
| `game.round-extra-time` | a duration from 0s to 10m (like 250ms, 30s, 10m, 3h, 7d) | `12s` | world | live | The wait between game over and the next map. |
| `game.shuffle` | one of none, all, custom, builtin | `custom` | world | live | How a world picks its next map after a game over (vanilla's shufflemode): none ends the game by kicking its players and stopping the world; all, custom and builtin pick at random among game.maps of that kind (custom falls back to the default maps when there is no custom one), never the map just played unless it is the only one. |
| `game.maps` | a list of at most 1024 strings | `[]` | world | live | A world's map registry: the maps its rotation picks from, by name as in host.worlds. Empty: every map the server can host (vanilla's Maps.all()). New in Shard. |
| `game.simulate-when-empty` | true or false | `false` | world | live | Whether an empty world keeps simulating at 60 Hz. By default its time freezes (ARCHITECTURE.md §7); vanilla's autoPause=false is true here. |
| `game.auto-pause` | true or false | `false` | world | live | Whether a world pauses its game while nobody is in it, as vanilla's autoPause: players see it paused, and the first one in resumes it, unless an admin paused it by hand. |
| `game.universe-seconds` | an integer >= 0 | `0` | host | restart | The universe's age in seconds that state snapshots send (vanilla's universe.seconds(), saved as utimei); outside a campaign nothing advances it. |

## persistence

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `persistence.autosave-interval` | a duration from 5s to 1d (like 250ms, 30s, 10m, 3h, 7d) | `1m` | world | live | How often a running world writes a checkpoint. Always on (vanilla: autosave=false, every 300 s). |
| `persistence.directory` | a string of at most 1024 characters | `"data/worlds"` | host | restart | Where world checkpoints and crash bundles go, one directory per world id, relative to the working directory. |
| `persistence.saves-directory` | a string of at most 1024 characters | `"data/saves"` | host | restart | Where the console's save and load keep their slots, as <slot>.msav vanilla saves, relative to the working directory (vanilla: config/saves). |
| `persistence.keep-recent` | an integer in [1, 1000] | `3` | world | live | Checkpoint generations kept per world. |
| `persistence.hibernate-after` | a duration from 0s to 30d (like 250ms, 30s, 10m, 3h, 7d) | `10m` | world | live | How long a world stays empty before it hibernates; 0 never hibernates. New in Shard. |
| `persistence.match-checkpoints` | a duration from 0s to 1h (like 250ms, 30s, 10m, 3h, 7d) | `0s` | world | live | How often a world also writes a checkpoint it keeps for the whole match, for replays; 0 writes none. Rounded up to a multiple of 10 s (600 ticks, the replay keyframe). New in Shard. |
| `persistence.resume` | true or false | `false` | host | restart | Whether a world hosted again after a restart, from the same map and mode, goes on from its last checkpoint instead of starting over: the match continues. A match that ended (a game over's reload, a stop) never resumes. New in Shard. |

## replay

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `replay.record` | true or false | `false` | world | live | Whether a world records its matches: a view stream to watch in a browser and a replay bundle (match checkpoints every 10 s, the inputs, the hashes). Turns persistence.match-checkpoints on at 10 s if it is off. Reloads live: a world starts recording at its next match checkpoint, in the match under way, and stops (closing the replay) when turned off. New in Shard. |
| `replay.directory` | a string of at most 1024 characters | `"data/replays"` | host | restart | Where replays go, relative to the working directory: bundles/<build>/<match>/ and views/<match>.sview, plus views/<match>/ split for static hosting. Serving them is the web host's job. |
| `replay.keep-bundles` | one of build, forever | `build` | host | restart | How long replay bundles stay. build: while the Shard build that recorded them runs; a later build deletes them once their view stream is baked (a bundle only replays on its own build). forever: never deleted. |
| `replay.keep-views` | a duration from 0s to 36500d (like 250ms, 30s, 10m, 3h, 7d) | `0s` | host | restart | How long published view streams (.sview) stay; 0 keeps them until someone deletes them. |
| `replay.pvp-views` | one of after-match, live | `after-match` | world | live | When a PvP match's view stream is published for viewing. after-match: only once the match ends, so nobody watches the other team live. live: segment by segment, like other matches. |
| `replay.in-game` | one of none | `none` | host | restart | In-game replays, watched with the vanilla client in a replay world (note 37 P4). none: browsers only; v1 has no other choice. |

## storage

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `storage.database` | a string of at most 1024 characters | `"data/shard.db"` | host | restart | The SQLite database file, relative to the working directory. It must be on a local file system (WAL). |
| `storage.synchronous` | one of full, normal | `full` | host | restart | SQLite's synchronous mode. full syncs every commit; normal may lose the last commits on power loss. |
| `storage.writer-queue` | an integer in [16, 1048576] | `4096` | host | restart | Writes that may wait for the writer thread; past it, writes fail fast. |
| `storage.readers` | an integer in [1, 64] | `4` | host | restart | Reader connections; reads past it wait their turn. |
| `storage.busy-timeout` | a duration from 0s to 1m (like 250ms, 30s, 10m, 3h, 7d) | `5s` | host | restart | How long a connection waits on a lock before it fails. |
| `storage.backup-dir` | a string of at most 1024 characters | `"data/backups"` | host | live | Where online backups go, including the one taken before a migration. |

## ops

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `ops.enabled` | true or false | `true` | host | restart | Whether the REST/SSE ops API runs (metrics, health, events). |
| `ops.bind` | a loopback IP address (127.0.0.1 or ::1) | `127.0.0.1` | host | restart | The ops API's address: loopback only. Reach it remotely through an authenticated TLS reverse proxy. |
| `ops.port` | an integer in [1, 65535] | `7810` | host | restart | The ops API's port. |

## log

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `log.level` | one of trace, debug, info, warn, error | `info` | host | live | The root log level (vanilla: debug=true is debug). |
| `log.format` | one of json, text | `json` | host | restart | json: one JSON object per line; text: a human pattern. |
| `log.file` | a string of at most 1024 characters | `""` | host | restart | A log file, rolled by size, besides stdout; empty for stdout only. |
| `log.max-file-size` | a size from 64KiB to 1024GiB (like 512KiB, 5MiB, 1GiB) | `5MiB` | host | restart | The size at which log.file rolls over. |

## console

| Key | Type | Default | Scope | Reload | Description |
|---|---|---|---|---|---|
| `console.start-commands` | a list of at most 64 strings | `[]` | host | restart | Console commands run once at startup, in order. |

## Vanilla settings

Every `Administration.Config` key of v160.7 (`Administration.java:534`), plus `playerlimit` and `shufflemode`.

| Vanilla | Shard | Note |
|---|---|---|
| `name` | `server.name` | stored as servername |
| `desc` | `server.description` | "off" becomes empty |
| `port` | `net.port` |  |
| `autoUpdate` | dropped | Shard never updates itself; deploy a new build instead |
| `showConnectMessages` | `chat.show-connect-messages` | per world |
| `enableVotekick` | `game.votekick` | per world |
| `startCommands` | `console.start-commands` | a YAML list, not a comma-separated string |
| `logging` | `log.file` | a file is written when log.file is set |
| `strict` | `game.strict` | per world |
| `antiSpam` | `chat.anti-spam` |  |
| `interactRateWindow` | `game.interact-rate-window` | a duration (vanilla: seconds); per world |
| `interactRateLimit` | `game.interact-rate-limit` | per world |
| `interactRateKick` | `game.interact-rate-kick` | per world |
| `messageRateLimit` | `chat.message-rate-limit` | a duration (vanilla: seconds) |
| `messageSpamKick` | `chat.message-spam-kick` |  |
| `packetSpamLimit` | `net.packet-spam-limit` |  |
| `uuidChangeLimit` | `admin.uuid-change-limit` |  |
| `uuidChangeTimePeriod` | `admin.uuid-change-period` | a duration (vanilla: hours) |
| `chatSpamLimit` | `net.chat-spam-limit` |  |
| `socketInput` | `ops.enabled` | the raw TCP console is gone; the loopback REST/SSE API replaces it |
| `socketInputPort` | `ops.port` | the ops API's port |
| `socketInputAddress` | `ops.bind` | loopback only |
| `allowCustomClients` | `net.allow-custom-clients` | stored as allow-custom |
| `whitelist` | `admin.whitelist` |  |
| `motd` | `server.motd` | "off" becomes empty; per world |
| `autosave` | dropped | checkpoints are always on; see persistence.autosave-interval |
| `autosaveAmount` | `persistence.keep-recent` | checkpoint generations, per world (vanilla: 10 shared files) |
| `autosaveSpacing` | `persistence.autosave-interval` | a duration, default 60 s (vanilla: 300 s); per world |
| `debug` | `log.level` | debug=true is log.level: debug |
| `snapshotInterval` | `net.snapshot-interval` | a duration (vanilla: ms); per world |
| `autoPause` | `game.auto-pause` | per world; apart from it, an empty world's time freezes unless game.simulate-when-empty |
| `roundExtraTime` | `game.round-extra-time` | a duration (vanilla: seconds); per world |
| `maxLogLength` | `log.max-file-size` | a size (vanilla: bytes) |
| `logCommands` | `chat.log-commands` |  |
| `playerlimit` | `net.player-limit` | ServerControl's playerlimit command; 0 is no limit |
| `utimei` | `game.universe-seconds` | universe.seconds(), which state snapshots send; read at startup |
| `shufflemode` | `game.shuffle` | per world, over the world's game.maps |
