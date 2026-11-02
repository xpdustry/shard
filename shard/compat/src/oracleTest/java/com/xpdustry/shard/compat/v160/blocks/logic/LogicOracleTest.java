package com.xpdustry.shard.compat.v160.blocks.logic;

import arc.struct.*;
import arc.util.io.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.LAccess;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.world.blocks.logic.MemoryBlock.*;
import mindustry.world.blocks.logic.MessageBlock.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP67): processors, memory, messages and switches run as vanilla's. Each fixture is built in a vanilla world
 * ({@link Sim}) and in a Shard one, edited and run in step, and compared after every frame: every building's team,
 * enabled flag, health and items; memory contents and message text; every processor's variables (in vanilla's
 * {@code executor.vars} order), counter, accumulator, links and text buffer, every frame or, so that Shard's
 * processors park in between, every {@code every} frames; the tiles a world processor edits. Then every building's
 * save chunk, but for unported blocks. Then vanilla saves its world, both load that save ({@code LogicBuild.read},
 * {@code afterReadAll}), their chunks are compared again, and both run on, compared as before.
 * {@link #callsAreVanillasInOrder()} compares the Calls a world processor makes, frame by frame.
 *
 * <p>The logic {@code op rand} generator is seeded 0 in both, as the oracle seeds {@code GlobalVars.rand}.
 */
public class LogicOracleTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    /** Frames both run after loading vanilla's save of the fixture. */
    static final int AFTER_LOAD = 150;
    /** The Calls a processor makes, by packet id. */
    static final Set<Integer> LOGIC_CALLS = Set.of(Packets.SyncVariable.ID, Packets.SetFlag.ID, Packets.SetTile.ID, Packets.SetFloor.ID,
        Packets.SetOverlay.ID, Packets.SetMapArea.ID);

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** M0: a micro processor's {@code control enabled} turns a router off and on again on vanilla's ticks. */
    @Test
    void controlEnabledTogglesARouterOnVanillasTicks(){
        var run = new Run(16, 10);
        run.place("item-source", 1, 2);
        for(int x = 2; x <= 4; x++) run.place("router", x, 2);
        run.place("item-void", 5, 2);
        run.configure(1, 2, "copper");
        run.processor("micro-processor", 3, 4, """
            control enabled router1 0
            wait 0.5
            control enabled router1 1
            wait 0.25
            """, 3, 2);
        run.run(600, 1);
        assertTrue(run.toggles > 10, "the router went off and on: " + run.toggles);
    }

    /** As {@link #controlEnabledTogglesARouterOnVanillasTicks}, but unlinked mid-wait, which re-enables the router. */
    @Test
    void unlinkingReenablesWhatTheProcessorDisabled(){
        var run = new Run(16, 10);
        run.place("item-source", 1, 2);
        run.place("router", 2, 2);
        run.place("item-void", 3, 2);
        run.configure(1, 2, "copper");
        run.processor("micro-processor", 2, 4, """
            control enabled router1 0
            wait 2
            """, 2, 2);
        run.at(40, () -> run.link(2, 4, 2, 2));
        run.at(90, () -> run.link(2, 4, 2, 2));
        run.at(200, () -> run.remove(2, 4));
        run.run(300, 1);
    }

    /** Memory cells and banks, a message and a switch, with numbers and objects, a generator, waits and text. */
    @Test
    void memoryMessagesAndSwitches(){
        var run = new Run(16, 16);
        run.place("memory-cell", 8, 8);
        run.place("memory-bank", 8, 5);
        run.place("message", 4, 6);
        run.place("switch", 4, 8);
        run.processor("logic-processor", 6, 6, """
            loop:
            sensor on switch1 @enabled
            op add i i 1
            op rand r 100
            write r bank1 i
            read back bank1 i
            write @this cell1 0
            write "text" cell1 1
            write @copper cell1 2
            write r cell1 3
            read obj cell1 0
            read txt cell1 1
            read far cell1 70
            print "i="
            print i
            print " r="
            print back
            printflush message1
            sensor buf message1 @bufferSize
            sensor cap bank1 @memoryCapacity
            op mod m i 7
            jump skip notEqual m 0
            op equal off on false
            control enabled switch1 off
            skip:
            wait 0.05
            jump loop lessThan i 600
            stop
            """, 8, 8, 8, 5, 4, 6, 4, 8);
        run.processor("micro-processor", 10, 10, """
            read v cell1 3
            op add total total v
            write total bank1 511
            """, 8, 8, 8, 5);
        run.at(100, () -> run.toggle(4, 8, true));
        run.at(260, () -> run.configure(4, 6, "hello"));
        run.run(400, 1);
    }

    /** {@link #memoryMessagesAndSwitches()}' kind of program, compared every 97 frames, so Shard's processors park. */
    @Test
    void parkedProcessorsCatchUpExactly(){
        var run = new Run(16, 16);
        run.place("memory-cell", 8, 8);
        run.processor("logic-processor", 6, 6, """
            op rand r 10
            op add total total r
            write total cell1 0
            wait 0.37
            op add n n 1
            setrate 3
            """, 8, 8);
        run.processor("micro-processor", 10, 10, """
            read t cell1 0
            wait 1.13
            print t
            """, 8, 8);
        run.processor("hyper-processor", 3, 3, """
            op add i i 1
            jump 0 lessThan i 100000
            stop
            """);
        run.run(900, 97);
    }

    /** A world processor: tiles, flags, building health, a world cell, its own rate, the wave state and the clock. */
    @Test
    void worldProcessorsEditTheWorld(){
        var run = new Run(16, 12);
        run.rules();
        run.place("world-cell", 2, 1);
        run.place("router", 6, 6);
        run.processor("world-processor", 1, 1, WORLD_PROGRAM, 2, 1, 6, 6);
        run.watch(3, 3, 4, 3, 2, 9, 5, 9);
        run.run(600, 1);
        assertEquals(mindustry.content.Blocks.oreCopper, world.tile(4, 3).overlay(), "the world processor ran");
    }

    /**
     * The power sensors: a source, a battery, an illuminator and a fuelled thorium reactor in one graph, and an idle
     * impact reactor; a processor reads the graph's numbers, the reactors' heat and the illuminator's colour.
     */
    @Test
    void poweredBlocksSenseTheirGraphHeatAndColor(){
        var run = new Run(16, 16);
        run.place("power-source", 2, 2);
        run.place("battery", 3, 2);
        run.place("illuminator", 4, 2);
        run.place("thorium-reactor", 3, 4);
        run.place("impact-reactor", 9, 9);
        run.raw(4, 2, 0x12345678);
        run.give(3, 4, "thorium", 10);
        run.processor("logic-processor", 6, 3, """
            sensor in battery1 @powerNetIn
            sensor out battery1 @powerNetOut
            sensor stored battery1 @powerNetStored
            sensor capacity battery1 @powerNetCapacity
            sensor color illuminator1 @color
            sensor hot reactor1 @heat
            sensor warm reactor2 @heat
            sensor none battery1 @heat
            """, 3, 2, 4, 2, 3, 4, 9, 9);
        run.at(120, () -> run.raw(4, 2, 0xff00ff80));
        // a loaded building's Interval timers count from vanilla's global Time.time, which a load doesn't reset, so the
        // reactor burns fuel in the first frame after loading
        int[] fuel = new int[1];
        run.afterLoad(0, () -> fuel[0] = run.sim.build(3, 4).items.get(content.item("thorium")));
        run.afterLoad(1, () -> assertEquals(fuel[0] - 1, run.sim.build(3, 4).items.get(content.item("thorium")), "fuel burnt in the first frame"));
        run.run(300, 1);
        var reactor = (mindustry.world.blocks.power.NuclearReactor.NuclearReactorBuild)run.sim.build(3, 4);
        assertTrue(reactor.heat > 0.05f, "the reactor heated: " + reactor.heat);
    }

    /**
     * Conveyors and ducts through the sensor surface: a world processor reads a fed conveyor's {@code @progress} and
     * {@code @firstItem} (its front item), a duct's {@code @progress}, and sets items on an idle conveyor with
     * {@code setprop}, up to its capacity, then fewer.
     */
    @Test
    void conveyorsAndDuctsSenseAndSet(){
        var run = new Run(12, 12);
        run.rules();
        run.place("item-source", 1, 2);
        for(int x = 2; x <= 5; x++) run.place("conveyor", x, 2);
        run.place("item-void", 6, 2);
        run.place("conveyor", 4, 5);
        run.place("item-source", 1, 8);
        for(int x = 2; x <= 5; x++) run.place("duct", x, 8);
        run.place("item-void", 6, 8);
        run.configure(1, 2, "copper");
        run.configure(1, 8, "beryllium");
        run.processor("world-processor", 10, 10, """
            getblock building c 5 2
            getblock building idle 4 5
            getblock building d 5 8
            sensor p c @progress
            sensor f c @firstItem
            sensor dp d @progress
            sensor n idle @lead
            op mod t @tick 200
            jump low lessThan t 100
            setprop @lead idle 5
            jump done always
            low:
            setprop @lead idle 1
            done:
            """);
        run.run(400, 1);
        assertTrue(run.sim.build(4, 5).items.get(content.item("lead")) > 0, "setprop put lead on the idle conveyor");
    }

    static final String WORLD_PROGRAM = """
        setrate 5
        loop:
        getblock floor f 3 3
        setblock floor @darksand 3 3 @derelict 0
        setblock ore @ore-copper 4 3 @derelict 0
        setblock block @copper-wall 2 9 @sharded 0
        setblock block @router 5 9 @crux 1
        getblock building w 2 9
        sensor hp w @health
        op sub hp hp 7
        setprop @health w hp
        sensor rhp router1 @health
        write rhp cell1 0
        write w cell1 1
        setflag "seen" true
        getflag g "seen"
        op add t t 1
        sync t
        set tk @tick
        setrule wave t
        setrule currentWaveTime 2.5
        set wn @waveNumber
        set wt @waveTime
        wait 0.5
        op mod m t 3
        jump loop notEqual m 0
        setflag "seen" false
        setblock floor @stone 3 3 @derelict 0
        setblock block @air 2 9 @derelict 0
        jump loop always
        """;

    /** The Calls a world processor makes (tiles, flags, synced variables), in vanilla's order, frame by frame. */
    @Test
    void callsAreVanillasInOrder(){
        long interval = LExecutor.SyncI.syncInterval;
        // vanilla syncs at most every 50 ms of wall clock; this program syncs every 30 ticks, so every sync goes out
        LExecutor.SyncI.syncInterval = -1;
        try{
            var run = new Run(16, 12);
            run.rules();
            run.place("world-cell", 2, 1);
            run.place("router", 6, 6);
            run.processor("world-processor", 1, 1, WORLD_PROGRAM, 2, 1, 6, 6);
            run.calls = true;
            run.run(400, 1);
            assertTrue(run.callCount > 40, "calls: " + run.callCount);
        }finally{
            LExecutor.SyncI.syncInterval = interval;
        }
    }

    /** One fixture in both worlds, built and edited in step. */
    static final class Run{
        final Sim sim;
        World shard;
        final Map<Integer, List<Runnable>> edits = new TreeMap<>();
        /** Checks run after frame {@code n} from the save; 0 is right after loading. */
        final Map<Integer, List<Runnable>> loaded = new TreeMap<>();
        final List<int[]> watched = new ArrayList<>();
        com.xpdustry.shard.sim.world.Observer observer;
        boolean calls;
        int toggles, callCount, frame;

        Run(int width, int height){
            sim = Sim.empty(width, height);
            shard = new World(WorldConfig.of(EPOCH, width, height, 1).withKinds(KINDS));
            shard.buildings().beginLoad();
            for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
            shard.buildings().endLoad(false);
            // emptyWorld's logic.play() starts the wave countdown
            WaveState.of(shard).play(shard.buildings().rules());
            // GlobalVars.rand, as the oracle seeds it
            LogicPart.require(shard).logic().rand(new Rand(0));
        }

        /** World processors may be configured and may link the default team's buildings. */
        void rules(){
            sim.rules(r -> {
                r.allowEditWorldProcessors = true;
                r.worldProcessorPlayerLink = true;
            });
            var rules = shard.buildings().rules();
            rules.allowEditWorldProcessors = true;
            rules.worldProcessorPlayerLink = true;
        }

        /** As a map editor places it: {@code tile.setBlock} for team sharded, rotation 0. */
        void place(String block, int x, int y){
            sim.place(content.block(block), x, y, 0, Team.sharded);
            shard.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, 0, Cause.SERVER);
        }

        void remove(int x, int y){
            world.tile(x, y).setAir();
            Vanilla.runPosted();
            shard.buildings().remove(shard.buildings().at(x, y), Cause.SERVER);
        }

        /** A processor running {@code code}, then linked to each {@code (x, y)} in {@code links} as a player links. */
        void processor(String block, int x, int y, String code, int... links){
            place(block, x, y);
            sim.build(x, y).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(code, new Seq<>()));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(LogicConfig.compress(code, List.of()));
            for(int i = 0; i < links.length; i += 2) link(x, y, links[i], links[i + 1]);
        }

        /** Links or unlinks {@code (tx, ty)}: the {@code Integer} config. */
        void link(int x, int y, int tx, int ty){
            sim.build(x, y).configureAny(arc.math.geom.Point2.pack(tx, ty));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(Point2.pack(tx, ty));
        }

        /** An item source's item. */
        void configure(int x, int y, String value){
            if(sim.build(x, y) instanceof mindustry.world.blocks.logic.MessageBlock.MessageBuild){
                sim.build(x, y).configureAny(value);
                Vanilla.runPosted();
                shard.buildings().at(x, y).configureAny(value);
                return;
            }
            sim.build(x, y).configureAny(content.item(value));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(ContentRef.item(EPOCH.item(value).id()));
        }

        /** A config value both sides read alike: a boxed number. */
        void raw(int x, int y, Object value){
            sim.build(x, y).configureAny(value);
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(value);
        }

        /** Items put into a building, as a map editor's. */
        void give(int x, int y, String item, int amount){
            sim.build(x, y).items.add(content.item(item), amount);
            shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
        }

        void toggle(int x, int y, boolean on){
            sim.build(x, y).configureAny(on);
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(on);
        }

        /** Tiles whose floor, ore, block and team are compared too. */
        void watch(int... tiles){
            for(int i = 0; i < tiles.length; i += 2) watched.add(new int[]{tiles[i], tiles[i + 1]});
        }

        void at(int when, Runnable edit){
            edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        }

        void afterLoad(int frame, Runnable check){
            loaded.computeIfAbsent(frame, k -> new ArrayList<>()).add(check);
        }

        void run(int frames, int every){
            if(calls){
                Vanilla.captured().clear();
                observer = shard.observe(4096, List.of(LogicEvent.class), null);
            }
            same(vanillaStates(true), shardStates(true), "as placed");
            String lastRouters = "";
            for(frame = 1; frame <= frames; frame++){
                for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
                if(calls){
                    int before = Vanilla.captured().size();
                    Vanilla.net(Vanilla.NetMode.server, () -> sim.step(1));
                    shard.tick();
                    var sent = Vanilla.captured().subList(before, Vanilla.captured().size());
                    var expected = vanillaCalls(sent);
                    var events = observer.drain();
                    var actual = events.stream().map(event -> shardCall((LogicEvent)event)).toList();
                    assertEquals(expected, actual, "calls in frame " + frame);
                    // and byte for byte, as the sync layer's mappings send them
                    CallFrames.same(CallFrames.vanilla(sent, LOGIC_CALLS), CallFrames.shard(shard, events), "Call bytes in frame " + frame);
                    callCount += expected.size();
                }else{
                    sim.step(1);
                    shard.tick();
                }
                boolean processors = frame % every == 0 || frame == frames;
                same(vanillaStates(processors), shardStates(processors), "after frame " + frame);
                String routers = routers();
                if(!routers.equals(lastRouters)) toggles++;
                lastRouters = routers;
            }
            chunks("save chunk");
            if(calls) return;

            // vanilla's save of this world, loaded by both
            var file = new ByteArrayOutputStream();
            SaveIO.write(file, new SaveOptions());
            // as the server's load command: SaveIO.load resets the game to the menu, then it plays again
            SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
            state.set(mindustry.core.GameState.State.playing);
            Vanilla.runPosted();
            SaveFile save = READER.read(file.toByteArray());
            shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
            LogicPart.require(shard).logic().rand(new Rand(GlobalVars.rand.seed0, GlobalVars.rand.seed1));
            chunks("save chunk after loading vanilla's save");
            same(vanillaStates(true), shardStates(true), "loaded");
            for(Runnable check : loaded.getOrDefault(0, List.of())) check.run();
            for(int i = 1; i <= AFTER_LOAD; i++){
                sim.step(1);
                shard.tick();
                boolean processors = i % every == 0 || i == AFTER_LOAD;
                same(vanillaStates(processors), shardStates(processors), "after frame " + i + " from the save");
                for(Runnable check : loaded.getOrDefault(i, List.of())) check.run();
            }
        }

        /** Every building's chunk, but those of unported blocks (walls a world processor places), which can't be saved. */
        void chunks(String what){
            var current = ContentHeader.of(EPOCH).mapping(EPOCH);
            for(mindustry.gen.Building expected : buildings()){
                Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
                if(actual instanceof PassthroughBuilding) continue;
                assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                    expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): " + what);
            }
        }

        String routers(){
            var out = new StringBuilder();
            for(mindustry.gen.Building build : buildings()) if(build.block == mindustry.content.Blocks.router) out.append(build.enabled);
            return out.toString();
        }

        List<mindustry.gen.Building> buildings(){
            var out = new ArrayList<mindustry.gen.Building>();
            for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
            return out;
        }

        List<String> vanillaStates(boolean processors){
            var out = new ArrayList<String>();
            out.add("time " + arc.util.Time.time);
            for(int[] at : watched){
                var tile = world.tile(at[0], at[1]);
                out.add("tile (" + at[0] + ", " + at[1] + ") " + tile.floor().name + "/" + tile.overlay().name + "/" + tile.block().name + " team " + tile.team().id);
            }
            for(mindustry.gen.Building build : buildings()){
                var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") team " + build.team.id
                    + " enabled " + build.enabled + " health " + build.health + " items " + items(id -> build.items == null ? 0 : build.items.get(id))
                    + " timers " + Arrays.toString(build.timer.getTimes()));
                switch(build){
                    case MemoryBuild memory -> {
                        Object[] objects = field(memory, MemoryBuild.class, "objectMemory");
                        double[] numbers = field(memory, MemoryBuild.class, "numberMemory");
                        Object sentinel = field(null, MemoryBuild.class, "sentinel");
                        line.append(" memory");
                        for(int i = 0; i < objects.length; i++) line.append(' ').append(objects[i] == sentinel ? num(numbers[i]) : vanillaValue(objects[i]));
                    }
                    case MessageBuild message -> line.append(" message \"").append(message.message).append('"');
                    case mindustry.world.blocks.logic.LogicBlock.LogicBuild logic when processors -> {
                        line.append(" counter ").append(num(logic.executor.counter.numval)).append(" accumulator ").append(logic.accumulator);
                        for(LVar v : logic.executor.vars) line.append(' ').append(v.name).append('=').append(v.isobj ? vanillaValue(v.objval) : num(v.numval));
                        line.append(" links");
                        for(var link : logic.links) line.append(' ').append(link.name).append('@').append(link.x).append(',').append(link.y).append(link.valid ? "" : " invalid");
                        line.append(" text \"").append(logic.executor.textBuffer).append('"');
                    }
                    default -> {}
                }
                out.add(line.toString());
            }
            return out;
        }

        List<String> shardStates(boolean processors){
            var out = new ArrayList<String>();
            out.add("time " + shard.timers().time());
            var tiles = shard.tiles();
            for(int[] at : watched){
                int pos = tiles.pos(at[0], at[1]);
                out.add("tile (" + at[0] + ", " + at[1] + ") " + EPOCH.block(tiles.floor(pos)).name() + "/" + EPOCH.block(tiles.overlay(pos)).name()
                    + "/" + EPOCH.block(tiles.block(pos)).name() + " team " + tiles.team(pos));
            }
            for(mindustry.gen.Building expected : buildings()){
                Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
                var line = new StringBuilder(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") team " + build.team
                    + " enabled " + build.enabled + " health " + build.health + " items " + items(id -> build.items == null ? 0 : build.items.get(id))
                    + " timers " + Arrays.toString(build.timer));
                switch(build){
                    case MemoryBlock memory -> {
                        line.append(" memory");
                        for(int i = 0; i < memory.objects.length; i++){
                            line.append(' ').append(memory.objects[i] == MemoryBlock.NUMBER ? num(memory.numbers[i]) : shardValue(memory.objects[i]));
                        }
                    }
                    case MessageBlock message -> line.append(" message \"").append(message.message).append('"');
                    case LogicBlock logic when processors -> {
                        Processor p = logic.processor();
                        line.append(" counter ").append(shardValue(p.counterValue())).append(" accumulator ").append(p.accumulator());
                        for(int i = 0; i < p.program().variables(); i++){
                            line.append(' ').append(p.program().variableName(i)).append('=').append(shardValue(p.variable(i)));
                        }
                        line.append(" links");
                        for(var link : p.links()) line.append(' ').append(link.name()).append('@').append(link.x).append(',').append(link.y).append(link.valid() ? "" : " invalid");
                        line.append(" text \"").append(p.text()).append('"');
                    }
                    default -> {}
                }
                out.add(line.toString());
            }
            return out;
        }

        List<String> vanillaCalls(List<Vanilla.Capture.Sent> sent){
            var out = new ArrayList<String>();
            for(var call : sent){
                switch(call.packet()){
                    case mindustry.gen.SetTileCallPacket p -> out.add("setTile " + p.tile.x + "," + p.tile.y + " " + p.block.name + " team " + p.team.id + " rotation " + p.rotation);
                    case mindustry.gen.SetFloorCallPacket p -> out.add("setFloor " + p.tile.x + "," + p.tile.y + " " + p.floor.name + " " + p.overlay.name);
                    case mindustry.gen.SetOverlayCallPacket p -> out.add("setOverlay " + p.tile.x + "," + p.tile.y + " " + p.overlay.name);
                    case mindustry.gen.SetFlagCallPacket p -> out.add("setFlag " + p.flag + " " + p.add);
                    case mindustry.gen.SyncVariableCallPacket p -> out.add("syncVariable " + p.building.tile.x + "," + p.building.tile.y + " " + p.variable + " " + vanillaValue(p.value));
                    case mindustry.gen.SetMapAreaCallPacket p -> out.add("setMapArea " + p.x + "," + p.y + " " + p.w + "x" + p.h);
                    default -> {}
                }
            }
            return out;
        }

        /** An event's value ({@link ConfigValue}) as {@link #vanillaValue} describes the object it stands for. */
        String syncedValue(Object value){
            return switch(value){
                case ContentRef ref -> EPOCH.get(ref.type(), ref.id()).name();
                case ConfigValue.Team team -> "team " + team.id();
                case ConfigValue.BuildingAt at -> shardValue(shard.buildings().at(at.pos()));
                default -> shardValue(value);
            };
        }

        String shardCall(LogicEvent event){
            var tiles = shard.tiles();
            return switch(event){
                case LogicEvent.SetTile e -> "setTile " + tiles.x(e.pos()) + "," + tiles.y(e.pos()) + " " + EPOCH.block(e.block()).name() + " team " + e.team() + " rotation " + e.rotation();
                case LogicEvent.SetFloor e -> "setFloor " + tiles.x(e.pos()) + "," + tiles.y(e.pos()) + " " + EPOCH.block(e.floor()).name() + " " + EPOCH.block(e.overlay()).name();
                case LogicEvent.SetOverlay e -> "setOverlay " + tiles.x(e.pos()) + "," + tiles.y(e.pos()) + " " + EPOCH.block(e.overlay()).name();
                case LogicEvent.SetFlag e -> "setFlag " + e.flag() + " " + e.add();
                case LogicEvent.SyncVariable e -> "syncVariable " + tiles.x(e.pos()) + "," + tiles.y(e.pos()) + " " + e.variable() + " " + syncedValue(e.value());
                case LogicEvent.SetMapArea e -> "setMapArea " + e.x() + "," + e.y() + " " + e.width() + "x" + e.height();
            };
        }
    }

    /** The first line that differs, whole, rather than both lists. */
    static void same(List<String> expected, List<String> actual, String when){
        for(int i = 0; i < Math.min(expected.size(), actual.size()); i++){
            if(!expected.get(i).equals(actual.get(i))) assertEquals(expected.get(i), actual.get(i), when);
        }
        assertEquals(expected.size(), actual.size(), when + ": " + expected + " vs " + actual);
    }

    static String num(double value){
        return Double.toString(value);
    }

    static String vanillaValue(Object value){
        return switch(value){
            case null -> "null";
            case Double d -> num(d);
            case String s -> '"' + s + '"';
            case mindustry.gen.Building b -> b.block.name + "@" + b.tile.x + "," + b.tile.y;
            case mindustry.ctype.MappableContent c -> c.name;
            case Team t -> "team " + t.id;
            case mindustry.logic.LAccess a -> "@" + a.name();
            default -> value.getClass().getName();
        };
    }

    static String shardValue(Object value){
        return switch(value){
            case null -> "null";
            case Double d -> num(d);
            case String s -> '"' + s + '"';
            case Building b -> b.block().name() + "@" + b.tileX() + "," + b.tileY();
            case ContentDef c -> c.name();
            case LogicTeam t -> "team " + t.id();
            case LAccess a -> "@" + a.name();
            default -> value.getClass().getName();
        };
    }

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, Class<?> type, String name){
        try{
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return (T)field.get(owner);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
