package com.xpdustry.shard.oracle;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.game.EventType.*;
import mindustry.world.*;

import java.lang.reflect.*;

import static mindustry.Vars.*;

/**
 * Vanilla's {@code Pathfinder} without its thread, for any module's oracle tests (W2.17's {@code sim.ai} copy is the
 * original): the documented nondeterminism of the oracle pinned to one order. {@link #take()} stops the thread and
 * finishes whatever it had started; {@link #settle()}, after each frame, runs the thread's loop body once with no
 * time budget ({@code queue.run()}, then every field: targets if dirty, then the whole frontier). So a field requested
 * in frame N is complete for frame N + 1, and a tile change in frame N is in the fields of frame N + 1, as Shard's
 * {@code PATH_FLUSH} has them. {@link #beforeFrame()} lifts the 100 ms wall-clock throttle on refreshes
 * ({@code Pathfinder.refreshIntervalMs}), so every frame with a tile change refreshes.
 *
 * <p>One more pin, to ARCHITECTURE.md §9's fresh fields ({@link #freshRings()}): vanilla repacks a changed tile alone
 * and leaves its neighbours' bits stale, so a destroyed wall's neighbours keep {@code nearSolid}; Shard repacks the
 * 3×3 ring. With the pin, vanilla repacks the ring too, keeping each unchanged tile's sampled health as Shard's view
 * does.
 */
public final class VanillaPathfinder{
    private static final Field THREAD, QUEUE, THREAD_LIST, LAST_REFRESH, DIRTY, FRONTIER, TILES, CACHE;
    private static final Method UPDATE_TARGETS, UPDATE_FRONTIER;
    private static boolean ringsPinned;

    static{
        try{
            THREAD = open(Pathfinder.class.getDeclaredField("thread"));
            QUEUE = open(Pathfinder.class.getDeclaredField("queue"));
            THREAD_LIST = open(Pathfinder.class.getDeclaredField("threadList"));
            LAST_REFRESH = open(Pathfinder.class.getDeclaredField("lastRefreshTime"));
            DIRTY = open(Pathfinder.Flowfield.class.getDeclaredField("dirty"));
            FRONTIER = open(Pathfinder.Flowfield.class.getDeclaredField("frontier"));
            TILES = open(Pathfinder.class.getDeclaredField("tiles"));
            CACHE = open(Pathfinder.class.getDeclaredField("cache"));
            UPDATE_TARGETS = open(Pathfinder.class.getDeclaredMethod("updateTargets", Pathfinder.Flowfield.class));
            UPDATE_FRONTIER = open(Pathfinder.class.getDeclaredMethod("updateFrontier", Pathfinder.Flowfield.class, long.class));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private VanillaPathfinder(){
    }

    private static <T extends AccessibleObject> T open(T member){
        member.setAccessible(true);
        return member;
    }

    /** Stops the pathfinder thread (after a world load started it) and completes every field it holds. */
    public static void take(){
        try{
            var thread = (Thread)THREAD.get(pathfinder);
            if(thread != null){
                thread.interrupt();
                thread.join(10_000);
                if(thread.isAlive()) throw new IllegalStateException("vanilla's pathfinder thread didn't stop");
                THREAD.set(pathfinder, null);
            }
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        settle();
    }

    /**
     * After vanilla's own {@code TileChangeEvent} handler ({@code Pathfinder.updateTile}), repacks the 3×3 ring
     * around each tile it repacked; a ring tile that didn't change keeps the health byte it was packed with. Once per
     * JVM: the listener stays registered.
     */
    public static void freshRings(){
        if(ringsPinned) return;
        ringsPinned = true;
        Events.on(TileChangeEvent.class, event -> {
            if(state.isEditor()) return;
            int[] tiles = tiles();
            event.tile.getLinkedTiles(changed -> {
                for(int dy = -1; dy <= 1; dy++){
                    for(int dx = -1; dx <= 1; dx++){
                        if(dx == 0 && dy == 0) continue;
                        Tile other = world.tile(changed.x + dx, changed.y + dy);
                        if(other == null || other.array() >= tiles.length) continue;
                        int pos = other.array();
                        // the health term is sampled when a tile changes, not when its neighbour does
                        tiles[pos] = (pathfinder.packTile(other) & ~0xff) | (tiles[pos] & 0xff);
                    }
                }
            });
        });
    }

    /** Vanilla's packed path tiles. */
    public static int[] tiles(){
        try{
            return (int[])TILES.get(pathfinder);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** {@code team}'s enemy-core field for vanilla cost {@code cost}, or null if nobody asked for it. */
    public static Pathfinder.Flowfield field(int team, int cost){
        try{
            return ((Pathfinder.Flowfield[][][])CACHE.get(pathfinder))[team][cost][Pathfinder.fieldCore];
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** Lifts the refresh throttle for the coming frame. */
    public static void beforeFrame(){
        try{
            LAST_REFRESH.setLong(pathfinder, Long.MIN_VALUE / 2);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** One pass of the thread's loop, to completion: posted tasks, then each field's targets and frontier. */
    @SuppressWarnings("unchecked")
    public static void settle(){
        try{
            ((TaskQueue)QUEUE.get(pathfinder)).run();
            for(Pathfinder.Flowfield field : (Seq<Pathfinder.Flowfield>)THREAD_LIST.get(pathfinder)){
                if(DIRTY.getBoolean(field) && ((IntQueue)FRONTIER.get(field)).size == 0){
                    UPDATE_TARGETS.invoke(pathfinder, field);
                    DIRTY.setBoolean(field, false);
                }
                UPDATE_FRONTIER.invoke(pathfinder, field, -1L);
            }
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
