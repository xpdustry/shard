package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.config.*;

/**
 * The chat settings a line is admitted under, read from the world's config when its packet arrives and carried in its
 * intent, as W2.14's {@code Policy} is, so a replay filters the same way whatever the config says by then.
 *
 * @param antiSpam vanilla's {@code antiSpam}
 * @param rateLimitMillis {@code messageRateLimit}, in milliseconds; 0 is off
 * @param spamKick {@code messageSpamKick}: lines inside the rate limit before a kick; 0 is off
 */
public record ChatPolicy(boolean antiSpam, long rateLimitMillis, int spamKick){
    /** Vanilla's headless defaults. */
    public static final ChatPolicy VANILLA = new ChatPolicy(true, 0, 3);

    public static ChatPolicy of(Config config){
        return new ChatPolicy(config.get(ConfigKeys.CHAT_ANTI_SPAM), config.get(ConfigKeys.CHAT_MESSAGE_RATE_LIMIT).toMillis(),
            config.get(ConfigKeys.CHAT_MESSAGE_SPAM_KICK));
    }
}
