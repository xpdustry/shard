package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/**
 * Vanilla's {@code Administration.ChatFilter} (Administration.java:668): passes a chat line on, changed or not, or
 * stops it. Vanilla runs the filters on plain chat and on {@code /t}'s message ({@code filterMessage}), in the order
 * they were added; the anti-spam filter its constructor adds comes first.
 *
 * <p>Filters are admission policies, as W2.14's action filters are (ARCHITECTURE.md §4): registered with
 * {@code @Register}, run on the world thread at the line's admission, in {@link #order()} then class name, deciding
 * from the world, the line and its recorded arrival only, so a replay filters the same lines the same way. What a
 * filter tells the player goes out as a Call after the tick.
 */
@Registry
public interface ChatFilter{
    Comparator<ChatFilter> ORDER = Comparator.comparingInt(ChatFilter::order).thenComparing(filter -> filter.getClass().getName());

    /** Lower runs first; vanilla's anti-spam filter is 0. */
    default int order(){
        return 0;
    }

    /** The line to send on, or null to stop it; a filter that stops a line should tell the player why. */
    String filter(String message, ChatContext context);
}
