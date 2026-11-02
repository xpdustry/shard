package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The registered {@link CallMapping}s, looked up by an event's class: the class itself, then its interfaces nearest
 * first, then its superclass's. Two mappings for one type fail at construction. Thread-safe.
 */
public final class CallMappings{
    private static final CallMapping<?> NONE = new CallMapping<>(){
        @Override
        public Class<WorldEvent> event(){
            return WorldEvent.class;
        }

        @Override
        public void map(WorldEvent event, CallContext context, CallSink sink){
        }
    };

    private final Map<Class<?>, CallMapping<?>> byType = new HashMap<>();
    private final ConcurrentHashMap<Class<?>, CallMapping<?>> resolved = new ConcurrentHashMap<>();

    private CallMappings(List<CallMapping<?>> mappings){
        for(CallMapping<?> mapping : mappings){
            CallMapping<?> previous = byType.putIfAbsent(mapping.event(), mapping);
            if(previous != null){
                throw new IllegalArgumentException(mapping.event().getName() + " has two Call mappings: " + previous.getClass().getName()
                    + " and " + mapping.getClass().getName());
            }
        }
    }

    /** Every mapping registered on the class path. */
    public static CallMappings builtIn(){
        return BuiltIn.MAPPINGS;
    }

    /** Exactly these mappings, for tests. */
    public static CallMappings of(List<CallMapping<?>> mappings){
        return new CallMappings(mappings);
    }

    /**
     * Sends {@code event}'s Calls through {@code sink}.
     *
     * @return whether a mapping took it; false for events that have no Call
     */
    @SuppressWarnings("unchecked")
    public boolean map(WorldEvent event, CallContext context, CallSink sink){
        CallMapping<?> mapping = resolved.computeIfAbsent(event.getClass(), this::find);
        if(mapping == NONE) return false;
        ((CallMapping<WorldEvent>)mapping).map(event, context, sink);
        return true;
    }

    /** Whether events of {@code type} have a mapping. */
    public boolean maps(Class<? extends WorldEvent> type){
        return resolved.computeIfAbsent(type, this::find) != NONE;
    }

    private CallMapping<?> find(Class<?> type){
        for(Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()){
            CallMapping<?> own = byType.get(at);
            if(own != null) return own;
            // breadth first over the interfaces, nearest first
            var queue = new ArrayDeque<Class<?>>(List.of(at.getInterfaces()));
            while(!queue.isEmpty()){
                Class<?> next = queue.poll();
                CallMapping<?> mapping = byType.get(next);
                if(mapping != null) return mapping;
                queue.addAll(List.of(next.getInterfaces()));
            }
        }
        return NONE;
    }

    private static final class BuiltIn{
        @SuppressWarnings({"unchecked", "rawtypes"})
        static final CallMappings MAPPINGS = new CallMappings((List)Registries.instances(CallMapping.class));
    }
}
