# rig: stock vanilla clients against a server, on boat (L5)

TL;DR: one command creates (or reuses) a boat VM, starts a server and N unmodified v160.7 desktop clients under
Xvfb, plays a `.rig` script with real X11 keys and clicks, then pulls back screenshots, logs and a verdict. This
is the L5 gate (`ARCHITECTURE.md` §6) for every milestone. It is never a runtime dependency.

## Cheat sheet

```sh
rig/run.sh --server 'vanilla serpulo/groundZero' --clients 2 --script rig/scenarios/vanilla-groundzero.rig  # the reference gate
rig/run.sh --push <shard build dir> --server '"$PUSH"/bin/server' --clients 2 --script rig/scenarios/join-walk.rig
rig/run.sh ... --keep           # keep the VM; later runs reuse it until: rig/run.sh --delete-vm
rig/run.sh ... --vm "$(cat .vm-id)"   # run on your scripts/vm-gradle.sh VM, next to your build; never deleted by the rig
rig/run.sh ... --expect fail    # exit 0 when the verdict is fail (negative tests)
java rig/src/com/xpdustry/shard/rig/Script.java my.rig          # syntax check, no VM (run.sh does this first)
# artifacts: ~/.cache/shard/scratch/rig/runs/<time>-<script>/{summary.txt,verdict.json,rig.log,server/,alpha/,...}
# a script line: [alpha:|bravo:|all:] action args   e.g.  alpha: place conveyor tile 133 59 rot 1
# every run ends with: final screenshots, last_log.txt free of errors/desync/kicks, server still alive
# never run vm/run.sh on the dev box: it starts the GUI client
```

## Running

`rig/run.sh` runs on the dev box and does everything else on a VM:

1. Syntax-checks the script locally.
2. VM: `--vm ID` uses that VM and leaves it alone. Otherwise it reuses the VM remembered in `rig/.vm-id` (from an
   earlier `--keep`), or creates `r3-rig-<run>` from `shard-runner` with `--no-env` and deletes it at the end.
3. Pushes `rig/`, the script, and `--push DIR` (a server build) to the VM.
4. Runs `rig/vm/run.sh` there. That script compiles the driver against `Mindustry.jar` when its sources change.
   It then starts `Rig` inside `unshare --user --net --pid --mount-proc`: loopback is the only interface, so the
   clients' community-server fetch fails offline, and every process dies with the run.
5. Pulls the run directory back to `~/.cache/shard/scratch/rig/runs/<run>/` (or `--out`), prints the summary and
   the path, and exits 0 when the verdict matches `--expect` (default `pass`).

| Flag | Meaning |
|---|---|
| `--server CMD` | Shell command that starts the server on the VM. It runs in `<run>/server/`, its stdin is the console (`console ...`), and its output goes to `server/server.log`. The command sees `$PORT`, `$PUSH`, `$VANILLA` (the jar directory), `$JAVA`, `$RIG` and `$RIG_OUT`. |
| `--server '"$RIG"/vm/shard-server.sh [--join POLICY] WORLD...'` | Shard, from `--push` of `server/build/install/shard` (copied out of the checkout first: `boat push` skips ignored paths such as `build/`). It writes a `shard.yml` that hosts the worlds (`host.worlds` entries) on `$PORT` from the vanilla jar's maps. |
| `--server 'vanilla [map...] [-- commands]'` | The vanilla `server-release.jar` (`vm/vanilla-server.sh`). Each `map` is a `.msav` path inside the jar, installed as a custom map. The `commands` run at startup, for example `-- host Ground_Zero`. |
| `--clients N` | Clients `alpha`, `bravo`, `charlie`, ... (up to 8; the default VM runs 2 comfortably). |
| `--script FILE` | The `.rig` script, from any path. |
| `--push DIR` | Copies a local directory to the VM; the server command sees it as `$PUSH`. |
| `--vm ID`, `--keep`, `--delete-vm`, `--type T` | VM handling, as above. `--type` is `small`, `default` or `large`. |
| `--expect pass\|fail`, `--out DIR`, `--port P`, `--size WxH` | Verdict to expect, artifact directory, server port (6567), screen size (1280x720). |
| `--server-cpus L`, `--client-cpus L` | Pins the server's command, and each client's Xvfb, JVM and input, to CPU lists (`taskset -c`, for example `0-3` and `4-7` on a large VM). Two clients booting took a large VM's 8 vCPUs to a run queue of 25 and put an unpinned server 4 ticks behind. |

Already on a VM (for example in a `vm-gradle.sh` shell), run the inner script directly:
`rig/vm/run.sh --server CMD --clients N --script FILE --out DIR`.

### Artifacts

```
<run>/summary.txt        the verdict in a few lines (also printed)
<run>/verdict.json       verdict, first failure, every step (status, seconds, detail), final checks, per-client log problems and screenshot stats
<run>/rig.log            timestamped trace of the driver
<run>/script.rig         the script that ran
<run>/server/            server.log, plus whatever the server writes in its working directory (vanilla: config/)
<run>/<client>/shots/    NN-name.png
<run>/<client>/data/Mindustry/   the client's data directory: last_log.txt, settings.bin, crashes/
<run>/<client>/stdout.log        client stdout: vanilla's log plus the driver's [rig] step markers
```

## How it works

- **`RigClient`** runs inside each vanilla client JVM. It boots `DesktopLauncher` at the screen size, without
  maximising. It answers the rig on a loopback control socket: status, world-to-screen projection, UI element
  positions, tiles, players, chat and dialogs. It only reads state, apart from the client's own UI entry points:
  the join dialog's `connect` and the pause menu's quit. It logs `[rig] disconnected unexpectedly: <dialog text>`
  to `last_log.txt` when the connection drops without the script asking. The text is what the client showed, for
  example a kick reason.
- **`RigInput`** is one long-lived process per display. It injects every key, click, drag and wheel notch as a
  real X11 event (XTEST through `java.awt.Robot`), and takes the screenshots. The client handles them through its
  normal input code: keybinds, the placement menu, chat.
- **Camera maths.** The view is centred on the camera, which follows the player. A world point maps to
  `(wx - cam.x) / cam.width * W + W/2`, where `cam.width = W / renderer.getDisplayScale()`, so zoom matters. Y is
  flipped for X11. Before a pointer action, the rig waits until the camera stops moving and the zoom matches its
  target. After moving the pointer, it reads back what the client has under it: the tile from
  `Core.input.mouseWorld()`, or the hit UI element. A mismatch fails the step. The window offset is calibrated at
  the first join from two probes in a row that agree.
- **`Rig`** parses the script, starts Xvfb, the server and the clients, and runs the steps. Between steps and every
  250 ms while it waits, it checks health: an unexpected server exit, a client process exit, an unexpected
  disconnect, or the run timeout. The first failed step stops the script. The remaining steps are recorded as
  skipped, and the final checks still run.

## Script reference

One statement per line: `[target:] action args...`. The target is a client name, or `all` to run the action on
each client in turn. It is optional when there is only one client. `#` starts a comment.

Tokens are words, `"quoted strings"`, `/regex/` (Java syntax; write `\/` for a slash) and `!/regex/` (no match).
`console` and `exec` take the rest of the line as is.

**Durations:** `500ms`, `2s`, `1m` and `120t`. Ticks are 1/60 s of wall time, except in `<client>: wait 120t`,
which waits for that client's own simulation to advance 120 ticks (`state.tick`).

**Positions:** `tile X Y` (tile centre), `world X Y` (world units, 8 per tile), `screen X Y` (X11 pixels),
`ui NAME` (a named UI element: `block-<block>` and `category-<category>` in the placement menu, `blockinfo`...) and
`player` (where the client's own player stands when the pointer moves there: `drag left player tile 58 33` drops the
unit's items on a block, which only starts within 11 units of the unit).

### Directives

| Line | Meaning |
|---|---|
| `timeout 6m` | Budget for the whole run (default 10m). |
| `allow client-log /re/` | Excuse matching client log entries from the clean check. Startup noise is already excused: GL version probing, audio, the offline community-server fetch. |
| `allow server-log /re/` | The same, for `assert clean server`. |

### Global actions

| Action | What it does |
|---|---|
| `wait 2s` | Sleeps, while still checking health. |
| `wait port [P] [60s]` | Waits until the server accepts TCP connections. Use it for servers that host on their own. |
| `wait log SRC /re/ [30s]` | Waits for a matching line in SRC: `server`, or a client's `last_log.txt`. It matches from a cursor, so two waits need two occurrences. |
| `console LINE` | Writes LINE to the server's stdin. For vanilla, `js <expr>` prints any server-side state. |
| `server start`, `server stop` | Starts the server, or stops it on purpose (SIGTERM to its tree, then SIGKILL after 10 s). The clients are told that their next disconnect is planned. |
| `server kill` | SIGKILL with no warning: a crash, so the run fails. `server kill expected` makes it planned. |
| `exec CMD` | `bash -c CMD` in the run directory, with `$RIG_SERVER_PID` and the server variables. A non-zero exit fails the step. |
| `assert log SRC /re/` or `!/re/` `[after /re2/]` | A matching line exists, or doesn't, anywhere in SRC's log, or only after the first line matching `re2`. |
| `assert clean [SRC\|all\|server]` | No errors (`[E]` entries, exception text), desync lines or kick/disconnect lines in that log. `all` (the default) checks every client. |

### Client actions

| Action | What it does |
|---|---|
| `join [host[:port]] [nounit]` | Waits for the client to load, clicks away leftover dialogs, connects through the join dialog's `connect` (the same call as typing the address) and waits for the client to spawn with a unit (120 s). With `nounit`, the loaded world is enough: Shard spawns no player units until WP23. |
| `disconnect` / `reconnect [nounit]` / `rejoin [nounit]` | Quit through the pause menu's code path; join again; or both, one second apart. |
| `dismiss` | Clicks the first button of each open dialog, such as OK on "Connection closed". |
| `drop` | The client's next disconnect is planned: say it before an action that gets it kicked. |
| `refused [/re/] [30s]` | Connects, expecting the server to turn the client away (a ban, a kick cooldown), and waits for a dialog matching `re`. The drop is planned. |
| `hold KEYS DUR` | Holds keys together: `hold W 500ms`, `hold W+D 1s`. Key names are `java.awt.event.KeyEvent` `VK_` names: `W`, `ENTER`, `ESCAPE`, `SHIFT`, `F1`, `COMMA`, `UP`... `MOUSE_LEFT`, `MOUSE_RIGHT` and `MOUSE_MIDDLE` hold a button where the pointer is: `hold CONTROL+MOUSE_LEFT 200ms` is a ctrl-click, `hold MOUSE_LEFT 3s` holds the trigger. |
| `press KEY...` | Taps each key in turn (50 ms down, 100 ms apart). |
| `hotbar D...` | Vanilla's number keys: the first digit picks a category (1 = turret, 2 = production, 3 = distribution...), the next picks a block. `hotbar 3 1` gives the conveyor. |
| `type "text"` | Types characters with a US layout, using shift for capitals and symbols. |
| `chat "text"` | Enter, type, Enter, checking that the chat field opened and closed. Slash commands work the same way. |
| `scroll N` | Wheel notches. Positive zooms out, or rotates the selected block. |
| `zoom SCALE` | Scrolls until the camera's target scale is within 10% (vanilla starts at 4). It fails while a block is selected (the wheel would rotate it). |
| `move POS`, `click [left\|right\|middle] POS`, `drag [button] POS POS` | Pointer actions, each checked against what the client sees under the pointer. A left drag with a block selected places a line; a right drag with nothing selected deconstructs; a right click drops the selection. |
| `select BLOCK` | Clicks the block's category tab, then its button in the placement menu, and checks that it is selected. |
| `rotate R` | Scrolls until the placement rotation is R (0 = right, 1 = up, 2 = left, 3 = down). |
| `place BLOCK POS [rot R]` | `select`, `rotate` and a click. It checks that the client queued a plan, or shows the block or its construction site. |
| `shot NAME [any]` | Screenshot to `shots/NN-NAME.png`. A black or single-colour frame fails the step, unless `any` is given. |
| `mark` | Remembers the player's position. |
| `expect moved N` | At least N tiles from the mark. |
| `expect tile X Y BLOCK [30s]` | The client's world shows BLOCK there (`air`, `conveyor`, `constructing:conveyor`...). |
| `expect player NAME [within N] [10s]` | This client sees player NAME; with `within`, the position it sees is within N tiles of where NAME's own client is. This is the cross-client sync check. |
| `expect chat /re/ [10s]` | A message in this client's chat panel matches. The client doesn't log received chat. |
| `expect dialog /re/ [10s]` | The open dialogs' titles and labels, color tags stripped, match (a kick's reason, a trace). |
| `expect connected\|disconnected [10s]`, `expect selected BLOCK` | Connection state, and the selected block (`none` for no selection). |
| `expect js "EXPR" /re/ [10s]` | What vanilla's script console prints for EXPR (`Vars.mods.getScripts().runConsole`, on the app thread) matches. Any client state, no new rig code: `expect js "Vars.state.wave" /^3$/`, `expect js "Vars.state.rules.defaultTeam.core().items.get(Items.copper)" /^[1-9]/`. A script error prints as its message (`EcmaError: ...`), so it fails the match rather than hanging. |
| `sample js "EXPR" every DUR for DUR to FILE` | Probes EXPR at that rate for that long and writes `<ms>\t<value>` lines to FILE in the client's directory. The step takes the whole duration. |
| `wait DUR`, `assert ...` | As the global actions; `alpha: wait 60t` counts alpha's simulation ticks. |

### The end of every run

Even after a failure, the rig:
1. takes a `final` screenshot per client;
2. checks every client's `last_log.txt` for errors, desync lines (`Block ID mismatch`, `Missing entity`, `Error
   reading entity snapshot`, `desync`...) and kick or unexpected-disconnect lines;
3. checks that the server is still running, unless the script stopped it.

Any of these fails the verdict.

## Scenarios

| Script | Server | Expect | What it shows |
|---|---|---|---|
| `vanilla-groundzero.rig` | `vanilla serpulo/groundZero` | pass | **The reference gate**, 2 clients. Join, walk, see each other, chat, `/help`, place a conveyor through the menu, confirm it with `js` on the server console, rejoin and see it in the new world stream. |
| `server-killed.rig` | `vanilla serpulo/groundZero` | fail | SIGKILL of the server from outside the rig mid-run. It must fail on the dead server and the clients' drops. |
| `blank-shot.rig` | `vanilla ... -- host Ground_Zero` | fail | A screenshot of the black display before the client window opens must fail. |
| `actions.rig` | `vanilla ... -- host Ground_Zero` | pass | Rig self-test: every action, with 1 client. Zoom, hotbar, line and break drags, keys, typing, quit and reconnect, server stop and start. |
| `join-walk.rig` | any that hosts on its own | pass | The server-agnostic smoke test, 2 clients. It needs player units, so Shard passes it from WP23. |
| `shard-two-clients.rig` | `vm/shard-server.sh maze fortress` | pass | W2.4's L5, 2 clients on Shard: join maze, chat and `/help`, a `/world` transfer to fortress and back, a leave and rejoin, a minute idle. |
| `shard-maps.rig` | `vm/shard-server.sh --join round-robin <the 18 maps>` | pass | W2.4's L5 over the 18 default maps: round-robin joins give each client every other map; each join loads its world and idles 60 s. |
| `shard-loads.rig` | `SHARD_OPTS=-Dshard.debug.stream-delay=maze=8s vm/shard-server.sh maze fortress`, 1 client | pass | W2.4h: a `/sync` still deflating when a transfer loads fortress never reaches the client. The client counts its loads with `js` and reads its map; each world it is sent follows the one `worldDataBegin` it handled. |
| `intent-walk.rig` | any that hosts on its own, under `tcpdump` | pass | WP26's walk, 2 clients: each walks a square and sees the other where its own client is. It sends only `clientSnapshot`, so any `setPosition` in the capture is a server's rubber-band; W2.14 counted 0 on Shard and on vanilla. Needs player units and entity sync. |
| `shard-intents.rig` | `vm/shard-server.sh maze` | pass | W2.14 (WP26) on Shard, 2 clients: the walk, then admission's account (`console input`: every snapshot within a tile, no corrections); a deny rule takes alpha's conveyor back off its queue with the rule's message, and it stays queued once the rule is gone. Needs entity sync. |
| `shard-payloads.rig` | `vm/shard-server.sh "payloads sandbox"` on `payloads.msav` (`PayloadsMap.java`) | pass | W3.9 (WP65) on Shard, 2 clients: alpha takes a mega over and, with vanilla's `[`/`]` keys, lifts a dagger and sets it down, lifts a container of copper and drops it elsewhere; a `dropPayload` deny rule refuses a drop with its message. bravo sees every change. |
| `shard-factory.rig` | `vm/shard-server.sh "factory sandbox"`, or `vm/vanilla-server.sh -- host factory sandbox` without the console steps (factory.msav, `FactoryMap.java`) | pass | W4.1 (WP66), 2 clients: alpha opens an air factory's config, picks mono and the move command (`tileConfig`); both clients see both, the made mono carries the command, `null` clears it, and bravo's rejoin streams the plan. Evidence in `evidence/w4.1/`. |
| `shard-commands.rig` | `vm/shard-server.sh "commands sandbox"` on `commands.msav` (`CommandsMap.java`), or `vm/vanilla-server.sh -- host commands sandbox` | pass | W4.2 (WP72), 2 clients, one script for both servers: alpha toggles command mode, box-selects four daggers and two flares with a drag, right-clicks east of a wall; bravo sees all six arrive. Then hold fire on a fresh selection, as the stance button sends it (a script-rebound key never fired, on either server). Evidence in `evidence/w4.2/`. |
| `reconnect-stress.rig` | any that hosts on its own | pass | 2 clients rejoin 30 times each, idling 10 s after each round. W2.4's reconnect control: it waits on no server log, so it runs the same against vanilla and Shard. |
| `shard-build.rig` | `vm/shard-server.sh routers` (sync-routers.msav) | pass | W2.15's L5 for construction, 2 clients: alpha breaks ten routers for copper, builds a conveyor line and a router, breaks the line's middle; bravo sees each block come and go, no site or rebuild plan is left, and a rejoin streams the same blocks. Evidence in `evidence/w2.15/`. |
| `shard-config.rig` | `vm/shard-server.sh "routers sandbox"` (sync-routers.msav) | pass | Review 39's blocker, 2 clients: alpha builds power nodes, a processor and a switch, and links and unlinks them by tapping; bravo's links and power graphs match alpha's after each toggle, and a rejoin's world stream shows the same. Evidence in `evidence/w2.20/`. |
| `admin.rig` | `vm/shard-server.sh --aot fortress`, or `vanilla -- host Fortress` | pass | W3.13, 2 clients, one script for both servers: `admin add`, then the admin's trace, wave skip, kick (refused for 30 s, then back) and ban of the other client, `bans` and `unban` on the console, and a kick ignored once `admin remove` ran. Evidence in `evidence/w3.13/`. |
| `game-flow.rig` | `vm/shard-server.sh --aot fortress`, or `vanilla -- host Fortress` | pass | W3.8, 3 clients, one script for both servers: chat, `/t` and `/a`; a votekick that fails after 30 s (one address); `nextmap Maze`, `gameover`, the game-over dialog, and every client moved onto Maze 12 s later. Evidence in `evidence/w3.8/`. |
| `shard-mine.rig` | `vm/shard-server.sh mine`, or `vm/vanilla-server.sh -- host mine` (mine.msav) | pass | W3.7's L5 for items and mining, 1 client: alpha mines by the core (each copper straight into it), walks out of its transfer range and mines into its stack, drags the stack onto a container, then onto the core. Every count is read on the client. Evidence in `evidence/w3.7/`. |
| `objectives.rig` | `vm/shard-server.sh objectives`, or `vm/vanilla-server.sh -- host objectives` (objectives.msav) | pass | W3.12's L5 for objectives and markers, 2 clients, one script for both servers: each client sees the map's three objectives and the first one's text, and the markers a world processor made, its printed text included. alpha builds a conveyor; the build-count objective completes, its flag lets the processor set the next one's, and both clients show the third. After a rejoin, bravo's world stream carries the progress and the markers. Evidence in `evidence/w3.12/`. |
| `payload-drop-order.rig` | `vm/shard-server.sh "payloads sandbox"` on `payloads.msav` | pass | W4.5 (Q215), 2 clients: `shard-payloads.rig`'s setup and one container drop; both clients' own tiles must show it, which W3.9 had to leave out. bravo logs whether the `payloadDropped` Call or the snapshot that empties the carrier came first. This branch 20/20, main 19/20. Evidence in `evidence/w4.5/`. |
| `payload-drop-cycles.rig` | as above | pass | W4.5's amplified version: ten lifts and drops per run, both clients counting drops that left their tile empty and logging Calls and carrier-changing snapshots in arrival order. This branch 0 misses in 200 Calls, main 3 in 190, each after `snapshot:1>0`. |
| `shard-fog.rig` | `vm/shard-server.sh "fog pvp"` (fog.msav, `FogMap.java`) | pass | W4.3 (WP73), 2 clients on opposite teams (PvP) of a fog map: each sees its own daggers and only the enemy dagger in its core's sight, before and after a rejoin; the enemy's far dagger and core unit stay out of its snapshots and world stream. Evidence in `evidence/w4.3/`. |
| `shard-turret.rig` | `vm/shard-server.sh "turret sandbox"` on `turret.msav` (`TurretMap.java`), or `vm/vanilla-server.sh -- host turret sandbox` | pass | W5.7 (WP60), 2 clients, one script for both servers: alpha ctrl-clicks a duo and controls it through its block unit, aims at empty ground and fires (no enemy, so only the player turns it); bravo sees it face the aim and spend rounds. Then a ctrl-click on the core respawns alpha there (`buildingControlSelect`). Evidence in `evidence/w5.7/`. |
| `m1.rig` | `vm/shard-server.sh --join round-robin <the 18 maps>`, 1 client | pass | W5.6's M1: alpha joins the 18 default maps in turn; on each its world has the map's size, it sees its team's cores and walks, and `exec` records the map clean or failing (with its desync and error lines) in `m1-maps.tsv`. Evidence in `evidence/m1/`. |
| `m1-two-clients.rig` | `vm/shard-server.sh --join round-robin <the 18 maps>`, 2 clients | pass | W6.5: M1 with two clients. Round-robin joins, one per map, the other client following with `/world`; on each map both see the other's unit move and the other's blocks, and `m1-maps.tsv` records it. Generated by `m1-two-clients.py`. Evidence in `evidence/m1-two-clients/`. |
| `m2.rig` | `vm/shard-server.sh m2`, or `vm/vanilla-server.sh -- host m2` (m2.msav, `M2Map.java`) | pass | W5.6's M2, 2 clients, one script for both servers: alpha builds copper drills and a line into the core, bravo coal drills, combustion generators, a battery, a lancer and a node; both see the graph charge and the copper arrive, bravo rejoins, and the lancer kills wave 1 after `runwave`. Evidence in `evidence/m2/`. |

Past runs' evidence is archived outside this repository, as `evidence/<scenario>/` (the scenario table names it), and
`shard-l5.sh` writes new summaries to `build/rig-evidence/`. Each run kept `verdict.json`, `summary.txt`, `rig.log`, `server.log` and
each client's `last_log.txt`, plus the screenshots for the gate and the two failure scenarios (the self-tests'
screenshots stayed in scratch, to keep the repository small). Newer rows kept a short `README.md` (results and
counts), `summary.txt`, `verdict.json` and a screenshot or two, never raw captures or client data.

**Ground Zero notes.** It is a sector map, not in the server's `host` list, so `vanilla serpulo/groundZero`
installs it as a custom map. Its name tag is "Ground Zero", hence `host Ground_Zero`. Its core (shard, 129,55)
starts empty, so the gate runs `console fillitems sharded` before building.

## Known limits

- The `within` check compares positions read at slightly different moments. Use a tolerance of a tile or more
  while something moves.
- `all:` runs the action on one client after the other, not at the same time.
- Typing assumes the US layout of Xvfb's default keymap.
- The clients share the VM with the server. Two software-rendered clients keep a 4-vCPU VM near 90% CPU, so
  Xvfb and the clients run at `nice 10`, and the server keeps the priority. A scenario that asserts the server log
  has no `WARN` (no ticks dropped) still measures the server on a busy machine: when boat's hosts are busy, every
  server thread can wake 80-100 ms late at once (the tick threads and the unrelated `tick-watchdog` alike, with the
  JVM under 3% CPU), and a world drops 4-5 ticks. Tell that from a server stall in the run's JFR before blaming the
  code, and give such gates `--type large` (W2.4's final L5 did).
- Vanilla's `ArcNetProvider.connectClient` can leave two update threads reading one socket after a reconnect
  ("mismatch"). `connect` stops the client and waits for its threads first. Without that, `reconnect-stress.rig`
  failed with "mismatch" against stock vanilla in 2 runs of 3, and against Shard in 1 of 2 (W2.4). To check a
  server's framing, capture beside it (`tcpdump -Z root -i lo -U -s 0 -w lo.pcap tcp port 6567` in `--server`, as
  sudo fails in the rig's user namespace) and run `rig/arcnet-pcap.py lo.pcap --show 12`.
- Not built yet: the WP17 "headless probe" spike (vanilla `NetClient` + `Logic` without rendering, logging the
  corrections it receives).
