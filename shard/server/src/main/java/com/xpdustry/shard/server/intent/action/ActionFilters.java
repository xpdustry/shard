package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/**
 * Vanilla's {@code allowAction} (Administration.java:173): runs the filters in order and stops at the first that
 * denies. The registered filters are the build's, so every world and every replay of it runs the same ones.
 */
public final class ActionFilters{
    private static final ActionFilters BUILT_IN = new ActionFilters(Registries.instances(ActionFilter.class));

    private final List<ActionFilter> filters;

    /** Exactly {@code filters}, in their {@link ActionFilter#ORDER}; for tests and tools. */
    public ActionFilters(List<? extends ActionFilter> filters){
        var sorted = new ArrayList<ActionFilter>(filters);
        sorted.sort(ActionFilter.ORDER);
        this.filters = List.copyOf(sorted);
    }

    /** Every registered filter. */
    public static ActionFilters builtIn(){
        return BUILT_IN;
    }

    public List<ActionFilter> filters(){
        return filters;
    }

    /** The first filter that denies {@code action}, or null if every one allows it. */
    public ActionFilter denier(PlayerAction action, ActionContext context){
        for(ActionFilter filter : filters){
            if(!filter.allow(action, context)) return filter;
        }
        return null;
    }
}
