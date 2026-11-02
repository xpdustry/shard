package com.xpdustry.shard.compat.v160.blocks.logic;

import arc.Core;
import arc.math.*;
import arc.struct.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.objectives.MapMarkers;
import com.xpdustry.shard.sim.objectives.ObjectiveState;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.MapObjectives.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP74's frame-by-frame gates ({@link UnitLogicRun}): objectives of every kind progress as vanilla's under a scripted
 * run, through a save, and a world processor makes and changes markers of every kind as vanilla's does. After every
 * frame, besides the run's own comparison: the rules' objectives as JSON (their progress included), the objective
 * flags, the stats objectives read, and the markers as the save writes them; the Calls of each frame byte for byte.
 *
 * <p>Pin: a finished construction's place sound draws from the random by wall-clock time on vanilla's side
 * (VANILLA-QUIRKS, W2.15), so the scripted {@code constructFinish} keeps both randoms as they were; construction's own
 * gates cover that draw.
 */
class ObjectiveOracleTest{
    static final ObjectivesJson JSON = new RulesJson(Worlds.EPOCH).objectives();

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    static Rules rules(UnitLogicRun run){
        return run.shard.part(WorldRules.class).rules;
    }

    /** Both sides take vanilla's objectives, Shard's through vanilla's JSON. */
    static void objectives(UnitLogicRun run, mindustry.game.MapObjectives exec){
        state.rules.objectives = exec;
        rules(run).objectives = JSON.readObjectives(ArcJson.parse(JsonIO.write(exec)));
    }

    static byte[] markers(mindustry.game.MapMarkers markers){
        try{
            var out = new ByteArrayOutputStream();
            markers.write(new DataOutputStream(out));
            return out.toByteArray();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** The objective state compared after every frame. */
    static void compare(UnitLogicRun run){
        run.also = at -> {
            Rules rules = rules(run);
            ObjectiveState shard = ObjectiveState.of(run.shard);
            assertEquals(JsonIO.write(state.rules.objectives), JSON.writeObjectives(rules.objectives), at + ": objectives");
            var flags = new TreeSet<String>();
            for(String flag : state.rules.objectiveFlags) flags.add(flag);
            assertEquals(flags, rules.objectiveFlags, at + ": objective flags");
            assertArrayEquals(markers(state.markers), JSON.writeMarkers(shard.markers), at + ": markers");
            assertEquals(stats(state.stats), stats(shard.stats), at + ": game stats");
        };
    }

    /** Every count of vanilla's {@code GameStats}, its maps by content name. */
    static String stats(mindustry.game.GameStats stats){
        return "enemyUnitsDestroyed " + stats.enemyUnitsDestroyed + " wavesLasted " + stats.wavesLasted + " buildingsBuilt "
            + stats.buildingsBuilt + " buildingsDeconstructed " + stats.buildingsDeconstructed + " buildingsDestroyed "
            + stats.buildingsDestroyed + " unitsCreated " + stats.unitsCreated + " placed " + names(stats.placedBlockCount)
            + " destroyed " + names(stats.destroyedBlockCount) + " coreItems " + names(stats.coreItemCount);
    }

    /** Shard's {@link com.xpdustry.shard.sim.objectives.GameStats}, as {@link #stats(mindustry.game.GameStats)} prints vanilla's. */
    static String stats(com.xpdustry.shard.sim.objectives.GameStats stats){
        var placed = new TreeMap<String, Integer>();
        stats.placedBlockCount.forEach((id, count) -> placed.put(Worlds.EPOCH.block(id).name(), count));
        var destroyed = new TreeMap<String, Integer>();
        stats.destroyedBlockCount.forEach((id, count) -> destroyed.put(Worlds.EPOCH.block(id).name(), count));
        var items = new TreeMap<String, Integer>();
        stats.coreItemCount.forEach((id, count) -> items.put(Worlds.EPOCH.item(id).name(), count));
        return "enemyUnitsDestroyed " + stats.enemyUnitsDestroyed + " wavesLasted " + stats.wavesLasted + " buildingsBuilt "
            + stats.buildingsBuilt + " buildingsDeconstructed " + stats.buildingsDeconstructed + " buildingsDestroyed "
            + stats.buildingsDestroyed + " unitsCreated " + stats.unitsCreated + " placed " + placed + " destroyed " + destroyed
            + " coreItems " + items;
    }

    private static <T extends mindustry.ctype.UnlockableContent> TreeMap<String, Integer> names(ObjectIntMap<T> counts){
        var out = new TreeMap<String, Integer>();
        for(var entry : counts) if(entry.value != 0) out.put(entry.key.name, entry.value);
        return out;
    }

    @Test
    void everyObjectiveKindProgressesAsVanillas(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("core-shard", 10, 10, Team.sharded);
            run.place("core-shard", 30, 12, Team.crux);
            for(int x = 30; x <= 34; x += 2) run.place("router", x, 30, Team.crux);
            run.dummy("dagger", Team.crux, 200f, 200f);
            run.processor("world-processor", 2, 2, Team.sharded, """
                getflag d "timer-done"
                jump 0 equal d false
                setflag "world" true
                """);

            var exec = new mindustry.game.MapObjectives();
            var research = new ResearchObjective(Items.copper);
            var produce = new ProduceObjective(Items.silicon);
            var item = new ItemObjective(Items.lead, 40);
            item.parents.add(research);
            var coreItem = new CoreItemObjective(Items.sand, 3);
            var build = new BuildCountObjective(Blocks.conveyor, 2);
            build.flagsAdded = new String[]{"built"};
            var units = new UnitCountObjective(UnitTypes.dagger, 2);
            var destroyUnits = new DestroyUnitsObjective(1);
            var timer = new TimerObjective("@objective.enemyescelating", 200f);
            timer.flagsAdded = new String[]{"timer-done", "spare"};
            timer.completionLogicCode = "setflag \"scripted\" true\nmakemarker shape 9 5 5 true\nsetmarker radius 9 3";
            timer.markers = new ObjectiveMarker[]{new ShapeTextMarker("text", 8f, 8f)};
            var scripted = new FlagObjective("scripted", "@flag");
            scripted.parents.add(timer);
            var block = new DestroyBlockObjective(Blocks.router, 30, 30, Team.crux);
            var blocks = new DestroyBlocksObjective(Blocks.router, Team.crux, new arc.math.geom.Point2(32, 30), new arc.math.geom.Point2(34, 30));
            var command = new CommandModeObjective();
            var core = new DestroyCoreObjective();
            core.parents.add(block);
            var world = new FlagObjective("world", null);
            world.flagsRemoved = new String[]{"spare"};
            exec.add(research, produce, item, coreItem, build, units, destroyUnits, timer, scripted, block, blocks, command, core, world);
            objectives(run, exec);
            compare(run);

            run.at(60, () -> run.give(10, 10, "lead", 50));
            for(int frame = 80; frame <= 82; frame++){
                run.at(frame, () -> {
                    mindustry.Vars.world.build(10, 10).handleItem(null, Items.sand);
                    run.shard.buildings().at(10, 10).handleItem(null, Worlds.EPOCH.item("sand").id());
                });
            }
            for(int i = 0; i < 2; i++){
                int x = 20 + i;
                run.at(100 + i, () -> constructFinish(run, x, 20, "conveyor"));
            }
            run.at(120, () -> {
                run.dummy("dagger", Team.sharded, 100f, 100f);
                run.dummy("dagger", Team.sharded, 110f, 100f);
            });
            run.sending(140, () -> {
                Groups.unit.find(u -> u.team == Team.crux).kill();
                for(int i = 0; i < run.shard.units().size(); i++){
                    var unit = run.shard.units().get(i);
                    if(unit.team == Team.crux.id) unit.kill();
                }
            });
            run.at(160, () -> remove(run, 30, 30));
            run.at(170, () -> remove(run, 32, 30));
            run.at(180, () -> remove(run, 34, 30));
            run.at(190, () -> remove(run, 30, 12));
            run.saveAt(150);
            run.run(400);

            Rules rules = rules(run);
            var done = new ArrayList<String>();
            for(var objective : rules.objectives.all) if(objective.completed) done.add(objective.tag());
            System.out.println("objectives done after 400 frames: " + done);
            assertFalse(rules.objectives.all.get(1).completed, "research of content a server hasn't unlocked never completes");
            assertEquals(13, done.size(), "every other objective completed: " + done);
            assertEquals(new TreeSet<>(List.of("built", "scripted", "timer-done", "world")), rules.objectiveFlags);
            assertTrue(run.seen("Completed") >= 13, "the completions were Calls: " + run.seen);
        }
    }

    /**
     * The game stats vanilla's {@code Logic} counts, through a save: the default team's walls destroyed by damage and by
     * a world processor's explosion count in {@code buildingsDestroyed}; crux routers and a derelict wall in
     * {@code destroyedBlockCount}. The default team's payload source counts each dagger it makes in
     * {@code unitsCreated}; a crux one (into a payload void) and the units a world processor spawns count nowhere.
     */
    @Test
    void gameStatsCountAsVanillas(){
        try(var run = new UnitLogicRun(40, Map.of())){
            for(int x = 10; x <= 18; x += 2) run.place("copper-wall", x, 20, Team.sharded);
            for(int x = 10; x <= 14; x += 2) run.place("router", x, 30, Team.crux);
            run.place("copper-wall", 20, 30, Team.derelict);
            run.place("payload-source", 30, 10, Team.sharded);
            run.place("payload-source", 30, 30, Team.crux);
            run.place("payload-void", 35, 30, Team.crux);
            for(int y : new int[]{10, 30}){
                world.build(30, y).configure(UnitTypes.dagger);
                Vanilla.runPosted();
                run.shard.buildings().at(30, y).configureAny(new com.xpdustry.shard.sim.building.ContentRef(com.xpdustry.shard.content.ContentType.UNIT,
                    Worlds.EPOCH.unit("dagger").id()));
            }
            run.processor("world-processor", 2, 2, Team.sharded, """
                set t @tick
                jump 0 lessThan t 400
                explosion @crux 16 20 3 5000 false true false false
                explosion @sharded 12 30 3 5000 false true false false
                spawn @flare 5 35 0 @sharded f
                spawn @flare 7 35 0 @crux f
                stop
                """);
            compare(run);
            int[] cruxCreated = {0};
            arc.Events.on(mindustry.game.EventType.UnitCreateEvent.class, e -> {
                if(e.unit.team == Team.crux) cruxCreated[0]++;
            });
            run.at(100, () -> run.damage(10, 20, 10_000f));
            run.at(120, () -> run.damage(20, 30, 10_000f));
            run.at(140, () -> run.damage(14, 30, 10_000f));
            run.saveAt(250);
            run.run(700);

            var stats = ObjectiveState.of(run.shard).stats;
            System.out.println("game stats after 700 frames: " + stats(stats));
            int router = Worlds.EPOCH.block("router").id(), wall = Worlds.EPOCH.block("copper-wall").id();
            assertTrue(stats.buildingsDestroyed >= 2, "a damaged wall and walls in the blast: " + stats(stats));
            assertTrue(stats.destroyedBlockCount.getOrDefault(router, 0) >= 2, "routers by damage and in the blast: " + stats(stats));
            assertEquals(1, stats.destroyedBlockCount.getOrDefault(wall, 0), "the derelict wall: " + stats(stats));
            int daggers = 0;
            for(int i = 0; i < run.shard.units().size(); i++){
                var unit = run.shard.units().get(i);
                if(unit.team == Team.sharded.id && unit.type().name().equals("dagger")) daggers++;
            }
            assertTrue(daggers >= 3, "the sharded payload source made daggers: " + daggers);
            // the source may hold one more it made and hasn't put down yet
            assertTrue(stats.unitsCreated == daggers || stats.unitsCreated == daggers + 1, "only the sharded daggers count: " + daggers
                + " daggers, " + stats(stats));
            assertTrue(cruxCreated[0] >= 3, "vanilla's crux source made daggers too: " + cruxCreated[0]);
        }
    }

    /** {@code ConstructBlock.constructFinish} on both sides, each random kept (the class comment's pin). */
    static void constructFinish(UnitLogicRun run, int x, int y, String block){
        long s0 = Mathf.rand.seed0, s1 = Mathf.rand.seed1;
        ConstructBlock.constructFinish(mindustry.Vars.world.tile(x, y), content.block(block), null, (byte)0, Team.sharded, null);
        Mathf.rand.setState(s0, s1);
        long r0 = run.shard.rand().getState(0), r1 = run.shard.rand().getState(1);
        Build.constructFinish(run.shard, run.shard.tiles().pos(x, y), Worlds.EPOCH.block(block), null, 0, Team.sharded.id, null);
        run.shard.rand().setState(r0, r1);
    }

    static void remove(UnitLogicRun run, int x, int y){
        mindustry.Vars.world.tile(x, y).setAir();
        Vanilla.runPosted();
        run.shard.buildings().setBlock(x, y, Worlds.EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    @Test
    void worldProcessorMarkersAreVanillas(){
        Core.settings.put("locale", "en");
        try(var run = new UnitLogicRun(32, Map.of())){
            var locales = new mindustry.type.MapLocales();
            var bundle = new StringMap();
            bundle.put("greeting", "hi there");
            locales.put("en", bundle);
            state.mapLocales = locales;
            ObjectiveState.of(run.shard).locales.put("en", "greeting", "hi there");
            state.markers = new mindustry.game.MapMarkers();
            run.place("router", 6, 6, Team.sharded);
            run.processor("world-processor", 2, 2, Team.sharded, """
                makemarker shapeText 1 5 5 false
                makemarker point 2 6 6 false
                makemarker shape 3 7 7 false
                makemarker text 4 8 8 false
                makemarker line 5 9 9 false
                makemarker texture 6 10 10 false
                makemarker quad 7 11 11 false
                makemarker light 8 12 12 false
                makemarker minimap 10 3 3 false
                op mod t @tick 7
                op mod u @tick 2
                setmarker radius 1 t
                setmarker pos 2 t 3
                setmarker color 3 %ff8040ff
                setmarker shape 3 t 1 0
                setmarker arc 3 10 t
                setmarker endPos 5 t 2
                setmarker posi 5 1 t 4
                setmarker colori 5 1 %10203040
                setmarker colori 7 2 %10203040
                setmarker posi 7 3 t 2
                setmarker pos 7 t 4
                setmarker textureSize 6 2 t
                setmarker world 4 u
                setmarker minimap 2 1
                setmarker light 8 u
                setmarker labelFlags 4 u 1
                setmarker outline 1 u
                setmarker fontSize 4 1.5
                setmarker drawLayer 3 t
                setmarker autoscale 3 u
                setmarker stroke 5 t
                localeprint "greeting"
                setmarker flushText 1 false
                print "frog"
                setmarker flushText 4 true
                print "white"
                setmarker texture 6 true
                getblock building b 6 6
                setmarker texture 7 false b
                setmarker texture 6 false @copper
                jump 44 notEqual t 3
                setmarker remove 2
                makemarker light 2 4 4 true
                makemarker shape 1 1 1 true
                """);
            compare(run);
            run.saveAt(120);
            run.run(240);
            var markers = ObjectiveState.of(run.shard).markers;
            assertEquals(9, markers.size());
            System.out.println("markers after 240 frames: " + markers.list(MapMarkers.WORLD).size() + " in the world list");
        }
    }
}
