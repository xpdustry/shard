package com.xpdustry.shard.compat.v160.fog;

import arc.struct.Bits;
import arc.util.*;
import mindustry.game.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Vanilla's {@code FogControl} on the schedule Shard's {@code sim.fog.FogControl} keeps, so the two can be compared
 * frame by frame. Vanilla draws on two threads and gates a team's redraw on 40 ms of wall clock; here:
 * <ul>
 *   <li>the threads are never started: unstarted stand-ins sit in {@code staticFogThread} and
 *   {@code dynamicFogThread}, so {@code update()} only notifies nobody;</li>
 *   <li>the draws they would make ({@code updateStatic()}, {@code updateDynamic(cleared)}) run first thing in
 *   {@code Time.update()}, which {@code Logic.update} calls right after {@code fogControl.update()}: as if both threads
 *   finished before the frame went on, which is how Shard draws;</li>
 *   <li>before each frame, each team's {@code lastDynamicMs} says "40 ms passed" exactly when
 *   {@value #FRAMES_PER_REDRAW} frames passed since that team's last redraw (60 Hz:
 *   the first frame past 40 ms), and after the frame a changed value marks a redraw.</li>
 * </ul>
 */
final class VanillaFog{
    private static final long ALLOWED = 0L, WAITING = Long.MAX_VALUE;
    /** 40 ms at 60 frames a second: the first frame boundary past it is the third (50 ms). */
    static final int FRAMES_PER_REDRAW = 3;
    private static final Field FOG, STATIC_THREAD, DYNAMIC_THREAD, READ, STATIC_DATA, LAST_MS, UPDATED, STATIC_EVENTS, DYNAMIC_EVENTS,
        NOTIFY_STATIC, NOTIFY_DYNAMIC;
    private static final Method UPDATE_STATIC, UPDATE_DYNAMIC, WRITE;

    static{
        try{
            Class<?> control = mindustry.game.FogControl.class;
            Class<?> data = Class.forName("mindustry.game.FogControl$FogData");
            FOG = open(control.getDeclaredField("fog"));
            STATIC_THREAD = open(control.getDeclaredField("staticFogThread"));
            DYNAMIC_THREAD = open(control.getDeclaredField("dynamicFogThread"));
            READ = open(data.getDeclaredField("read"));
            STATIC_DATA = open(data.getDeclaredField("staticData"));
            LAST_MS = open(data.getDeclaredField("lastDynamicMs"));
            UPDATED = open(data.getDeclaredField("dynamicUpdated"));
            STATIC_EVENTS = open(control.getDeclaredField("staticEvents"));
            DYNAMIC_EVENTS = open(control.getDeclaredField("dynamicEvents"));
            NOTIFY_STATIC = open(control.getDeclaredField("notifyStatic"));
            NOTIFY_DYNAMIC = open(control.getDeclaredField("notifyDynamic"));
            UPDATE_STATIC = open(control.getDeclaredMethod("updateStatic"));
            UPDATE_DYNAMIC = open(control.getDeclaredMethod("updateDynamic", Bits.class));
            WRITE = open(control.getDeclaredMethod("write", DataOutput.class));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static <T extends AccessibleObject> T open(T member){
        member.setAccessible(true);
        return member;
    }

    /** Each team's last redraw, in this driver's frames. */
    private final long[] lastRedraw = new long[256];
    private final Bits cleared = new Bits(256);
    private long frame;

    VanillaFog(){
        Arrays.fill(lastRedraw, Long.MIN_VALUE / 2);
    }

    /** A world was loaded: vanilla's fog data is new, so every team's redraw is due, as on a fresh driver. */
    void loaded(){
        Arrays.fill(lastRedraw, Long.MIN_VALUE / 2);
    }

    /** Before {@code Vanilla.frame()}: the threads stay unstarted, the clock is pinned, and the draws are queued. */
    void before(){
        try{
            if(STATIC_THREAD.get(fogControl) == null) STATIC_THREAD.set(fogControl, new Thread(() -> {}, "StaticFogThread (never started)"));
            if(DYNAMIC_THREAD.get(fogControl) == null) DYNAMIC_THREAD.set(fogControl, new Thread(() -> {}, "DynamicFogThread (never started)"));
            Object[] fog = (Object[])FOG.get(fogControl);
            for(int team = 0; fog != null && team < fog.length; team++){
                if(fog[team] != null) LAST_MS.setLong(fog[team], frame - lastRedraw[team] >= FRAMES_PER_REDRAW ? ALLOWED : WAITING);
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        // first in Time.update(), which runs right after fogControl.update()
        Time.run(0f, this::draw);
        var runs = Time.getRuns();
        runs.insert(0, runs.pop());
    }

    /** After {@code Vanilla.frame()}: which teams redrew. */
    void after(){
        try{
            Object[] fog = (Object[])FOG.get(fogControl);
            for(int team = 0; fog != null && team < fog.length; team++){
                if(fog[team] == null) continue;
                long ms = LAST_MS.getLong(fog[team]);
                if(ms != ALLOWED && ms != WAITING) lastRedraw[team] = frame;
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        frame++;
    }

    private void draw(){
        try{
            UPDATE_STATIC.invoke(fogControl);
            UPDATE_DYNAMIC.invoke(fogControl, cleared);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** True if a team has a dynamic redraw pending ({@code FogData.dynamicUpdated}): a unit moved since its last one. */
    static boolean redrawPending(){
        try{
            Object[] fog = (Object[])FOG.get(fogControl);
            for(int team = 0; fog != null && team < fog.length; team++) if(fog[team] != null && UPDATED.getBoolean(fog[team])) return true;
            return false;
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /**
     * For unmodified vanilla (no {@link #before}): true once both its fog threads wait, nothing is queued for them, and
     * no team has a redraw pending. Only then do {@link #visible} and {@link #discovered} read the threads' last draw:
     * each thread takes its notify monitor to wait, after drawing (the dynamic one after its buffer swap), and this
     * takes both. A thread notified a moment ago may still show as waiting, so queued events mean "not yet", never
     * "lost"; the caller bounds the wait.
     */
    static boolean idle(){
        try{
            for(Field field : new Field[]{STATIC_THREAD, DYNAMIC_THREAD}){
                var thread = (Thread)field.get(fogControl);
                if(thread == null || !thread.isAlive()) throw new AssertionError("vanilla's fog threads aren't running: " + thread);
                if(thread.getState() != Thread.State.WAITING) return false;
            }
            synchronized(NOTIFY_STATIC.get(null)){
                // the static thread's last draw happens before this
            }
            synchronized(NOTIFY_DYNAMIC.get(null)){
                // and the dynamic thread's
            }
            if(redrawPending()) return false;
            var statics = (arc.struct.LongSeq)STATIC_EVENTS.get(fogControl);
            var dynamics = (arc.struct.LongSeq)DYNAMIC_EVENTS.get(fogControl);
            synchronized(statics){
                if(statics.size > 0) return false;
            }
            synchronized(dynamics){
                return dynamics.size == 0;
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** True if vanilla's fog runs on its own threads ({@code FogControl.update()} started them), not this driver's stand-ins. */
    static boolean threaded(){
        try{
            Object thread = STATIC_THREAD.get(fogControl);
            return thread != null && thread.getClass().getName().startsWith("mindustry.game.FogControl$");
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** A team's visible tiles, as set bits over the map, or null without data. */
    static BitSet visible(Team team){
        return bits(team, READ);
    }

    /** A team's discovered tiles, as {@link #visible}. */
    static BitSet discovered(Team team){
        return bits(team, STATIC_DATA);
    }

    private static BitSet bits(Team team, Field field){
        try{
            Object[] fog = (Object[])FOG.get(fogControl);
            if(fog == null || fog[team.id] == null) return null;
            Bits bits = (Bits)field.get(fog[team.id]);
            var out = new BitSet();
            int size = world.width() * world.height();
            for(int i = 0; i < size; i++) if(bits.get(i)) out.set(i);
            return out;
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /** {@code FogControl.write}: the {@code static-fog-data} chunk's body. */
    static byte[] chunk(){
        try{
            var bytes = new ByteArrayOutputStream();
            WRITE.invoke(fogControl, new DataOutputStream(bytes));
            return bytes.toByteArray();
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
