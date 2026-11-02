package com.xpdustry.shard.sim.path;

import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.gen.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Vanilla's {@code ControlPathfinder} without its thread, driven by reflection: the oracle side of Shard's
 * {@link ControlPathfinder}. Each wake of vanilla's thread is split into the steps Shard's pass has
 * ({@link #runQueue}, {@link #updateClusters}, {@link #checkInvalid}, {@link #updateFields}), so a test can compare
 * the two between steps. Pins, each a documented nondeterminism of vanilla's (ARCHITECTURE.md §1):
 * <ul>
 *   <li>the thread is stopped ({@link #take()}), and the searches run when the test says, to the end (no 12 ms
 *   budget);</li>
 *   <li>{@code invalidRequests} and {@code unitRequests} iterate in insertion order ({@link #pinOrder}), as Shard's
 *   do, where vanilla's iterate in identity-hash order.</li>
 * </ul>
 */
final class VanillaControlPath{
    static final Class<?> REQUEST, FIELD, CLUSTER;
    private static final Field REQUEST_UPDATE, REQUEST_UNIT, FIELD_UPDATE, FIELD_KEY;
    private static final Field THREAD, QUEUE, THREAD_REQUESTS, FIELDS, FIELD_LIST, TO_UPDATE, TO_INNER, INVALID, UNIT_REQUESTS,
        CLUSTERS, RESULT_PATH, NOT_FOUND, INVALIDATED, OLD_CACHE, DESTINATION, COST_ID, TEAM, FIELD_FRONTIER, FIELD_CLUSTERS,
        PORTALS, CONNECTIONS;
    private static final Constructor<?> NEW_REQUEST;
    private static final Method INITIALIZE, UPDATE_FIELDS, UPDATE_COMPLETE, UPDATE_INNER, RECALCULATE;

    static{
        try{
            Class<mindustry.ai.ControlPathfinder> type = mindustry.ai.ControlPathfinder.class;
            REQUEST = Class.forName("mindustry.ai.ControlPathfinder$PathRequest");
            FIELD = Class.forName("mindustry.ai.ControlPathfinder$FieldCache");
            CLUSTER = Class.forName("mindustry.ai.ControlPathfinder$Cluster");
            THREAD = open(type.getDeclaredField("thread"));
            QUEUE = open(type.getDeclaredField("queue"));
            THREAD_REQUESTS = open(type.getDeclaredField("threadPathRequests"));
            FIELDS = open(type.getDeclaredField("fields"));
            FIELD_LIST = open(type.getDeclaredField("fieldList"));
            TO_UPDATE = open(type.getDeclaredField("clustersToUpdate"));
            TO_INNER = open(type.getDeclaredField("clustersToInnerUpdate"));
            INVALID = open(type.getDeclaredField("invalidRequests"));
            UNIT_REQUESTS = open(type.getDeclaredField("unitRequests"));
            CLUSTERS = open(type.getDeclaredField("clusters"));
            RESULT_PATH = open(REQUEST.getDeclaredField("resultPath"));
            NOT_FOUND = open(REQUEST.getDeclaredField("notFound"));
            INVALIDATED = open(REQUEST.getDeclaredField("invalidated"));
            OLD_CACHE = open(REQUEST.getDeclaredField("oldCache"));
            DESTINATION = open(REQUEST.getDeclaredField("destination"));
            COST_ID = open(REQUEST.getDeclaredField("costId"));
            TEAM = open(REQUEST.getDeclaredField("team"));
            FIELD_FRONTIER = open(FIELD.getDeclaredField("frontier"));
            FIELD_CLUSTERS = open(FIELD.getDeclaredField("fields"));
            PORTALS = open(CLUSTER.getDeclaredField("portals"));
            CONNECTIONS = open(CLUSTER.getDeclaredField("portalConnections"));
            REQUEST_UPDATE = open(REQUEST.getDeclaredField("lastUpdateId"));
            REQUEST_UNIT = open(REQUEST.getDeclaredField("unit"));
            FIELD_UPDATE = open(FIELD.getDeclaredField("lastUpdateId"));
            FIELD_KEY = open(FIELD.getDeclaredField("mapKey"));
            NEW_REQUEST = open(REQUEST.getDeclaredConstructor(Unit.class, int.class, int.class, int.class));
            INITIALIZE = open(type.getDeclaredMethod("initializePathRequest", REQUEST, int.class, int.class, int.class, int.class, int.class, int.class));
            UPDATE_FIELDS = open(type.getDeclaredMethod("updateFields", FIELD, long.class));
            UPDATE_COMPLETE = open(type.getDeclaredMethod("updateClustersComplete", int.class));
            UPDATE_INNER = open(type.getDeclaredMethod("updateClustersInner", int.class));
            RECALCULATE = open(type.getDeclaredMethod("recalculatePath", REQUEST));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private VanillaControlPath(){
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

    /** Stops the thread of the world's {@code controlPath} (a world load started it) and returns it. */
    static mindustry.ai.ControlPathfinder take(){
        try{
            var thread = (Thread)THREAD.get(controlPath);
            if(thread != null){
                thread.interrupt();
                thread.join(10_000);
                if(thread.isAlive()) throw new IllegalStateException("vanilla's control pathfinder thread didn't stop");
                THREAD.set(controlPath, null);
            }
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return controlPath;
    }

    /** {@code invalidRequests} and {@code unitRequests} in insertion order, as Shard keeps them. */
    static void pinOrder(mindustry.ai.ControlPathfinder finder){
        try{
            INVALID.set(finder, new OrderedSet<>());
            UNIT_REQUESTS.set(finder, new OrderedMap<>());
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** {@code new PathRequest(unit, team, costId, destination)}. */
    static Object request(Unit unit, int team, int costId, int destination){
        try{
            return NEW_REQUEST.newInstance(unit, team, costId, destination);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static void initialize(mindustry.ai.ControlPathfinder finder, Object request, int team, int costId, int unitX, int unitY, int goalX, int goalY){
        call(INITIALIZE, finder, request, team, costId, unitX, unitY, goalX, goalY);
    }

    /** What a new request posts: {@code threadPathRequests.add(request); recalculatePath(request)}. */
    static void start(mindustry.ai.ControlPathfinder finder, Object request){
        queue(finder).post(() -> {
            threads(finder).add(request);
            call(RECALCULATE, finder, request);
        });
    }

    static boolean notFound(Object request){
        try{
            return NOT_FOUND.getBoolean(request);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    static IntSeq resultPath(Object request){
        return (IntSeq)get(RESULT_PATH, request);
    }

    static Object oldCache(Object request){
        return get(OLD_CACHE, request);
    }

    @SuppressWarnings("unchecked")
    static LongMap<Object> fields(mindustry.ai.ControlPathfinder finder){
        return (LongMap<Object>)get(FIELDS, finder);
    }

    @SuppressWarnings("unchecked")
    static Seq<Object> fieldList(mindustry.ai.ControlPathfinder finder){
        return (Seq<Object>)get(FIELD_LIST, finder);
    }

    /** The field towards {@code goalPos} for a cost type and team, or null. */
    static Object field(mindustry.ai.ControlPathfinder finder, int goalPos, int costId, int team){
        return fields(finder).get(FieldIndex.get(goalPos, costId, team));
    }

    static IntQueue frontier(Object field){
        return (IntQueue)get(FIELD_FRONTIER, field);
    }

    @SuppressWarnings("unchecked")
    static IntMap<int[]> fieldClusters(Object field){
        return (IntMap<int[]>)get(FIELD_CLUSTERS, field);
    }

    /** {@code updateFields(cache, -1)}: the field's whole frontier. */
    static void updateField(mindustry.ai.ControlPathfinder finder, Object field){
        call(UPDATE_FIELDS, finder, field, -1L);
    }

    static TaskQueue queue(mindustry.ai.ControlPathfinder finder){
        return (TaskQueue)get(QUEUE, finder);
    }

    @SuppressWarnings("unchecked")
    static Seq<Object> threads(mindustry.ai.ControlPathfinder finder){
        return (Seq<Object>)get(THREAD_REQUESTS, finder);
    }

    static IntSet toUpdate(mindustry.ai.ControlPathfinder finder){
        return (IntSet)get(TO_UPDATE, finder);
    }

    static IntSet toInner(mindustry.ai.ControlPathfinder finder){
        return (IntSet)get(TO_INNER, finder);
    }

    @SuppressWarnings("unchecked")
    static ObjectSet<Object> invalid(mindustry.ai.ControlPathfinder finder){
        return (ObjectSet<Object>)get(INVALID, finder);
    }

    static Object[][][] clusters(mindustry.ai.ControlPathfinder finder){
        return (Object[][][])get(CLUSTERS, finder);
    }

    static IntSeq[] portals(Object cluster){
        return (IntSeq[])get(PORTALS, cluster);
    }

    static LongSeq[][] connections(Object cluster){
        return (LongSeq[][])get(CONNECTIONS, cluster);
    }

    static int destination(Object request){
        return (int)get(DESTINATION, request);
    }

    // the thread's loop (ControlPathfinder.java:1549), a step at a time

    static void runQueue(mindustry.ai.ControlPathfinder finder){
        queue(finder).run();
    }

    static void updateClusters(mindustry.ai.ControlPathfinder finder){
        IntSet update = toUpdate(finder), inner = toInner(finder);
        update.each(cluster -> {
            call(UPDATE_COMPLETE, finder, cluster);
            inner.remove(cluster);
        });
        inner.each(cluster -> call(UPDATE_INNER, finder, cluster));
        inner.clear();
        update.clear();
    }

    /** The invalid check's body, as vanilla's loop runs it once its 1,000 ms are up. */
    static void checkInvalid(mindustry.ai.ControlPathfinder finder){
        try{
            var it = invalid(finder).iterator();
            while(it.hasNext()){
                Object request = it.next();
                if(INVALIDATED.getBoolean(request)){
                    it.remove();
                    continue;
                }
                int destination = DESTINATION.getInt(request);
                Object field = field(finder, destination, COST_ID.getInt(request), TEAM.getInt(request));
                if(field != null){
                    if(frontier(field).size == 0){
                        fields(finder).remove(FieldIndex.get(destination, COST_ID.getInt(request), TEAM.getInt(request)));
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

    /**
     * The {@code Trigger.update} handler (ControlPathfinder.java:239), which only {@code Logic.update} fires: stale
     * requests and fields leave the thread's lists at the next pass, and the main thread's now ({@code Time.run(0)}).
     */
    @SuppressWarnings("unchecked")
    static void trigger(mindustry.ai.ControlPathfinder finder){
        try{
            var requests = (ObjectMap<Unit, Object>)get(UNIT_REQUESTS, finder);
            var stale = new ArrayList<Object>();
            for(Object request : requests.values()){
                if(REQUEST_UPDATE.getLong(request) <= state.updateId - 10 || !((Unit)REQUEST_UNIT.get(request)).isAdded()){
                    INVALIDATED.setBoolean(request, true);
                    queue(finder).post(() -> threads(finder).remove(request));
                    stale.add(request);
                }
            }
            var staleFields = new ArrayList<Object>();
            for(Object field : fieldList(finder)){
                if(FIELD_UPDATE.getLong(field) <= state.updateId - 30){
                    long key = FIELD_KEY.getLong(field);
                    queue(finder).post(() -> fields(finder).remove(key));
                    staleFields.add(field);
                }
            }
            for(Object request : stale) requests.remove((Unit)REQUEST_UNIT.get(request));
            for(Object field : staleFields) fieldList(finder).remove(field);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** Every field's whole frontier ({@code updateFields(cache, -1)}): no budget. */
    static void updateFields(mindustry.ai.ControlPathfinder finder){
        for(Object field : fields(finder).values()) updateField(finder, field);
    }
}
