package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.server.intent.*;

/**
 * Vanilla's block interaction rate limit, the filter {@code Administration}'s constructor adds first
 * (Administration.java:73-90). With {@code antiSpam} on, every action but building, breaking and commanding units counts
 * in the player's window: past {@code interactRateLimit} it is denied, with a message at most every 2 s, and past
 * {@code interactRateKick} the player is kicked for 30 s. Admins are limited too, as in vanilla; only the local player
 * isn't, and a server has none.
 */
@Register
public final class InteractionRate implements ActionFilter{
    /** {@code 1000 * 30}. */
    public static final long KICK_MILLIS = 30_000;
    /** {@code messageTimer.get(60f * 2f)}. */
    public static final float MESSAGE_TICKS = 60f * 2f;
    public static final String KICK_REASON = "You are interacting with too many blocks.";
    public static final String MESSAGE = "[scarlet]You are interacting with blocks too quickly.";

    @Override
    public int order(){
        return 0;
    }

    @Override
    public boolean allow(PlayerAction action, ActionContext context){
        ActionType type = action.type();
        Policy policy = context.policy();
        if(type == ActionType.BREAK_BLOCK || type == ActionType.PLACE_BLOCK || type == ActionType.COMMAND_UNITS || !policy.antiSpam()) return true;
        PlayerInput input = context.input();
        if(input.interactions.allow(context.arrivalMillis(), policy.interactWindowMillis(), policy.interactLimit())) return true;
        if(input.interactions.occurrences > policy.interactKick()){
            context.kick(KICK_REASON, KICK_MILLIS);
        }else if(input.messageDue(context.world().ticks(), MESSAGE_TICKS)){
            context.message(MESSAGE);
        }
        return false;
    }
}
