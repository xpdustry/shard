/**
 * The server-side UI and plugin channels (WP52): {@link com.xpdustry.shard.server.ui.Ui} sends vanilla's menu, popup,
 * label, toast, effect, sound and plugin-packet Calls to a {@link com.xpdustry.shard.server.ui.To}, and routes what
 * clients answer (W2.14's {@code menuChoose}, {@code textInputResult} and {@code serverPacket} intents) back to the
 * handlers plugins registered.
 *
 * <p>Owner: WP52.
 */
package com.xpdustry.shard.server.ui;
