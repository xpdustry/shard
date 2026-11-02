package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint size and time for a 500×500 world with 20,000 toy buildings, 1,000 units and 2,000 bullets (WAVES.md
 * W1.4), and the codec choice. Writes {@code build/reports/checkpoint-bench.txt}. Run with
 * {@code -Pshard.tags=slow --tests '*CheckpointBench*'}.
 */
@Tag("slow")
class CheckpointBench{
    static final int WARMUP = 5, RUNS = 15;

    @Test
    void measure() throws IOException{
        World world = big();
        var report = new StringBuilder();
        report.append(String.format("world: 500x500, %d buildings, %d units, %d bullets, %d journal entries%n",
            world.buildings().count(), world.units().size(), world.bullets().size(), world.journal().entries().size()));

        Capture capture = Checkpoint.capture(world);
        report.append(String.format("raw sections: %,d bytes %s%n", capture.rawBytes(), capture.sectionSizes()));
        report.append(String.format("capture on the tick thread: %s%n", time(() -> Checkpoint.capture(world))));
        report.append(String.format("fork on the tick thread (the alternative): %s%n", time(world::fork)));

        WorldHash hash = world.hash();
        var codecs = new LinkedHashMap<String, Compression>();
        codecs.put("raw", Compression.NONE);
        for(int level : new int[]{1, 3, 6, 9}) codecs.put("deflate " + level, Compression.deflate(level));
        for(var entry : codecs.entrySet()){
            byte[] file = capture.encode(entry.getValue());
            report.append(String.format("%-10s %,11d bytes (%5.1f%%)  encode %s  decode %s  restore %s%n", entry.getKey(), file.length,
                100.0 * file.length / capture.rawBytes(), time(() -> capture.encode(entry.getValue())),
                time(() -> Checkpoint.decode(file)), time(() -> Checkpoint.decode(file).restore(world.config()))));
            assertEquals(hash, Checkpoint.decode(file).restore(world.config()).hash());
        }
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "checkpoint-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** 8,000 counters, 4,000 2×2 links and 8,000 walls in rows, then 50 ticks so state isn't fresh. */
    static World big(){
        var config = new WorldConfig(ToyContent.INSTANCE, 500, 500, 99, Fixtures.config(99).systems(), Fixtures.config(99).kinds(),
            4096, new MemoryJournal());
        var world = new World(config);
        var rand = new Random(5);
        int placed = 0;
        for(int y = 1; y < 499 && placed < 20_000; y += 2){
            for(int x = 1; x < 499 && placed < 20_000; x += 2){
                var block = placed < 8000 ? ToyContent.COUNTER : placed < 12_000 ? ToyContent.LINK : ToyContent.WALL;
                if(block.size() == 2 && (x + 1 >= 499 || y + 1 >= 499)) continue;
                if(world.tiles().build(world.tiles().pos(x, y)) != -1) continue;
                Building building = world.buildings().create(block, 1 + rand.nextInt(3), x, y, rand.nextInt(4), Cause.SERVER);
                if(building instanceof Link link) link.target = world.buildings().at(world.tiles().pos(x - 2 < 1 ? 1 : x - 2, y));
                placed++;
            }
        }
        assertEquals(20_000, world.buildings().count());
        for(int i = 0; i < 1000; i++){
            Unit unit = Unit.create(world, ToyContent.DRONE, 1 + i % 3);
            unit.x = rand.nextFloat() * 4000f;
            unit.y = rand.nextFloat() * 4000f;
            unit.controller = new Follow(world.buildings().at(rand.nextInt(world.tiles().size())));
            unit.add();
        }
        for(int i = 0; i < 2000; i++){
            world.bullets().add(world.ids(), i % 5, 1, rand.nextFloat() * 4000f, rand.nextFloat() * 4000f, 1f, -1f, 1e9f, 10f, null);
        }
        for(int i = 0; i < 50; i++) world.tick();
        return world;
    }

    /** The median and the best of {@link #RUNS} runs after {@link #WARMUP}, in milliseconds. */
    static String time(Supplier<?> action){
        for(int i = 0; i < WARMUP; i++) action.get();
        var times = new double[RUNS];
        for(int i = 0; i < RUNS; i++){
            long start = System.nanoTime();
            action.get();
            times[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(times);
        return String.format("%.2f ms (best %.2f)", times[RUNS / 2], times[0]);
    }
}
