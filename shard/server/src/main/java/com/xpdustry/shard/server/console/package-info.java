/**
 * The server's stdin console (WP51), one {@link com.xpdustry.shard.server.console.Console} per process. Output goes to
 * the console's stream, apart from the log; {@code tile} and {@code players} are what the rig's scenarios read.
 *
 * <p>Vanilla's 43 {@code ServerControl} commands, and where each stands: ported (vanilla's syntax and lines), mapped
 * to a Shard command for a host of many worlds, or refused with the reason it prints. Moderation changes are in the
 * store and hold for every world.
 *
 * <table>
 *   <caption>Vanilla's console commands in Shard</caption>
 *   <tr><th>Command</th><th>State</th></tr>
 *   <tr><td>help</td><td>ported: {@code help [command]}</td></tr>
 *   <tr><td>version</td><td>ported (also Shard's build id)</td></tr>
 *   <tr><td>exit</td><td>ported</td></tr>
 *   <tr><td>stop</td><td>mapped: {@code stop [world]}, every world by default</td></tr>
 *   <tr><td>host</td><td>mapped: {@code host [map] [mode] [id]} hosts one more world</td></tr>
 *   <tr><td>maps</td><td>ported: custom maps by default, {@code all} or {@code default}</td></tr>
 *   <tr><td>reloadassets</td><td>refused: content is the build's epoch, fixed at compile time</td></tr>
 *   <tr><td>reloadmaps</td><td>refused: the catalog is read at startup (WP48's map registry)</td></tr>
 *   <tr><td>status</td><td>mapped: one line per world</td></tr>
 *   <tr><td>mods</td><td>refused: Shard runs no mods; plugins are WP52's API</td></tr>
 *   <tr><td>mod</td><td>refused, as {@code mods}</td></tr>
 *   <tr><td>js</td><td>refused: no script engine</td></tr>
 *   <tr><td>say</td><td>ported: every player in every world</td></tr>
 *   <tr><td>pause</td><td>mapped: {@code pause [world] <on/off>}, every world by default ({@code SetPause}), clearing
 *   the empty-world auto-pause ({@code game.auto-pause}) as vanilla clears {@code autoPaused}</td></tr>
 *   <tr><td>rules</td><td>refused: rules come from the map and {@code shard.yml} (WP46)</td></tr>
 *   <tr><td>dumpsettings</td><td>mapped: every setting and the layer its value comes from</td></tr>
 *   <tr><td>fillitems</td><td>mapped: {@code fillitems [world] [team]}</td></tr>
 *   <tr><td>playerlimit</td><td>mapped: shows {@code net.player-limit}; it is set in {@code shard.yml}</td></tr>
 *   <tr><td>config</td><td>mapped: reads any setting by vanilla or Shard name; {@code config reload} applies {@code shard.yml}</td></tr>
 *   <tr><td>subnet-ban</td><td>ported, with CIDR blocks; vanilla's whole-octet prefixes are read as one</td></tr>
 *   <tr><td>name-ban</td><td>ported</td></tr>
 *   <tr><td>whitelist</td><td>ported</td></tr>
 *   <tr><td>shuffle</td><td>mapped: {@code shuffle <world> [mode]}, until restart; {@code game.shuffle} is the setting</td></tr>
 *   <tr><td>nextmap</td><td>mapped: {@code nextmap [world] <map...>}, every world without one</td></tr>
 *   <tr><td>kick</td><td>ported, with vanilla's cooldown; also by session id</td></tr>
 *   <tr><td>ban</td><td>ported; a ban by name bans the address too (vanilla's {@code ||} skips it)</td></tr>
 *   <tr><td>bans</td><td>ported, with subnets and each ban's id</td></tr>
 *   <tr><td>unban</td><td>ported: an address, then a UUID</td></tr>
 *   <tr><td>pardon</td><td>ported</td></tr>
 *   <tr><td>admin</td><td>ported</td></tr>
 *   <tr><td>admins</td><td>ported</td></tr>
 *   <tr><td>players</td><td>mapped: also each player's session id, world and state</td></tr>
 *   <tr><td>runwave</td><td>mapped: {@code runwave [world]}</td></tr>
 *   <tr><td>loadautosave</td><td>refused: a world restarts from its own checkpoints; {@code recover} brings back a failed one</td></tr>
 *   <tr><td>load</td><td>mapped: {@code load <slot> [id]} hosts the slot's save as a new world (the slot's name by
 *   default), with its own rules and without {@code logic.play()} ({@code Saves}); a slot outside the directory is
 *   refused</td></tr>
 *   <tr><td>save</td><td>mapped: {@code save <slot> [world]} writes a vanilla msav to {@code persistence.saves-directory};
 *   no {@code embedAssets}</td></tr>
 *   <tr><td>saves</td><td>ported, over {@code persistence.saves-directory}</td></tr>
 *   <tr><td>gameover</td><td>mapped: {@code gameover [world]}, every world by default</td></tr>
 *   <tr><td>info</td><td>ported, plus when the player was last seen</td></tr>
 *   <tr><td>search</td><td>ported</td></tr>
 *   <tr><td>gc</td><td>ported</td></tr>
 *   <tr><td>yes</td><td>ported, with vanilla's "did you mean" suggestions</td></tr>
 *   <tr><td>dos-ban</td><td>ported; checked at the connect packet, in memory as in vanilla</td></tr>
 * </table>
 *
 * <p>Shard's own: {@code tile <world> <x> <y>} (a tile's floor, overlay, block, rotation and team),
 * {@code recover <world>} (a failed world from its last checkpoint) and {@code perf [mark|summary]} (CPU, tick
 * lateness and traffic, which the load gate reads).
 *
 * <p>Owner: WP51.
 */
package com.xpdustry.shard.server.console;
