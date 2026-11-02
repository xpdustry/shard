package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.config.*;

/**
 * The server settings an intent is admitted under, read from the world's config when its packet is decoded and
 * carried in the intent, so the command log replays a world's admissions exactly whatever the config says by then.
 *
 * @param strict vanilla's {@code strict}: the server moves a player's unit itself and corrects the client
 *     ({@code NetServer.java:782})
 * @param antiSpam vanilla's {@code antiSpam}: block interactions are rate-limited
 * @param interactWindowMillis {@code interactRateWindow}, in milliseconds
 * @param interactLimit {@code interactRateLimit}: interactions allowed per window
 * @param interactKick {@code interactRateKick}: interactions in one window that get the player kicked
 */
public record Policy(boolean strict, boolean antiSpam, long interactWindowMillis, int interactLimit, int interactKick){
    /** Vanilla's defaults. */
    public static final Policy VANILLA = new Policy(true, true, 6_000, 25, 60);

    public static Policy of(Config config){
        return new Policy(config.get(ConfigKeys.GAME_STRICT), config.get(ConfigKeys.CHAT_ANTI_SPAM),
            config.get(ConfigKeys.GAME_INTERACT_RATE_WINDOW).toMillis(), config.get(ConfigKeys.GAME_INTERACT_RATE_LIMIT),
            config.get(ConfigKeys.GAME_INTERACT_RATE_KICK));
    }
}
