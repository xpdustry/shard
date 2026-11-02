package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.unit.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP69): processors' unit and world statements against vanilla, frame by frame ({@link UnitLogicRun}):
 * {@code ubind}, {@code ucontrol}, {@code uradar} and {@code radar}, {@code ulocate}, a logic-controlled unit's return to
 * its AI, and a world processor's {@code fetch}, {@code spawn}, {@code status}, {@code bullet}, {@code explosion},
 * {@code spawnwave} and weather. Each fixture asserts what it exercised, so that agreeing on nothing can't pass.
 */
public class UnitLogicOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * A flare bound with {@code ubind} is driven by {@code ucontrol move}, {@code approach} with {@code boost},
     * {@code target} and {@code targetp} on what {@code uradar} finds, shooting; the processor then lets it go, and 600
     * frames later the flare goes back to its command AI. Vanilla saves and both load the world mid-control.
     */
    @Test
    void aFlareIsDrivenThenReturnsToItsAi(){
        try(var run = new UnitLogicRun(48, Map.of())){
            run.processor("logic-processor", 4, 4, Team.sharded, """
                set t @tick
                jump release greaterThan t 400
                ubind @flare
                set u @unit
                jump p2 greaterThan t 100
                ucontrol move 20 20 0 0 0
                ucontrol within 20 20 2 w 0
                end
                p2:
                jump p3 greaterThan t 200
                ucontrol approach 24 22 4 0 0
                ucontrol boost 1 0 0 0 0
                end
                p3:
                jump p4 greaterThan t 300
                ucontrol target 25 25 1 0 0
                end
                p4:
                uradar enemy any any distance 0 1 e
                ucontrol targetp e 1 0 0 0
                ucontrol flag 7 0 0 0 0
                end
                release:
                sensor c u @controlled
                sensor ctl u @controller
                sensor hp u @health
                sensor sx u @shootX
                sensor fl u @flag
                """);
            run.spawn("flare", Team.sharded, 10 * 8f, 10 * 8f);
            run.dummy("dagger", Team.crux, 27 * 8f, 26 * 8f);
            run.saveAt(250);
            run.run(1100);
            assertEquals(1, run.shard.units().size(), "the flare shot the dagger dead");
            assertEquals("command-ai", ((AiController)run.shard.units().get(0).controller).kind, "the flare went back to its AI");
        }
    }

    /**
     * A dagger told to {@code pathfind} in the open walks there, reads a block with {@code getBlock}, is stopped and
     * flagged, then {@code unbind} hands it straight back to its AI. Saved and loaded mid-walk.
     */
    @Test
    void aDaggerPathfindsStopsAndIsUnbound(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("copper-wall", 21, 10, Team.sharded);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                set t @tick
                jump done greaterThan t 300
                ubind @dagger
                set u @unit
                jump p2 greaterThan t 200
                ucontrol pathfind 20 8 0 0 0
                ucontrol within 20 8 1 w 0
                ucontrol getBlock 21 10 bt bb bf
                end
                p2:
                jump p3 greaterThan t 260
                ucontrol stop 0 0 0 0 0
                ucontrol flag 3 0 0 0 0
                end
                p3:
                ucontrol unbind 0 0 0 0 0
                end
                done:
                sensor c u @controlled
                sensor x u @x
                sensor fl u @flag
                """);
            run.spawn("dagger", Team.sharded, 8 * 8f, 8 * 8f);
            run.saveAt(120);
            run.run(660);
            Unit dagger = run.shard.units().get(0);
            assertEquals(20f, dagger.x / 8f, 1.5f, "the dagger walked to the point");
            assertEquals("command-ai", ((AiController)dagger.controller).kind, "unbind gave the dagger back");
        }
    }

    /**
     * {@code uradar} from a bound flare and {@code radar} from a linked duo, with every sort key both ways and the
     * target filters, over enemies at tied and distinct distances, healths, shields and armours; one moves and one is
     * hurt halfway.
     */
    @Test
    void radarsSortTheirTargetsAsVanilla(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("duo", 12, 8, Team.sharded);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                ubind @flare
                ucontrol move 10 10 0 0 0
                uradar enemy any any distance 0 1 a1
                uradar enemy any any distance 0 0 a2
                uradar enemy any any health 0 1 a3
                uradar enemy any any health 0 0 a4
                uradar enemy any any shield 0 1 a5
                uradar enemy any any armor 0 1 a6
                uradar enemy any any armor 0 0 a7
                uradar enemy any any maxHealth 0 1 a8
                uradar enemy ground any distance 0 1 a9
                uradar enemy flying any distance 0 1 a10
                uradar ally any any distance 0 1 a11
                uradar enemy boss any distance 0 1 a12
                uradar enemy attacker ground health 0 1 a13
                radar enemy any any distance duo1 1 b1
                radar enemy any any distance duo1 0 b2
                radar enemy any any health duo1 1 b3
                radar enemy flying any distance duo1 1 b4
                radar any any any maxHealth duo1 1 b5
                radar enemy any any distance @this 1 b6
                """, 12, 8);
            run.spawn("flare", Team.sharded, 10 * 8f, 10 * 8f);
            run.dummy("dagger", Team.crux, 13 * 8f, 10 * 8f);
            run.dummy("dagger", Team.crux, 10 * 8f, 13 * 8f);
            run.dummy("flare", Team.crux, 12 * 8f, 12 * 8f);
            run.dummy("mace", Team.crux, 8 * 8f, 12 * 8f);
            run.dummy("dagger", Team.sharded, 9 * 8f, 9 * 8f);
            run.unit(2, (v, s) -> {
                v.shield = 20f;
                s.shield = 20f;
            });
            run.at(60, () -> run.unit(1, (v, s) -> {
                v.set(11 * 8f, 11 * 8f);
                s.set(11 * 8f, 11 * 8f);
            }));
            run.at(120, () -> run.unit(3, (v, s) -> {
                v.damage(30f);
                s.damage(30f);
            }));
            run.run(600);
        }
    }

    /**
     * {@code ulocate} from a bound flare: the nearest ore of two kinds and of none, its own and the enemy's core, a
     * container by flag, a spawn, and a building that was hurt; the flare then flies to the ore, so the ore's quadrant
     * changes under it.
     */
    @Test
    void ulocateFindsOresBuildingsSpawnsAndDamage(){
        var tiles = new TreeMap<Integer, String>();
        for(int x = 30; x <= 32; x++) tiles.put(30 * 48 + x, "ore-copper");
        tiles.put(8 * 48 + 20, "ore-copper");
        tiles.put(30 * 48 + 20, "ore-lead");
        tiles.put(40 * 48 + 40, "spawn");
        try(var run = new UnitLogicRun(48, tiles)){
            run.place("core-shard", 24, 24, Team.sharded);
            run.place("core-shard", 40, 10, Team.crux);
            run.place("container", 14, 14, Team.sharded);
            run.place("copper-wall", 16, 18, Team.sharded);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                ubind @flare
                ulocate ore core true @copper ox oy of ob
                ulocate ore core true @lead lx ly lf lb
                ulocate ore core true @titanium tx ty tf tb
                ulocate building core false @copper cx cy cf cb
                ulocate building core true @copper ex ey ef eb
                ulocate building storage false @copper sx sy sf sb
                ulocate building turret true @copper qx qy qf qb
                ulocate spawn core true @copper px py pf pb
                ulocate damaged core true @copper dx dy df db
                sensor dh db @health
                ucontrol move ox oy 0 0 0
                """);
            run.spawn("flare", Team.sharded, 10 * 8f, 10 * 8f);
            run.at(40, () -> run.damage(16, 18, 30f));
            run.run(600);
        }
    }

    /**
     * A dagger takes copper from its core and drops it into a linked container, every 90 frames as
     * {@code LExecutor.unitTimeouts} lets it, then takes lead and throws it away with {@code itemDrop @air}.
     */
    @Test
    void itemsMoveBetweenACoreAndAContainer(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("core-shard", 10, 10, Team.sharded);
            run.place("container", 14, 12, Team.sharded);
            run.give(10, 10, "copper", 300);
            run.give(10, 10, "lead", 100);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                set t @tick
                ubind @dagger
                ulocate building core false @copper cx cy cf core
                jump p2 greaterThan t 400
                ucontrol move 13 11 0 0 0
                ucontrol itemTake core @copper 20 0 0
                ucontrol itemDrop container1 20 0 0
                end
                p2:
                ucontrol itemTake core @lead 5 0 0
                ucontrol itemDrop @air 5 0 0
                sensor st @unit @totalItems
                sensor fi @unit @firstItem
                """, 14, 12);
            run.spawn("dagger", Team.sharded, 13 * 8f, 13 * 8f);
            run.run(700);
            assertTrue(run.shard.buildings().at(14, 12).items.get(run.shard.content().item("copper").id()) > 0, "copper reached the container");
        }
    }

    /** A poly told to {@code build} a wall and a turned conveyor builds them from its core's items, then reads them back. */
    @Test
    void aPolyBuildsWhatItIsTold(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("core-shard", 24, 24, Team.sharded);
            run.give(24, 24, "copper", 200);
            run.give(24, 24, "lead", 200);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                ubind @poly
                ucontrol move 19 19 0 0 0
                ucontrol build 18 18 @copper-wall 0 0
                ucontrol getBlock 18 18 at ab af
                jump done notEqual at @copper-wall
                ucontrol build 19 17 @conveyor 1 0
                ucontrol getBlock 19 17 ct cb cf
                done:
                """);
            run.spawn("poly", Team.sharded, 20 * 8f, 20 * 8f);
            run.run(600);
            assertNotNull(run.shard.buildings().at(18, 18), "the wall was built");
        }
    }

    /**
     * A world processor's {@code query}, read back from {@code @queries}: units of every team in a circle (one inside
     * only by its hit size, one in the square but not the circle) and of one in a rectangle, over a team tree that has
     * split; buildings of every team and of one; and bullets, which vanilla never finds (LExecutor.java:1489). Units
     * move in and out halfway.
     */
    @Test
    void aWorldProcessorQueriesUnitsAndBuildings(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("container", 16, 16, Team.sharded);
            run.place("copper-wall", 17, 10, Team.crux);
            run.place("duo", 4, 10, Team.sharded);
            run.processor("world-processor", 2, 2, Team.sharded, """
                query circle unit null 10 10 4 4
                sensor an @queries @size
                read a0 @queries 0
                read a1 @queries 1
                read a2 @queries 2
                read a3 @queries 3
                read a4 @queries 4
                read a5 @queries 5
                read a6 @queries 6
                read a7 @queries 7
                read a8 @queries 8
                query rect unit @sharded 6 6 8 8
                sensor bn @queries @size
                read b0 @queries 0
                read b1 @queries 1
                read b2 @queries 2
                read b3 @queries 3
                query circle building null 14 14 6 6
                sensor cn @queries @size
                read c0 @queries 0
                read c1 @queries 1
                query rect building @crux 0 0 30 30
                sensor dn @queries @size
                read d0 @queries 0
                query circle bullet null 10 10 8 8
                sensor en @queries @size
                """);
            // the first is in the circle's square but not the circle: the last result takes its place, as in vanilla
            float[][] sharded = {{6.5f, 6.5f}, {9, 11}, {11, 9}, {12, 12}, {10, 13}, {7, 10}};
            for(float[] at : sharded) run.dummy("dagger", Team.sharded, at[0] * 8f, at[1] * 8f);
            run.dummy("flare", Team.sharded, 11 * 8f, 11 * 8f);
            // within the radius only with half its hit size added
            run.dummy("dagger", Team.crux, 14.3f * 8f, 10 * 8f);
            run.dummy("dagger", Team.crux, 10 * 8f, 7 * 8f);
            run.dummy("dagger", Team.crux, 14 * 8f, 14 * 8f);
            run.at(200, () -> run.unit(1, (v, s) -> {
                v.set(20 * 8f, 20 * 8f);
                s.set(20 * 8f, 20 * 8f);
            }));
            run.at(400, () -> run.unit(8, (v, s) -> {
                v.set(10 * 8f, 11 * 8f);
                s.set(10 * 8f, 11 * 8f);
            }));
            run.run(600);
            assertNotEquals("0.0", run.variable(2, 2, "an"), "the circle found units");
            assertEquals("1.0", run.variable(2, 2, "dn"), "the crux wall");
            assertEquals("0.0", run.variable(2, 2, "en"), "no bullets, as vanilla");
        }
    }

    /**
     * A world processor's {@code setprop} moves a player's dagger, on x then later on y, and it stays where it was put
     * (vanilla snaps its interpolation, UnitComp.java:366, :370); turns it (which the interpolation undoes, as vanilla's
     * does), and sets an AI mace's health, shield, team, item and status; {@code sensor} reads every {@code LAccess} of
     * both every frame.
     */
    @Test
    void setpropMovesUnitsAndSensorsReadThem(){
        var sensors = new StringBuilder();
        for(var access : mindustry.logic.LAccess.all){
            sensors.append("sensor d_").append(access.name()).append(" d @").append(access.name()).append('\n');
            sensors.append("sensor m_").append(access.name()).append(" m @").append(access.name()).append('\n');
        }
        try(var run = new UnitLogicRun(40, Map.of())){
            run.spawn("dagger", Team.sharded, 10 * 8f, 10 * 8f);
            run.spawn("mace", Team.sharded, 14 * 8f, 10 * 8f);
            run.player(0, "oracle");
            run.processor("world-processor", 4, 4, Team.sharded, """
                fetch unit d @sharded 0 @dagger
                fetch unit m @sharded 0 @mace
                jump read equal moved 2
                jump read lessThan @tick 30
                jump second equal moved 1
                set moved 1
                setprop @x d 20
                setprop @rotation d 45
                setprop @flag d 9
                setprop @copper d 10
                setprop @health m 100
                setprop @shield m 15
                setprop @velocityX m 1
                setprop @armor m 2
                setprop @status-wet m 5
                setprop @lead m 7
                setprop @team m 2
                jump read always
                second:
                jump read lessThan @tick 60
                set moved 2
                setprop @y d 18
                read:
                sensor dw d @status-wet
                sensor mw m @status-wet
                sensor mc m @copper
                sensor ml m @lead
                """ + sensors);
            run.run(600);
            Unit dagger = run.shard.units().get(0);
            assertEquals(20f, dagger.x / 8f, 0.01f, "the player's dagger stayed where setprop put it");
            assertEquals(18f, dagger.y / 8f, 0.01f, "the player's dagger stayed where setprop put it");
            assertEquals(2, run.shard.units().get(1).team, "the mace changed teams");
        }
    }

    /**
     * A world processor runs {@code spawn} with and without the effect, {@code status} on and off, every
     * {@code fetch}, {@code bullet} from a unit's weapon, an item turret and a power turret, {@code explosion} for a
     * team, for none and piercing, {@code spawnwave} natural and at two points, and keeps rain going with
     * {@code weatherset} until it lets it fade.
     */
    @Test
    void aWorldProcessorRunsItsStatements(){
        try(var run = new UnitLogicRun(48, Map.of())){
            run.place("core-shard", 30, 12, Team.sharded);
            run.place("container", 21, 11, Team.sharded);
            run.waveGroup("flare", 2, -1, -1, null);
            run.waveGroup("horizon", 1, 30, 30, "overclock");
            run.processor("world-processor", 4, 4, Team.sharded, """
                jump loop equal ran 1
                set ran 1
                spawn @dagger 10 10 90 @sharded d true
                spawn @flare 12 10 0 @crux f false
                status false @status-wet d 10
                status false @status-burning f 5
                fetch unitCount uc @sharded 0 @dagger
                fetch unitCount uca @crux 0
                fetch unit u0 @crux 0 @flare
                fetch unit u9 @crux 9
                fetch core c0 @sharded 0
                fetch coreCount cc @sharded 0
                fetch build b0 @sharded 0 @world-processor
                fetch build b1 @sharded 1
                fetch buildCount bc @sharded 0
                fetch buildCount bcc @sharded 0 @container
                fetch playerCount pc @sharded 0
                fetch player p0 @sharded 0
                bullet r1 @dagger 0 10 14 0 @sharded d -1 1 1 -1 -1
                bullet r2 @duo @copper 10 16 90 @crux null 20 1 1 -1 -1
                bullet r3 @lancer 0 20 20 180 null f -1 1 1 -1 -1
                explosion @crux 10 10 3 30 true true false true
                explosion null 21 11 2 40 false true false true
                explosion @sharded 12 10 2 20 true true true false
                spawnwave 0 0 true
                spawnwave 30 30 false
                spawnwave 5 30 false
                weathersense w0 @rain
                weatherset @rain true
                weathersense w1 @rain
                status true @status-wet d 0
                loop:
                set t @tick
                weathersense w @rain
                sensor dh d @health
                sensor fh f @health
                sensor fd f @dead
                fetch unitCount live @crux 0
                jump skip greaterThan t 300
                weatherset @rain true
                skip:
                end
                """);
            run.run(700);
            assertEquals(7, run.mostUnits, "two units and a wave of five spawned");
            assertEquals(3, run.seen("Explosion"), "three explosions");
            assertEquals(1, run.seen("Created"), "the rain was created once and kept");
            assertTrue(run.seen("UnitSpawned") >= 6, "spawn effects for the dagger and the wave");
        }
    }

    /**
     * W3.7's {@code ucontrol mine} (WP45): a mono finds the copper with {@code ulocate ore}, approaches it and mines it;
     * {@code MinerComp} fills its stack, a flight after each item, and hands a full stack to the core in range.
     */
    @Test
    void aMonoMinesWhereItIsTold(){
        var ores = new HashMap<Integer, String>();
        for(int x = 20; x < 23; x++){
            for(int y = 14; y < 17; y++) ores.put(y * 40 + x, "ore-copper");
        }
        try(var run = new UnitLogicRun(40, ores)){
            run.place("core-shard", 10, 10, Team.sharded);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                ubind @mono
                ulocate ore core true @copper ox oy found b
                ucontrol approach ox oy 2 0 0
                ucontrol mine ox oy 0 0 0
                sensor m @unit @mining
                sensor st @unit @totalItems
                """);
            run.spawn("mono", Team.sharded, 12 * 8f, 12 * 8f);
            run.run(1_200);
            assertTrue(run.shard.buildings().at(10, 10).items.get(run.shard.content().item("copper").id()) > 0, "full stacks reached the core");
        }
    }

    /**
     * W5.7's payload statements: a mega bound by a world processor takes a dagger ({@code ucontrol payTake true}) and
     * drops it ({@code payDrop}), lifts a container whole ({@code payTake false}) and sets it down elsewhere, then
     * {@code setprop @payloadType} gives it a dagger, takes it back ({@code null}), gives it a duo, ignores an item, and
     * gives it a dagger and a router, which it drops one by one. Vanilla saves and both load it mid-carry. Each frame
     * also compares what every unit carries.
     */
    @Test
    void aMegaCarriesWhatAWorldProcessorTellsIt(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("container", 14, 20, Team.sharded);
            run.give(14, 20, "copper", 30);
            run.processor("world-processor", 2, 2, Team.sharded, """
                set t @tick
                ubind @mega
                jump take2 greaterThan t 120
                ucontrol move 10 10 0 0 0
                ucontrol within 10 10 1 w 0
                jump 0 equal w false
                ucontrol payTake true 0 0 0 0
                end
                take2:
                jump take3 greaterThan t 240
                ucontrol move 20 10 0 0 0
                ucontrol within 20 10 1 w 0
                jump 0 equal w false
                ucontrol payDrop 0 0 0 0 0
                end
                take3:
                jump take4 greaterThan t 360
                ucontrol move 15 21 0 0 0
                ucontrol within 15 21 1 w 0
                jump 0 equal w false
                ucontrol payTake false 0 0 0 0
                end
                take4:
                jump props greaterThan t 480
                ucontrol move 24 20 0 0 0
                ucontrol within 24 20 1 w 0
                jump 0 equal w false
                ucontrol payDrop 0 0 0 0 0
                end
                props:
                jump drop greaterThan done 0
                setprop @payloadType @unit @dagger
                setprop @payloadType @unit null
                setprop @payloadType @unit @duo
                setprop @payloadType @unit @copper
                setprop @payloadType @unit @dagger
                setprop @payloadType @unit @router
                set done 1
                end
                drop:
                ucontrol move 30 30 0 0 0
                ucontrol within 30 30 1 w 0
                jump 0 equal w false
                ucontrol payDrop 0 0 0 0 0
                sensor pc @unit @payloadCount
                """);
            // 22 units north of where the mega stops: within its reach there (26), not yet as it arrives from the
            // south-west (about 28.6, inside the search radius of 30)
            run.spawn("dagger", Team.sharded, 10 * 8f, 10 * 8f + 22f);
            run.spawn("mega", Team.sharded, 5 * 8f, 5 * 8f);
            carried(run);
            run.saveAt(300);
            run.run(900);
            assertTrue(run.seen("PickedUnit") >= 1, "the mega took the dagger: " + run.seen);
            assertTrue(run.seen("PickedBuild") >= 1, "the mega lifted the container: " + run.seen);
            assertTrue(run.seen("Dropped") >= 3, "the mega dropped the dagger, the container and its last payloads: " + run.seen);
            assertNotNull(run.shard.buildings().at(24, 20), "the container stands where the mega set it down");
            int routers = 0;
            for(int x = 28; x <= 32; x++){
                for(int y = 28; y <= 32; y++){
                    var build = run.shard.buildings().at(x, y);
                    if(build != null && build.block().name().equals("router")) routers++;
                }
            }
            assertEquals(1, routers, "the router the mega was given stands at the drop point");
        }
    }

    /**
     * W5.7's {@code ucontrol payEnter}: a dagger walks onto a payload conveyor and enters it, rides to the end and is
     * dumped, then walks back and enters again; a mace enters a lone payload router, which dumps it; a mono flies over
     * an unpowered additive reconstructor, west of its centre (a unit may only enter a block it is over, and not from
     * its front), and enters it. Each entry sends {@code unitBuildingControlSelect} and gives the unit a
     * new id the next frame. Vanilla saves and both load it with units inside blocks. Each frame also compares what
     * the payload blocks hold.
     */
    @Test
    void unitsEnterPayloadBlocksWhenProcessorsTellThem(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("payload-conveyor", 20, 10, Team.sharded);
            run.place("payload-conveyor", 23, 10, Team.sharded);
            run.place("payload-router", 20, 20, Team.sharded);
            run.place("additive-reconstructor", 30, 30, Team.sharded);
            run.processor("logic-processor", 4, 4, Team.sharded, """
                ubind @dagger
                ucontrol move 20 10 0 0 0
                ucontrol payEnter 0 0 0 0 0
                """);
            run.processor("logic-processor", 4, 6, Team.sharded, """
                ubind @mace
                ucontrol move 20 20 0 0 0
                ucontrol payEnter 0 0 0 0 0
                """);
            run.processor("logic-processor", 4, 8, Team.sharded, """
                ubind @mono
                ucontrol move 29.4 30 0 0 0
                ucontrol payEnter 0 0 0 0 0
                """);
            run.spawn("dagger", Team.sharded, 14 * 8f, 10 * 8f);
            run.spawn("mace", Team.sharded, 14 * 8f, 20 * 8f);
            run.spawn("mono", Team.sharded, 24 * 8f, 30 * 8f);
            carried(run);
            int[][] blocks = {{20, 10}, {23, 10}, {20, 20}, {30, 30}};
            var held = new TreeSet<String>();
            run.also = run.also.andThen(at -> {
                for(int[] b : blocks){
                    var payload = run.shard.buildings().at(b[0], b[1]).getPayload();
                    assertEquals(payload(run, mindustry.Vars.world.build(b[0], b[1]).getPayload()), payload(run, payload),
                        at + ": the payload of the block at " + b[0] + "," + b[1]);
                    if(payload instanceof Payload.UnitPayload unit) held.add(b[0] + "," + b[1] + " " + unit.unit().type().name());
                }
            });
            run.saveAt(400);
            run.run(900);
            assertTrue(run.seen("ControlSelect") >= 4, "the dagger entered twice, the mace and the mono once each: " + run.seen);
            System.out.println("units the payload blocks held: " + held);
            assertTrue(held.containsAll(List.of("20,10 dagger", "23,10 dagger", "20,20 mace", "30,30 mono")),
                "the dagger rode both conveyors, the mace the router, the mono the reconstructor: " + held);
        }
    }

    /** Each frame, besides the run's comparison: what every unit carries, in order. */
    private static void carried(UnitLogicRun run){
        run.also = run.also.andThen(at -> {
            var vanilla = new ArrayList<String>();
            for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
                if(!(u instanceof mindustry.gen.Payloadc pay)) continue;
                var line = new ArrayList<String>();
                for(mindustry.world.blocks.payloads.Payload carried : pay.payloads()) line.add(payload(run, carried));
                vanilla.add(run.name(u) + " " + line);
            }
            var shard = new ArrayList<String>();
            for(int i = 0; i < run.shard.units().size(); i++){
                Unit u = run.shard.units().get(i);
                if(u.payloads == null) continue;
                var line = new ArrayList<String>();
                for(int j = 0; j < u.payloads.size(); j++) line.add(payload(run, u.payloads.get(j)));
                shard.add(run.name(u) + " " + line);
            }
            assertEquals(vanilla, shard, at + ": what the units carry");
        });
    }

    private static String payload(UnitLogicRun run, Object payload){
        return switch(payload){
            case null -> "-";
            case mindustry.world.blocks.payloads.UnitPayload p -> p.unit.type.name + " " + run.name(p.unit) + " at "
                + UnitLogicRun.bits(p.unit.x) + "," + UnitLogicRun.bits(p.unit.y) + " rot " + UnitLogicRun.bits(p.unit.rotation);
            case mindustry.world.blocks.payloads.BuildPayload p -> p.block().name + " t" + p.build.team.id;
            case Payload.UnitPayload p -> p.unit().type().name() + " " + run.name(p.unit()) + " at " + UnitLogicRun.bits(p.unit().x) + ","
                + UnitLogicRun.bits(p.unit().y) + " rot " + UnitLogicRun.bits(p.unit().rotation);
            case Payload.BuildPayload p -> p.building().block().name() + " t" + p.building().team;
            default -> payload.toString();
        };
    }
}
