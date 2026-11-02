package com.xpdustry.shard.compat.v160.command;

import arc.struct.*;
import arc.util.*;

import java.lang.reflect.*;

import static mindustry.Vars.*;

/**
 * Vanilla's {@code ControlPathfinder} without its thread, driven on Shard's schedule: W3.11's
 * {@code sim.path.VanillaControlPath} (package-private in {@code :sim}'s oracle tests), cut to what a whole world needs.
 * Each frame the test runs one pass of the thread's loop when Shard's {@code ControlFlush} runs one ({@link #pass});
 * vanilla's own {@code Trigger.update} handler does the clean-up inside the frame. Pins, as W3.11's:
 * <ul>
 *   <li>the thread is stopped, and a pass runs to the end (no 12 ms budget);</li>
 *   <li>{@code invalidRequests} and {@code unitRequests} iterate in insertion order, as Shard's do.</li>
 * </ul>
 */
public final class VanillaControl{
    private static final Field THREAD, QUEUE, THREAD_REQUESTS, FIELDS, FIELD_LIST, TO_UPDATE, TO_INNER, INVALID, UNIT_REQUESTS,
        INVALIDATED, OLD_CACHE, DESTINATION, COST_ID, TEAM, FIELD_FRONTIER;
    private static final Method UPDATE_FIELDS, UPDATE_COMPLETE, UPDATE_INNER, RECALCULATE;

    static{
        try{
            Class<mindustry.ai.ControlPathfinder> type = mindustry.ai.ControlPathfinder.class;
            Class<?> request = Class.forName("mindustry.ai.ControlPathfinder$PathRequest");
            Class<?> field = Class.forName("mindustry.ai.ControlPathfinder$FieldCache");
            THREAD = open(type.getDeclaredField("thread"));
            QUEUE = open(type.getDeclaredField("queue"));
            THREAD_REQUESTS = open(type.getDeclaredField("threadPathRequests"));
            FIELDS = open(type.getDeclaredField("fields"));
            FIELD_LIST = open(type.getDeclaredField("fieldList"));
            TO_UPDATE = open(type.getDeclaredField("clustersToUpdate"));
            TO_INNER = open(type.getDeclaredField("clustersToInnerUpdate"));
            INVALID = open(type.getDeclaredField("invalidRequests"));
            UNIT_REQUESTS = open(type.getDeclaredField("unitRequests"));
            INVALIDATED = open(request.getDeclaredField("invalidated"));
            OLD_CACHE = open(request.getDeclaredField("oldCache"));
            DESTINATION = open(request.getDeclaredField("destination"));
            COST_ID = open(request.getDeclaredField("costId"));
            TEAM = open(request.getDeclaredField("team"));
            FIELD_FRONTIER = open(field.getDeclaredField("frontier"));
            UPDATE_FIELDS = open(type.getDeclaredMethod("updateFields", field, long.class));
            UPDATE_COMPLETE = open(type.getDeclaredMethod("updateClustersComplete", int.class));
            UPDATE_INNER = open(type.getDeclaredMethod("updateClustersInner", int.class));
            RECALCULATE = open(type.getDeclaredMethod("recalculatePath", request));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private VanillaControl(){
    }

    private static <T extends AccessibleObject> T open(T member){
        member.setAccessible(true);
        return member;
    }

    private static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    private static Object call(Method method, Object owner, Object... args){
        try{
            return method.invoke(owner, args);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }catch(InvocationTargetException e){
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Stops the thread of the world's {@code controlPath} (a world load started it), pins its order and returns it. */
    public static mindustry.ai.ControlPathfinder take(){
        try{
            var thread = (Thread)THREAD.get(controlPath);
            if(thread != null){
                thread.interrupt();
                thread.join(10_000);
                if(thread.isAlive()) throw new IllegalStateException("vanilla's control pathfinder thread didn't stop");
                THREAD.set(controlPath, null);
            }
            INVALID.set(controlPath, new OrderedSet<>());
            UNIT_REQUESTS.set(controlPath, new OrderedMap<>());
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return controlPath;
    }

    /** True if the world's {@code controlPath} has its thread and it is alive (a run that didn't {@link #take} it). */
    public static boolean threadAlive(){
        var thread = (Thread)get(THREAD, controlPath);
        return thread != null && thread.isAlive();
    }

    private static TaskQueue queue(mindustry.ai.ControlPathfinder finder){
        return (TaskQueue)get(QUEUE, finder);
    }

    @SuppressWarnings("unchecked")
    private static Seq<Object> threads(mindustry.ai.ControlPathfinder finder){
        return (Seq<Object>)get(THREAD_REQUESTS, finder);
    }

    @SuppressWarnings("unchecked")
    private static LongMap<Object> fields(mindustry.ai.ControlPathfinder finder){
        return (LongMap<Object>)get(FIELDS, finder);
    }

    @SuppressWarnings("unchecked")
    private static Seq<Object> fieldList(mindustry.ai.ControlPathfinder finder){
        return (Seq<Object>)get(FIELD_LIST, finder);
    }

    /**
     * One wake of the thread's loop (ControlPathfinder.java:1549): its queue, the changed clusters, the invalid check
     * when {@code invalidCheck}, and every field's whole frontier; then what it posted to the main thread.
     */
    public static void pass(mindustry.ai.ControlPathfinder finder, boolean invalidCheck){
        queue(finder).run();
        IntSet update = (IntSet)get(TO_UPDATE, finder), inner = (IntSet)get(TO_INNER, finder);
        update.each(cluster -> {
            call(UPDATE_COMPLETE, finder, cluster);
            inner.remove(cluster);
        });
        inner.each(cluster -> call(UPDATE_INNER, finder, cluster));
        inner.clear();
        update.clear();
        if(invalidCheck) checkInvalid(finder);
        for(Object field : fields(finder).values()) call(UPDATE_FIELDS, finder, field, -1L);
    }

    @SuppressWarnings("unchecked")
    private static void checkInvalid(mindustry.ai.ControlPathfinder finder){
        try{
            var it = ((ObjectSet<Object>)get(INVALID, finder)).iterator();
            while(it.hasNext()){
                Object request = it.next();
                if(INVALIDATED.getBoolean(request)){
                    it.remove();
                    continue;
                }
                int destination = DESTINATION.getInt(request), costId = COST_ID.getInt(request), team = TEAM.getInt(request);
                long key = mindustry.gen.FieldIndex.get(destination, costId, team);
                Object field = fields(finder).get(key);
                if(field != null){
                    if(((IntQueue)get(FIELD_FRONTIER, field)).size == 0){
                        fields(finder).remove(key);
                        // Core.app.post(() -> fieldList.remove(field)), run before the next frame reads it
                        fieldList(finder).remove(field);
                        for(Object other : threads(finder)){
                            if(DESTINATION.getInt(other) == destination){
                                OLD_CACHE.set(other, field);
                                if(other != request) queue(finder).post(() -> call(RECALCULATE, finder, other));
                            }
                        }
                        queue(finder).post(() -> call(RECALCULATE, finder, request));
                        it.remove();
                    }
                }else{
                    queue(finder).post(() -> call(RECALCULATE, finder, request));
                    it.remove();
                }
            }
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }
}
