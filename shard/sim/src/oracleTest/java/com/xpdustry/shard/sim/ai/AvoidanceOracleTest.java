package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.path.AvoidanceProcess;
import com.xpdustry.shard.sim.team.Team;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP54): the avoidance grids of two ground teams. Crux and blue are both AI teams in wave mode; their daggers and
 * crawlers walk on a core behind a wall of large titanium walls across the whole map, which the field leads through,
 * so they press against it, get stuck ({@code GroundAI}'s stuck time), and from then on pathfind with avoidance.
 * Every frame for {@value #FRAMES} frames, both of Shard's buffers, which one is read and the swap and active flags
 * equal vanilla's {@code AvoidanceProcess}, and so does the whole world ({@link AiOracle#same}). The fixture then
 * asserts that both teams stamped and that the flare, on the flying layer, didn't. (No atrax: its slag makes puddles,
 * which aren't ported.)
 */
class AvoidanceOracleTest{
    static final int SIZE = 40, FRAMES = 900, BLUE = 5;
    private static final Field V_BUFFER1, V_BUFFER2, V_READ, V_SWAP, V_ACTIVE, S_BUFFER1, S_BUFFER2, S_READ, S_SWAP, S_ACTIVE;

    static{
        try{
            Class<?> vanilla = mindustry.async.AvoidanceProcess.class;
            V_BUFFER1 = open(vanilla, "buffer1");
            V_BUFFER2 = open(vanilla, "buffer2");
            V_READ = open(vanilla, "avoidance");
            V_SWAP = open(vanilla, "swap");
            V_ACTIVE = open(vanilla, "active");
            S_BUFFER1 = open(AvoidanceProcess.class, "buffer1");
            S_BUFFER2 = open(AvoidanceProcess.class, "buffer2");
            S_READ = open(AvoidanceProcess.class, "reading");
            S_SWAP = open(AvoidanceProcess.class, "swap");
            S_ACTIVE = open(AvoidanceProcess.class, "active");
        }catch(NoSuchFieldException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Class<?> owner, String name) throws NoSuchFieldException{
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void twoGroundTeamsStampVanillasGrids(){
        Vanilla.boot();
        try(var o = new AiOracle(SIZE, "stone")){
            o.place("core-shard", Team.SHARDED, 34, 20);
            for(int y = 0; y < SIZE; y += 2) o.place("titanium-wall-large", Team.SHARDED, 26, y);
            o.preloadPaths();
            for(int i = 0; i < 4; i++) o.spawn("dagger", Team.CRUX, (14 + i) * 8f, (14 + i * 2) * 8f);
            o.spawn("crawler", Team.CRUX, 12 * 8f, 22 * 8f);
            o.spawn("flare", Team.CRUX, 2 * 8f, 30 * 8f);
            for(int i = 0; i < 3; i++) o.spawn("dagger", BLUE, (13 + i * 2) * 8f, 4 * 8f);
            o.spawn("crawler", BLUE, 18 * 8f, 6 * 8f);
            o.same("spawned");

            AvoidanceProcess shard = o.world.part(AvoidanceProcess.class);
            var stampedBy = new TreeSet<Integer>();
            for(int frame = 0; frame < FRAMES; frame++){
                o.frame();
                String at = "frame " + frame;
                sameGrids(shard, at);
                o.same(at);
                int[] read = (int[])get(V_READ, avoidance);
                if(read == null) continue;
                for(int cell : read) if(cell != 0) stampedBy.add(Integer.MAX_VALUE - cell);
            }
            var teams = new TreeSet<String>();
            for(int id : stampedBy){
                var unit = mindustry.gen.Groups.unit.getByID(id);
                if(unit != null) teams.add(unit.team.name + " " + unit.type.name);
            }
            System.out.println("avoidance: " + FRAMES + " frames, units stamped " + stampedBy + " (alive: " + teams + ")");
            assertTrue(teams.stream().anyMatch(t -> t.startsWith("crux ")), "crux stamped: " + teams);
            assertTrue(teams.stream().anyMatch(t -> t.startsWith("blue ")), "blue stamped: " + teams);
            assertTrue(teams.stream().noneMatch(t -> t.endsWith(" flare")), "only ground units stamp: " + teams);
        }
    }

    void sameGrids(AvoidanceProcess shard, String at){
        boolean active = (boolean)get(V_ACTIVE, avoidance);
        assertEquals(active, get(S_ACTIVE, shard), at + ": active");
        assertEquals(get(V_SWAP, avoidance), get(S_SWAP, shard), at + ": swap");
        int[] one = (int[])get(V_BUFFER1, avoidance), two = (int[])get(V_BUFFER2, avoidance), read = (int[])get(V_READ, avoidance);
        int reading = read == null ? 0 : read == one ? 1 : read == two ? 2 : -1;
        assertEquals(reading, get(S_READ, shard), at + ": the buffer read");
        sameGrid(one, (int[])get(S_BUFFER1, shard), at + ": buffer 1");
        sameGrid(two, (int[])get(S_BUFFER2, shard), at + ": buffer 2");
    }

    static void sameGrid(int[] vanilla, int[] shard, String at){
        if(vanilla == null || shard == null){
            assertEquals(vanilla == null, shard == null, at);
            return;
        }
        var diffs = new ArrayList<String>();
        for(int pos = 0; pos < vanilla.length && diffs.size() < 8; pos++){
            if(vanilla[pos] != shard[pos]) diffs.add(pos % SIZE + "," + pos / SIZE + " " + vanilla[pos] + " vs " + shard[pos]);
        }
        assertEquals(List.of(), diffs, at);
    }

    static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }
}
