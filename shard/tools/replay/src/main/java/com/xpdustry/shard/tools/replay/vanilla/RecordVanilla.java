package com.xpdustry.shard.tools.replay.vanilla;

import arc.math.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;
import com.xpdustry.shard.tools.replay.view.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Plays a scripted match on vanilla v160.7 and writes its view stream. With {@code --measure} it also measures, on
 * the same match, the other two ways to record it (note 37 §1):
 * <ul>
 *   <li>the <b>viewer projection</b>: every packet vanilla's server sends a full-vision client, at vanilla's
 *   cadence (state and entity snapshots every 200 ms, block snapshots every 6 s, health every 0.5 s, plus the Calls
 *   the match makes), raw and deflated, and the world stream a late joiner or a seek needs;</li>
 *   <li>the <b>input log</b>: the {@code clientSnapshot} packets {@code --players} players would send (one per
 *   66 ms, as {@code NetClient.sync}), built from real units of the match, raw and deflated; plus a keyframe
 *   ({@code .msav}) a minute.</li>
 * </ul>
 * {@code --dump <dir>} also keeps the raw projection ({@code projection.bin}) and its byte offset every 600 ticks
 * ({@code projection.idx}), so {@code replay/spikes/sizes/compress.mjs} can compare compressors like for like.
 *
 * <pre>
 * --map triad --mode pvp|survival --minutes 10 --out ../replay/recordings/triad.sview
 * --frame-every 3 --keyframe-every 600 --measure --players 8 --squads 20 --seed 1 --team-ai build,rts|none --dump dir
 * </pre>
 */
public final class RecordVanilla{
    public static void main(String[] args) throws IOException{
        var options = Args.parse(args);
        Vanilla.boot();
        if(options.has("list")){
            // which maps have cores for two or more teams, and so can host PvP
            for(var map : maps.all()){
                Vanilla.host(map.file.nameWithoutExtension(), Gamemode.survival);
                var teams = new ArrayList<String>();
                for(var data : state.teams.getActive()) if(!data.cores.isEmpty()) teams.add(data.team.name + "×" + data.cores.size);
                System.out.println(map.file.nameWithoutExtension() + " " + world.width() + "x" + world.height() + " cores " + teams);
            }
            return;
        }
        Gamemode mode = Gamemode.valueOf(options.get("mode", "pvp"));
        String mapName = options.get("map", mode == Gamemode.pvp ? "triad" : "serpulo/groundZero");
        Vanilla.host(mapName, mode);
        long seed = options.num("seed", 1);
        Mathf.rand.setSeed(seed);
        var script = new Script(mode, options.num("squads", 20), new Rand(seed), options.get("team-ai", "build,rts"));
        script.setup();

        var fanout = new Fanout(new VanillaSource());
        int keyframes = options.num("keyframe-every", 600);
        var writer = new ViewWriter(fanout.view(), new ViewWriter.Options(options.num("frame-every", 3), keyframes, 6));
        // --compare 1,6: the same match at other frame rates, for the size table
        var others = new TreeMap<Integer, ViewWriter>();
        for(String every : options.get("compare", "").split(",")){
            if(!every.isBlank()) others.put(Integer.parseInt(every.strip()), new ViewWriter(fanout.view(), new ViewWriter.Options(Integer.parseInt(every.strip()), keyframes, 6)));
        }
        Measure measure = options.has("measure") ? new Measure(options.num("players", 8), options.has("dump") ? Path.of(options.get("dump", "")) : null) : null;
        long frames = Math.round(options.dbl("minutes", 5) * 3600);
        Path out = Path.of(options.get("out", "../replay/recordings/" + mapName.replace('/', '-') + ".sview"));
        System.out.println("recording " + state.map.plainName() + " (" + world.width() + "x" + world.height() + ", " + mode.name() + ") for " + frames + " ticks to " + out);

        long began = System.nanoTime();
        Runnable loop = () -> {
            writer.sample();
            others.values().forEach(ViewWriter::sample);
            for(long frame = 0; frame < frames; frame++){
                script.step(frame);
                if(measure != null) measure.beforeFrame();
                Vanilla.frame();
                writer.sample();
                others.values().forEach(ViewWriter::sample);
                if(measure != null) measure.afterFrame(frame);
                if(frame % 3600 == 3599){
                    System.out.printf("  minute %d: %d units, %d bullets, %d buildings, wave %d, %.1f s%n", (frame + 1) / 3600, Groups.unit.size(),
                        Groups.bullet.size(), Groups.build.size(), state.wave, (System.nanoTime() - began) / 1e9);
                }
                if(state.gameOver){
                    System.out.println("  game over at tick " + frame);
                    break;
                }
            }
        };
        if(measure != null) Vanilla.net(NetMode.server, loop);
        else loop.run();
        double seconds = (System.nanoTime() - began) / 1e9;

        byte[] file = writer.finish();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, file);
        long ticks = writer.ticks();
        System.out.printf("wrote %s: %,d bytes for %d ticks (%.1f min), %.2f ms per tick%n", out, file.length, ticks, ticks / 3600.0, seconds * 1000 / Math.max(1, ticks));
        System.out.print("view stream: " + writer.stats().summary(ticks));
        others.forEach((every, other) -> {
            other.finish();
            System.out.print("view stream at " + (60 / every) + " frames/s: " + other.stats().summary(other.ticks()));
        });
        if(measure != null) System.out.print(measure.summary(ticks));
    }

    /** The scripted match: turrets at every core, squads of units for every team at intervals, team AI. */
    static final class Script{
        final Gamemode mode;
        final int squadSeconds;
        final Rand rand;
        final List<Team> teams = new ArrayList<>();
        /** Vanilla's team AIs to turn on in PvP: {@code build} (BaseBuilderAI), {@code rts} (RtsAI), both or none. */
        final String teamAi;

        Script(Gamemode mode, int squadSeconds, Rand rand, String teamAi){
            this.mode = mode;
            this.squadSeconds = squadSeconds;
            this.rand = rand;
            this.teamAi = teamAi;
        }

        void setup(){
            state.rules.pvpAutoPause = false;
            state.rules.waitEnemies = false;
            for(var data : state.teams.getActive()){
                if(data.cores.isEmpty()) continue;
                teams.add(data.team);
            }
            if(mode == Gamemode.pvp){
                state.rules.waves = false;
            }else{
                state.rules.waveTimer = true;
                state.rules.waveSpacing = 30f * 60f;
                state.wavetime = 10f * 60f;
                teams.remove(state.rules.waveTeam);
            }
            for(Team team : teams){
                var rules = state.rules.teams.get(team);
                rules.cheat = true;
                rules.infiniteResources = true;
                rules.buildAi = mode == Gamemode.pvp && teamAi.contains("build");
                rules.rtsAi = mode == Gamemode.pvp && teamAi.contains("rts");
                for(CoreBuild core : team.data().cores) fortify(core);
            }
            Vanilla.runPosted();
            System.out.println("teams with cores: " + teams);
        }

        /** A ring of turrets around a core. */
        void fortify(CoreBuild core){
            Block[] turrets = {Blocks.duo, Blocks.hail, Blocks.lancer, Blocks.scatter, Blocks.arc, Blocks.duo, Blocks.salvo, Blocks.wave};
            int placed = 0;
            for(int i = 0; i < 24 && placed < 10; i++){
                float angle = i * 360f / 24f;
                Block block = turrets[placed % turrets.length];
                int tx = core.tile.x + Math.round(Angles.trnsx(angle, 9f)), ty = core.tile.y + Math.round(Angles.trnsy(angle, 9f));
                if(Build.validPlace(block, core.team, tx, ty, 0, false)){
                    world.tile(tx, ty).setBlock(block, core.team, 0);
                    placed++;
                }
            }
            for(int dx = -6; dx <= 6; dx += 12){
                for(int dy = -3; dy <= 3; dy++){
                    int tx = core.tile.x + dx, ty = core.tile.y + dy;
                    if(Build.validPlace(Blocks.titaniumWall, core.team, tx, ty, 0, false)) world.tile(tx, ty).setBlock(Blocks.titaniumWall, core.team, 0);
                }
            }
        }

        void step(long frame){
            if(squadSeconds <= 0 || frame % (squadSeconds * 60L) != 0) return;
            int minute = (int)(frame / 3600);
            UnitType[] ground = {UnitTypes.dagger, UnitTypes.dagger, UnitTypes.mace, UnitTypes.nova, UnitTypes.crawler, UnitTypes.fortress, UnitTypes.pulsar, UnitTypes.atrax};
            UnitType[] air = {UnitTypes.flare, UnitTypes.flare, UnitTypes.horizon, UnitTypes.poly, UnitTypes.zenith};
            for(Team team : teams){
                CoreBuild core = team.core();
                if(core == null || team.data().units.size > 140) continue;
                int count = 6 + Math.min(minute, 6);
                for(int i = 0; i < count; i++){
                    UnitType[] pool = i % 3 == 2 ? air : ground;
                    UnitType type = pool[rand.random(0, Math.min(pool.length - 1, 1 + minute))];
                    float angle = rand.random(360f), dst = rand.random(20f, 48f);
                    Unit unit = type.spawn(team, core.x + Angles.trnsx(angle, dst), core.y + Angles.trnsy(angle, dst));
                    unit.rotation = angle;
                    // since v146 a player team's units idle under CommandAI until ordered; order them onto the enemy
                    CoreBuild enemy = enemyCore(team, core);
                    if(enemy != null && !team.rules().rtsAi && unit.controller() instanceof mindustry.ai.types.CommandAI ai){
                        ai.commandPosition(new arc.math.geom.Vec2(enemy.x + rand.range(40f), enemy.y + rand.range(40f)));
                    }
                }
            }
            Vanilla.runPosted();
        }
    }

    /** The nearest core of another team. */
    static CoreBuild enemyCore(Team team, CoreBuild from){
        CoreBuild best = null;
        for(var data : state.teams.getActive()){
            if(data.team == team) continue;
            for(CoreBuild core : data.cores){
                if(best == null || core.dst(from) < best.dst(from)) best = core;
            }
        }
        return best;
    }

    /** The viewer projection and the input log of the same match. */
    static final class Measure{
        final int players;
        final TreeMap<String, long[]> projection = new TreeMap<>(); // packet -> {count, bytes}
        final CountingDeflater projectionDeflated = new CountingDeflater(), inputDeflated = new CountingDeflater();
        long projectionBytes, inputBytes, inputPackets;
        final List<Integer> worldStreams = new ArrayList<>(), saves = new ArrayList<>();
        final Field health;
        int snapshotId;
        /** With {@code --dump <dir>}: the raw projection bytes, and their offset at every 600th tick, for other compressors. */
        final OutputStream dump;
        final List<Long> chunks = new ArrayList<>();
        final Path dumpDir;

        Measure(int players, Path dumpDir){
            this.players = players;
            this.dumpDir = dumpDir;
            try{
                if(dumpDir != null) Files.createDirectories(dumpDir);
                dump = dumpDir == null ? null : new BufferedOutputStream(Files.newOutputStream(dumpDir.resolve("projection.bin")), 1 << 20);
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
            try{
                health = netServer.getClass().getDeclaredField("buildHealthChanged");
                health.setAccessible(true);
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }

        void beforeFrame(){
            // vanilla's own snapshot timer reads the wall clock; this measure sends them by tick instead
            netServer.snapshotSyncTime = Long.MAX_VALUE / 4;
        }

        void afterFrame(long frame){
            try{
                if(frame % 12 == 0){
                    netServer.writeStateSnapshot();
                    netServer.writeEntitySnapshotsAll();
                }
                if(frame % 360 == 0) netServer.writeBlockSnapshots();
                if(frame % 30 == 0) flushHealth();
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
            if(frame % 600 == 0) chunks.add(projectionBytes);
            for(var sent : Vanilla.captured()){
                if(sent.frame() == null) continue;
                byte[] bytes = sent.frame().serialized();
                if(dump != null){
                    try{
                        dump.write(bytes);
                    }catch(IOException e){
                        throw new UncheckedIOException(e);
                    }
                }
                projection.computeIfAbsent(sent.frame().packet(), k -> new long[2])[0]++;
                projection.get(sent.frame().packet())[1] += bytes.length;
                projectionBytes += bytes.length;
                projectionDeflated.write(bytes);
            }
            Vanilla.captured().clear();
            if(frame % 4 == 0) inputs();
            if(frame % 3600 == 0){
                Player viewer = Player.create();
                viewer.name = "viewer";
                viewer.team(Team.derelict);
                worldStreams.add(Golden.worldStream(viewer).deflated().length);
                var save = new ByteArrayOutputStream();
                SaveIO.write(new arc.util.io.FastDeflaterOutputStream(save), new SaveOptions()); // as SaveIO.write(Fi) writes .msav
                saves.add(save.size());
            }
        }

        void flushHealth(){
            try{
                var changed = (IntSet)health.get(netServer);
                if(changed.size == 0) return;
                var seq = new IntSeq();
                var iter = changed.iterator();
                while(iter.hasNext){
                    int pos = iter.next();
                    var build = world.build(pos);
                    if(build != null) seq.add(pos, Float.floatToRawIntBits(build.health));
                }
                changed.clear();
                if(seq.size > 0) Call.buildHealthUpdate(seq);
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }

        /** {@code clientSnapshot}s for the first {@code players} units by id, as their players' clients would send them. */
        void inputs(){
            var units = new ArrayList<Unit>();
            Groups.unit.each(units::add);
            units.sort(Comparator.comparingInt(u -> u.id));
            int count = Math.min(players, units.size());
            for(int i = 0; i < count; i++){
                Unit unit = units.get(i);
                int id = snapshotId++;
                List<Wire.Frame> sent = Golden.calls(NetMode.client, () -> Call.clientSnapshot(id, unit.id, false, unit.x, unit.y, unit.aimX, unit.aimY,
                    unit.rotation, unit instanceof Mechc mech ? mech.baseRotation() : 0f, unit.vel.x, unit.vel.y, null, false, unit.isShooting,
                    false, false, null, 0, null, unit.x, unit.y, 1280f / 4f, 720f / 4f));
                for(Wire.Frame frame : sent){
                    inputBytes += frame.serialized().length;
                    inputDeflated.write(frame.serialized());
                    inputPackets++;
                }
            }
            Vanilla.captured().clear();
        }

        String summary(long ticks) throws IOException{
            if(dump != null){
                dump.close();
                Files.writeString(dumpDir.resolve("projection.idx"), chunks.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            }
            double hours = ticks / 60.0 / 3600.0;
            var out = new StringBuilder();
            out.append(String.format("viewer projection (vanilla packets to one full-vision client): %,d B raw = %.1f MB/h, deflated %.1f MB/h%n",
                projectionBytes, projectionBytes / 1048576.0 / hours, projectionDeflated.finish() / 1048576.0 / hours));
            projection.forEach((packet, v) -> out.append(String.format("  %-40s %,8d packets %,12d B  %.1f MB/h%n", packet, v[0], v[1], v[1] / 1048576.0 / hours)));
            out.append(String.format("world stream (seek / late join), deflated, each minute: %s B%n", worldStreams));
            out.append(String.format("keyframe (.msav), each minute: %s B%n", saves));
            double playerHours = Math.max(1, inputPackets) / (15.0 * 3600.0); // one packet per player every 4 ticks
            out.append(String.format("input log (%d players' clientSnapshot at 15 Hz): %,d packets, %,d B raw = %.2f MB per player-hour, deflated %.2f MB per player-hour%n",
                players, inputPackets, inputBytes, inputBytes / 1048576.0 / playerHours, inputDeflated.finish() / 1048576.0 / playerHours));
            return out.toString();
        }
    }

    /** Deflates a stream and counts the compressed bytes, keeping none of them. */
    static final class CountingDeflater{
        final Deflater deflater = new Deflater(6, true);
        final byte[] buffer = new byte[1 << 16];
        long bytes;
        boolean finished;

        void write(byte[] data){
            deflater.setInput(data);
            while(!deflater.needsInput()) bytes += deflater.deflate(buffer);
        }

        long finish(){
            if(!finished){
                deflater.finish();
                while(!deflater.finished()) bytes += deflater.deflate(buffer);
                finished = true;
            }
            return bytes;
        }
    }

    /** {@code --key value} and {@code --flag} arguments. */
    record Args(Map<String, String> values){
        static Args parse(String[] args){
            var values = new HashMap<String, String>();
            for(int i = 0; i < args.length; i++){
                if(!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected " + args[i]);
                String key = args[i].substring(2);
                if(i + 1 < args.length && !args[i + 1].startsWith("--")) values.put(key, args[++i]);
                else values.put(key, "true");
            }
            return new Args(values);
        }

        boolean has(String key){
            return values.containsKey(key);
        }

        String get(String key, String fallback){
            return values.getOrDefault(key, fallback);
        }

        int num(String key, int fallback){
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
        }

        double dbl(String key, double fallback){
            return values.containsKey(key) ? Double.parseDouble(values.get(key)) : fallback;
        }
    }
}
