# Game flow, chat, menus, admin and the console

Read before touching `server.chat`, `server.game`, `server.ui`, `server.admin`, `server.console`, game over and map
rotation, menus and dialogs, kicks and bans, or console commands. Rows: W1.12, W3.8, W3.13. Their owned paths and WPs
are in [`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are
relative to the repository root.

**Game flow, chat and menus (W3.8 done: WP48, WP49, WP52)**
- Done:
  - chat lines are intents, admitted with vanilla's anti-spam filter (`ChatFilter`s, per player in world state);
  - Arc's `CommandHandler`, ported, with /help, /t, /a, /votekick, /vote, /sync and /world. Plugins add theirs
    through `server.chat().commands()`;
  - game over per world, then the rotation (`game.maps`, `game.shuffle`) and a reload onto the next map
    (`Host.replace`). The console has `gameover`, `nextmap` and `shuffle`;
  - `server.ui.Ui` for menus, text input, messages, toasts, labels, effects, sounds and plugin packets;
  - `Host.listen`, a tick-thread hook after each tick's Calls.

  `GameOverOracleTest` matches `checkGameState` in all 11 gamemode cases. `UiCallsOracleTest` has every UI Call
  byte-equal to vanilla's. L5: `game-flow.rig` passed 43/43 on Shard and on vanilla, with three stock clients.
  `Host.stop` now waits for the kicked members' leaves. Before, the server never forgot those sessions.
- Deviations: the `infoMessage` follows `updateGameOver` instead of preceding it. /a reaches admins in every
  world. A session still loading when its world reloads is kicked to join again.
- Open: no effect or sound name tables; chat isn't in the store's `chat_log`; vanilla's 500 ms join chat rule;
  PvP teams on a reload (closed by W5.3, [`pvp.md`](pvp.md)); `reloadmaps`.

**Admin and the console (W3.13 done: WP50, and WP51's command set)**
- Done:
  - kicks, with reasons, expiry and pardons, and name bans, all in the store (migration 0002), so kick cooldowns
    outlive a restart;
  - name bans are case-insensitive regexes, capped at 100k character reads per match;
  - the DOS-ban list; `adminRequest`'s kick, ban, trace, wave skip and switchTeam, applied with vanilla's checks.
    An admin's switchTeam never applied before (it checked the wrong config type);
  - all 43 vanilla console commands, ported, mapped or refused with a printed reason (`ConsoleCommandsTest`).

  `AdminRequestServerTest` checks the store after each admin action. L5: `admin.rig` passed 50/50 on Shard and on
  vanilla, with the same trace dialogs on both.
- Deviations: a name ban that hits the read cap counts as a match; `unban <ip>` lifts only that address's bans and
  the bans of the same players; `ban name X` bans both the uuid and the address (Q196); the DOS list is checked at the
  connect packet; `kick` broadcasts only to the player's world.
- Open:
  - refused for now: `loadautosave`, `rules`, `reloadmaps`; `players` keeps Shard's format (`save`, `load` and
    `saves` are W6.4's, [`pvp.md`](pvp.md));
  - requests: the votekick should store reason "vote" (`server.chat`); a packet-spam trip should add the address to
    the DOS list (`connect`, `net`); a per-world pause (`server.world`; closed by W5.3: `World.paused` and the console's `pause`, [`pvp.md`](pvp.md)); a hosted world's `MapState` and a catalog
    rescan (`server.host`).

**Open from review 41 (W3.8, routed; details in [`history-reviews.md`](history-reviews.md))**
- The server seed isn't configurable; a world stopped in the middle of its own reload.
