package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/**
 * Vanilla's {@code Administration.ActionFilter}: allows or denies what a player asks to do. A denied action is rolled
 * back as vanilla does it (the old config sent back, the plan removed from the client's queue) and its intent is
 * logged as rejected.
 *
 * <p>Filters are built-in admission policies (ARCHITECTURE.md §4): registered with {@code @Register}, run on the
 * world thread in {@link #order()} then class name, each within the tick's budget, so they must not block. What they
 * decide from is the world and the action, so a replay decides the same. Admins and plugins don't add code here; they
 * add {@link ActionRules}, which the built-in {@link RuleFilter} enforces.
 */
@Registry
public interface ActionFilter{
    Comparator<ActionFilter> ORDER = Comparator.comparingInt(ActionFilter::order).thenComparing(filter -> filter.getClass().getName());

    /** Lower runs first. Vanilla's own interaction limit, which it adds first, is 0. */
    default int order(){
        return 0;
    }

    /**
     * Whether {@code action} may go ahead. A filter that denies should tell the player why, through
     * {@code context}, as vanilla's contract asks.
     */
    boolean allow(PlayerAction action, ActionContext context);
}
