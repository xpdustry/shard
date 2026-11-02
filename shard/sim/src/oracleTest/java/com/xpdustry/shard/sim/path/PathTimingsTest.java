package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.ai.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Timings on the 18 default maps, printed as a markdown table. The full builds are Dial's, one per class, for the
 * wave team, next to vanilla's full refresh of its ground field (what vanilla runs after every tile change) and a heap
 * build of an RTS field. The flushes place, then remove, one wall of the wave team on a tile its ground field reaches:
 * impassable for its own fields, so each one invalidates a subtree. Each flush repairs the four wave-team fields. Every
 * repaired field is checked against a rebuild at the end.
 */
@Tag("slow")
class PathTimingsTest{
    static final int BUILD_WARMUP = 5, BUILD_SAMPLES = 21, EDIT_WARMUP = 10, EDIT_SAMPLES = 60;

    @BeforeAll
    static void header(){
        System.out.println("| map | size | dial ground | dial legs | dial naval | dial hover | vanilla ground | rts ground | place p50 / p95 | remove p50 / p95 | invalidated / repair |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|");
    }

    static List<String> maps(){
        return VanillaFieldsTest.MAPS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void timings(String map){
        Vanilla.host(map);
        VanillaPaths.repack();
        World shard = VanillaPaths.mirror();
        PathService paths = paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        FieldEngine engine = paths.engine();
        int team = state.rules.waveTeam.id, size = paths.grid().size;
        var row = new StringBuilder("| " + map + " | " + world.width() + "x" + world.height() + " |");

        for(PathCost cls : VanillaPaths.COSTS.keySet()){
            var field = new Flowfield(team, cls, Flowfield.ENEMY_CORES, size);
            field.targets = paths.targetsOf(shard, field);
            row.append(' ').append(ms(median(time(() -> engine.build(field), BUILD_WARMUP, BUILD_SAMPLES)))).append(" |");
        }
        var vanilla = VanillaPaths.build(team, Pathfinder.costGround).field();
        row.append(' ').append(ms(median(time(() -> VanillaPaths.refresh(vanilla), BUILD_WARMUP, BUILD_SAMPLES)))).append(" |");
        int core = state.rules.defaultTeam.core() == null ? size / 2 : state.rules.defaultTeam.core().tile.array();
        var rts = new Flowfield(team, PathCost.GROUND, core, size);
        rts.targets.add(core);
        row.append(' ').append(ms(median(time(() -> engine.build(rts), BUILD_WARMUP, BUILD_SAMPLES)))).append(" |");

        var handles = new ArrayList<Integer>();
        for(PathCost cls : VanillaPaths.COSTS.keySet()) handles.add(paths.field(team, cls));
        shard.tick();
        int ground = handles.getFirst();
        var open = new ArrayList<Integer>();
        for(int pos = 0; pos < size; pos++){
            if(paths.distance(ground, pos) > 0 && shard.tiles().block(pos) == 0 && shard.tiles().build(pos) == -1) open.add(pos);
        }
        assertFalse(open.isEmpty(), map + ": no open tile on the ground field");
        var rand = new Rand(map.hashCode());
        long[] place = new long[EDIT_SAMPLES], remove = new long[EDIT_SAMPLES];
        long repairs = engine.repairs, invalidated = engine.invalidated;
        for(int i = -EDIT_WARMUP; i < EDIT_SAMPLES; i++){
            int pos = open.get(rand.random(open.size() - 1));
            Building wall = place(shard, "copper-wall", team, shard.tiles().x(pos), shard.tiles().y(pos));
            assertNotNull(wall);
            long start = System.nanoTime();
            shard.tick();
            long placed = System.nanoTime() - start;
            remove(shard, wall);
            start = System.nanoTime();
            shard.tick();
            long removed = System.nanoTime() - start;
            if(i < 0){
                repairs = engine.repairs;
                invalidated = engine.invalidated;
                continue;
            }
            place[i] = placed;
            remove[i] = removed;
        }
        Arrays.sort(place);
        Arrays.sort(remove);
        row.append(' ').append(ms(place[EDIT_SAMPLES / 2])).append(" / ").append(ms(place[EDIT_SAMPLES * 95 / 100])).append(" |");
        row.append(' ').append(ms(remove[EDIT_SAMPLES / 2])).append(" / ").append(ms(remove[EDIT_SAMPLES * 95 / 100])).append(" |");
        long done = engine.repairs - repairs;
        row.append(' ').append(done == 0 ? "-" : String.valueOf((engine.invalidated - invalidated) / done)).append(" |");
        for(int handle : handles) References.checkField(paths, paths.get(handle), map + " " + paths.get(handle).cls);
        System.out.println(row);
    }

    static long[] time(Runnable run, int warmup, int samples){
        for(int i = 0; i < warmup; i++) run.run();
        long[] out = new long[samples];
        for(int i = 0; i < samples; i++){
            long start = System.nanoTime();
            run.run();
            out[i] = System.nanoTime() - start;
        }
        return out;
    }

    static long median(long[] values){
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static String ms(long nanos){
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1e6);
    }
}
