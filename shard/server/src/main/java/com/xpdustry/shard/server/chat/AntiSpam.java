package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.common.registry.*;

/**
 * Vanilla's anti-spam chat filter, the one {@code Administration}'s constructor adds (Administration.java:41-68). With
 * {@code antiSpam} on, for players who aren't admins: a line sooner than {@code messageRateLimit} after the last one
 * is stopped and counted, and {@code messageSpamKick} such lines in a row kick the player for two minutes; the same
 * line twice within 10 s is stopped. Otherwise the line becomes the last one.
 */
@Register
public final class AntiSpam implements ChatFilter{
    /** {@code 1000 * 60 * 2}. */
    public static final long KICK_MILLIS = 120_000;
    /** {@code 1000 * 10}. */
    public static final long REPEAT_MILLIS = 10_000;
    public static final String KICK_REASON = "You have been kicked for spamming.";
    public static final String REPEATED = "[scarlet]You may not send the same message twice.";

    @Override
    public String filter(String message, ChatContext context){
        ChatPolicy policy = context.policy();
        if(!policy.antiSpam() || context.player().admin) return message;
        ChatInput input = context.input();
        long now = context.arrivalMillis(), resetTime = policy.rateLimitMillis();
        if(resetTime > 0 && input.since(now) < resetTime){
            context.message("[scarlet]You may only send messages every " + resetTime / 1000 + " seconds.");
            input.infractions++;
            if(input.infractions >= policy.spamKick() && policy.spamKick() != 0) context.kick(KICK_REASON, KICK_MILLIS);
            return null;
        }else{
            input.infractions = 0;
        }
        if(message.equals(input.lastSentMessage) && input.since(now) < REPEAT_MILLIS){
            context.message(REPEATED);
            return null;
        }
        input.lastSentMessage = message;
        input.lastMessageTime = now;
        input.sent = true;
        return message;
    }
}
