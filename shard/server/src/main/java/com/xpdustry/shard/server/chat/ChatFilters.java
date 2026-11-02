package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/** {@code Administration.filterMessage}: the registered filters in order, stopping at the first that stops the line. */
public final class ChatFilters{
    private static final ChatFilters BUILT_IN = new ChatFilters(Registries.instances(ChatFilter.class));

    private final List<ChatFilter> filters;

    /** Exactly {@code filters}, in their {@link ChatFilter#ORDER}; for tests. */
    public ChatFilters(List<? extends ChatFilter> filters){
        var sorted = new ArrayList<ChatFilter>(filters);
        sorted.sort(ChatFilter.ORDER);
        this.filters = List.copyOf(sorted);
    }

    public static ChatFilters builtIn(){
        return BUILT_IN;
    }

    public List<ChatFilter> filters(){
        return filters;
    }

    /** The line after every filter, or null if one stopped it. */
    public String filter(String message, ChatContext context){
        String current = message;
        for(ChatFilter filter : filters){
            current = filter.filter(current, context);
            if(current == null) return null;
        }
        return current;
    }
}
