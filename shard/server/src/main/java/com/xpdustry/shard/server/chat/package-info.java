/**
 * Chat (WP49): vanilla's {@code sendChatMessage} and {@code NetServer}'s client commands in a host of many worlds.
 * {@link com.xpdustry.shard.server.chat.Chat} takes a line on its connection's thread and offers it to the session's
 * world as a {@link com.xpdustry.shard.server.chat.ChatLine}; the world admits it through the registered
 * {@link com.xpdustry.shard.server.chat.ChatFilter}s (vanilla's {@link com.xpdustry.shard.server.chat.AntiSpam}) and
 * sends a plain line in its tick's Call order; a command runs after the tick through Arc's
 * {@link com.xpdustry.shard.server.chat.CommandHandler}: {@code /help}, {@code /t}, {@code /a}, {@code /votekick},
 * {@code /vote}, {@code /sync} and Shard's {@code /world}, plus what plugins register.
 *
 * <p>Owner: WP49.
 */
package com.xpdustry.shard.server.chat;
