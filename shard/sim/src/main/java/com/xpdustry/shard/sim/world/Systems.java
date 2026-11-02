package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.registry.*;

import java.util.*;
import java.util.function.*;

/**
 * The registered {@link PhaseHandler}s, grouped by phase in run order, and the {@link WorldPart} classes each world
 * instantiates, with their constructors. Immutable and shared by every world built with it.
 */
public final class Systems{
    private static final Comparator<PhaseHandler> RUN_ORDER = Comparator.comparingInt(PhaseHandler::order)
        .thenComparing(handler -> handler.getClass().getName());

    private final List<List<PhaseHandler>> handlers;
    private final List<Class<? extends WorldPart>> parts;
    /** Each part's constructor; looked up, never iterated. */
    private final Map<Class<?>, Supplier<? extends WorldPart>> constructors;

    private Systems(List<? extends PhaseHandler> handlers, List<Class<? extends WorldPart>> parts,
                    Map<Class<?>, Supplier<? extends WorldPart>> constructors){
        var byPhase = new ArrayList<List<PhaseHandler>>();
        for(Phase phase : Phase.values()){
            byPhase.add(handlers.stream().filter(h -> h.phase() == phase).sorted(RUN_ORDER).<PhaseHandler>map(h -> h).toList());
        }
        this.handlers = List.copyOf(byPhase);
        var sorted = new ArrayList<>(parts);
        sorted.sort(Comparator.comparing(Class::getName));
        for(int i = 1; i < sorted.size(); i++){
            if(sorted.get(i) == sorted.get(i - 1)) throw new IllegalArgumentException("part listed twice: " + sorted.get(i));
        }
        this.parts = List.copyOf(sorted);
        this.constructors = Collections.unmodifiableMap(new IdentityHashMap<>(constructors));
    }

    /** Everything registered on the class path of {@code :sim}. */
    public static Systems builtIn(){
        return BuiltIn.SYSTEMS;
    }

    /**
     * Exactly these handlers and registered parts, for tests and tools. A part class that isn't registered goes in
     * with {@link #with(Class, Supplier)}.
     */
    public static Systems of(List<? extends PhaseHandler> handlers, List<Class<? extends WorldPart>> parts){
        return new Systems(handlers, parts, constructors(parts));
    }

    /**
     * These systems plus the part {@code type}, made by {@code constructor}: for a test's or tool's part, which
     * carries no {@code @Register} because that would add it to every world on the class path.
     */
    public <P extends WorldPart> Systems with(Class<P> type, Supplier<? extends P> constructor){
        var all = new ArrayList<>(parts);
        all.add(type);
        var made = new IdentityHashMap<>(constructors);
        made.put(type, constructor);
        var handlers = new ArrayList<PhaseHandler>();
        this.handlers.forEach(handlers::addAll);
        return new Systems(handlers, all, made);
    }

    /** These parts, with {@code handlers} instead of these systems' handlers. */
    public Systems withHandlers(List<? extends PhaseHandler> handlers){
        return new Systems(handlers, parts, constructors);
    }

    /** The handlers of {@code phase}, in run order. */
    public List<PhaseHandler> handlers(Phase phase){
        return handlers.get(phase.ordinal());
    }

    /** The part classes, sorted by name: the order of {@link World#parts()} and of their hash sections. */
    public List<Class<? extends WorldPart>> parts(){
        return parts;
    }

    /** A new instance of part {@code type}, which must be one of {@link #parts()}. */
    public <P extends WorldPart> P create(Class<P> type){
        Supplier<? extends WorldPart> constructor = constructors.get(type);
        if(constructor == null) throw new IllegalArgumentException(type.getName() + " is not a part of these systems");
        return type.cast(constructor.get());
    }

    /** Whether {@code type} is one of {@link #parts()}. */
    public boolean has(Class<?> type){
        return constructors.containsKey(type);
    }

    private static Map<Class<?>, Supplier<? extends WorldPart>> constructors(List<Class<? extends WorldPart>> parts){
        var out = new IdentityHashMap<Class<?>, Supplier<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : parts){
            try{
                out.put(part, Registries.constructor(part));
            }catch(IllegalArgumentException e){
                throw new IllegalArgumentException(part.getName() + " is not a registered part: add it with Systems.with", e);
            }
        }
        return out;
    }

    private static final class BuiltIn{
        static final Systems SYSTEMS = of(Registries.instances(PhaseHandler.class), Registries.classes(WorldPart.class));
    }
}
