package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/**
 * Where a hosted map's ticks go, tick by tick: one map (system property {@code shard.profile.map}, default
 * archipelago) loaded as {@link Host} hosts it and run alone, empty, for {@code shard.profile.ticks} (6,000) ticks,
 * with its hash every {@link LifecyclePolicy#hashEvery} ticks as {@link ManagedWorld} records it. It prints the
 * slowest ticks with their phases, and the fork an autosave makes, every 600 ticks. A diagnosis tool, not a gate:
 * {@code scripts/vm-gradle.sh :server:test -Pshard.tags=slow --tests '*TickProfileTest'}.
 */
@Tag("slow")
class TickProfileTest{
    static final Phase[] PHASES = Phase.values();

    /** Wall nanoseconds per tick and phase. */
    static final class Probe implements TickProbe{
        final long[][] nanos;
        int tick = -1;
        Phase open;
        long since;

        Probe(int ticks){
            nanos = new long[ticks][PHASES.length];
        }

        @Override
        public void enter(Phase phase){
            long now = System.nanoTime();
            if(open != null) nanos[tick][open.ordinal()] += now - since;
            open = phase;
            since = now;
        }

        @Override
        public void exit(){
            if(open != null) nanos[tick][open.ordinal()] += System.nanoTime() - since;
            open = null;
        }
    }

    @Test
    void profile() throws Exception{
        String name = System.getProperty("shard.profile.map", "archipelago");
        int ticks = Integer.getInteger("shard.profile.ticks", 6_000);
        Epoch epoch = Epoch.vanilla();
        var reader = new MsavReader(epoch);
        Path none = Files.createDirectories(Path.of("build/tick-profile/maps"));
        MapCatalog maps = MapCatalog.load(Path.of(ServerTest.vanillaJar()), none, reader);
        MapCatalog.Entry map = maps.find(name).orElseThrow();
        var loader = new MapLoader(epoch, BuildingCodecs.builtIn());

        long loadStart = System.nanoTime();
        SaveFile save = reader.read(map.read());
        Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
        long seed = WorldId.of(name).hash();
        WorldConfig template = HostedSystems.template(epoch, seed, 256);
        var config = new WorldConfig(template.content(), save.map().width(), save.map().height(), seed, template.systems(), template.kinds(),
            template.inboxCapacity(), template.journal());
        World world = Host.load(loader, save, config, rules).world();
        long loadNanos = System.nanoTime() - loadStart;

        int hashEvery = LifecyclePolicy.DEFAULT.hashEvery();
        var probe = new Probe(ticks);
        long[] total = new long[ticks];
        var forks = new StringBuilder();
        for(int i = 0; i < ticks; i++){
            probe.tick = i;
            long start = System.nanoTime();
            world.tick(probe);
            if(world.ticks() % hashEvery == 0) world.hash();
            total[i] = System.nanoTime() - start;
            if(world.ticks() % 600 == 0){
                long forkStart = System.nanoTime();
                world.fork();
                forks.append(String.format(Locale.ROOT, " %d:%.1f", world.ticks(), (System.nanoTime() - forkStart) / 1e6));
            }
        }

        var out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%s %dx%d: loaded in %.0f ms, %d ticks, %d units at the end%n", name, save.map().width(),
            save.map().height(), loadNanos / 1e6, ticks, world.units().size()));
        long[] sorted = total.clone();
        Arrays.sort(sorted);
        out.append(String.format(Locale.ROOT, "tick ms: p50 %.2f, p99 %.2f, p999 %.2f, max %.2f%n", sorted[ticks / 2] / 1e6,
            sorted[ticks * 99 / 100] / 1e6, sorted[ticks * 999 / 1000] / 1e6, sorted[ticks - 1] / 1e6));
        out.append("fork ms at tick:").append(forks).append('\n');
        slowest(out, "slowest of the first 600", probe, total, 0, Math.min(600, ticks), 5);
        slowest(out, "slowest of all", probe, total, 0, ticks, 12);
        System.out.print(out);
        Path file = Path.of("build/tick-profile", name + "-" + System.currentTimeMillis() + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
    }

    /** The {@code count} slowest ticks in {@code [from, to)}, each with its three costliest phases. */
    private static void slowest(StringBuilder out, String title, Probe probe, long[] total, int from, int to, int count){
        out.append(title).append(":\n");
        Integer[] order = new Integer[to - from];
        for(int i = 0; i < order.length; i++) order[i] = from + i;
        Arrays.sort(order, (a, b) -> Long.compare(total[b], total[a]));
        for(int k = 0; k < Math.min(count, order.length); k++){
            int tick = order[k];
            long[] phases = probe.nanos[tick];
            Integer[] top = new Integer[PHASES.length];
            for(int p = 0; p < top.length; p++) top[p] = p;
            Arrays.sort(top, (a, b) -> Long.compare(phases[b], phases[a]));
            out.append(String.format(Locale.ROOT, "  tick %5d (world tick %5d): %7.2f ms =", tick, tick + 1, total[tick] / 1e6));
            for(int p = 0; p < 3; p++) out.append(String.format(Locale.ROOT, " %s %.2f", PHASES[top[p]], phases[top[p]] / 1e6));
            out.append('\n');
        }
    }
}
