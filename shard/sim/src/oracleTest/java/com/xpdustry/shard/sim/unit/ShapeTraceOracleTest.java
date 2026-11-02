package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.world.blocks.environment.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for every movement shape (WP44): each test spawns units of one shape on the same fixture, in vanilla and in
 * Shard, and drives them with one scripted input for {@value #FRAMES} frames. The fixture has a wall line, a large
 * wall, a pool of deep water with a wall in it, shallow water ({@code shallow-water}, which vanilla doesn't mark
 * {@code shallow}) and sand water (which it does), a row of boulders, and enemy walls and power nodes to crush. The
 * script heads for waypoints across all of them with {@code movePref}, {@code rotateMove} or {@code moveAt} (one per
 * leg of the route), looks at them, brakes with {@code approach} at the end of each leg and, for types that can,
 * boosts on some legs.
 *
 * <p>The water floors keep their {@code wet} status, which {@code StatusComp} (WP42) applies on both sides: units in
 * shallow and sand water move at 0.94 ({@code WetShapeTraceOracleTest} also compares their statuses). Abilities run on
 * both sides (WP62): a drowned neoplasm crawler splashes its puddles, which draw from the random.
 *
 * <p>Tolerance: bit for bit. Every frame, each unit's position, rotation, velocity, drag, elevation, drown time, death
 * and the state of its shape (a mech's legs and walk, a tank's treads and slowdown, a crawler's segments, every leg of
 * a legged unit) equal vanilla's, as do the enemy buildings' health, the boulders' tiles and the random's state, so
 * every draw the movement makes (a crawler's dust, a drowning unit's effect, a splash's pitch, new legs) is kept.
 */
class ShapeTraceOracleTest{
    static final int SIZE = 64, FRAMES = 600, LEG = 60;
    /** How a leg of the route steers: {@code movePref}, {@code rotateMove}, {@code moveAt}. */
    static final int PREF = 0, ROTATE = 1, AT = 2;
    static final List<String> WATER = List.of("deep-water", "shallow-water", "sand-water");

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void mechsWalkAsVanilla(){
        var scene = new Scene();
        // against the wall line, into the shallow and deep water, around the large wall
        scene.spawn("dagger", 17, 12, 0f, new int[]{PREF, ROTATE, AT}, false, w(23, 12), w(17, 16), w(16, 30), w(20, 26), w(12, 28), w(24, 30));
        scene.spawn("dagger", 22, 6, 90f, new int[]{ROTATE, PREF}, false, w(18, 6), w(26, 29), w(14, 26), w(15, 36), w(22, 18));
        // a large mech drowns only where the eight tiles around it are deep
        scene.spawn("fortress", 11, 31, 180f, new int[]{PREF, ROTATE}, false, w(6, 31), w(10, 26), w(5, 26), w(11, 30));
        scene.spawn("fortress", 18, 30, 180f, new int[]{PREF, ROTATE}, false, w(13, 30), w(18, 26), w(13, 26), w(17, 32));
        // through the boulders, which mechs neither collide with nor break
        scene.spawn("mace", 30, 20, 45f, new int[]{AT, ROTATE, PREF}, false, w(34, 10), w(40, 16), w(28, 8), w(36, 24));
        // through the shallow water into the deep water
        scene.spawn("dagger", 18, 27, 180f, new int[]{PREF, ROTATE, AT}, false, w(14, 27), w(11, 27), w(10, 30), w(12, 25));
        scene.run();
        for(int i : new int[]{0, 1}) assertTrue(scene.facts(i).touched, "dagger #" + i + " pressed against a wall");
        assertTrue(scene.facts(5).deep && scene.facts(5).shallow, "a dagger waded into deep water");
        assertTrue(scene.facts(2).diedAt > 0, "the fortress in the deep water drowned");
        assertTrue(scene.facts(3).deep && scene.facts(3).diedAt == -1, "the fortress at its edge did not");
        assertTrue(scene.facts(4).boulder, "the mace walked into the boulders");
        assertEquals(0, scene.brokenBoulders(), "mechs break no boulders");
        scene.assertNeverOnTheWall(0, 1, 2, 3, 4, 5);
    }

    @Test
    void boostingMechsFlyOverWallsAndLand(){
        var scene = new Scene();
        // boosts on odd legs: over the wall line and over water; lands on stone, in shallow water and in deep water
        scene.spawn("nova", 17, 12, 0f, new int[]{PREF, ROTATE}, true, w(24, 12), w(16, 10), w(24, 14), w(15, 30), w(18, 26), w(10, 29), w(26, 6));
        scene.spawn("vela", 23, 18, 180f, new int[]{ROTATE, PREF, AT}, true, w(16, 18), w(23, 16), w(26, 28), w(14, 35), w(9, 29), w(9, 30));
        scene.spawn("nova", 30, 30, 90f, new int[]{AT, PREF}, true, w(34, 36), w(21, 26), w(17, 28), w(28, 31), w(18, 34));
        scene.run();
        for(int i = 0; i < 3; i++) assertTrue(scene.facts(i).flew && scene.facts(i).landed, "mech #" + i + " boosted and landed");
        assertTrue(scene.facts(0).onWall, "the first nova flew over the wall line");
        assertTrue(scene.facts(1).deep, "the vela flew over the deep water");
    }

    @Test
    void legsCrossWallsAndWater(){
        var scene = new Scene();
        // over the wall line and the large wall, then into the deep water
        scene.spawn("atrax", 17, 12, 0f, new int[]{PREF, AT}, false, w(24, 12), w(25, 28), w(16, 28), w(8, 28), w(9, 30));
        // a splashing leg breaks the boulders it lands on
        scene.spawn("arkyid", 30, 6, 0f, new int[]{PREF, ROTATE}, false, w(41, 11), w(30, 9), w(40, 6), w(34, 11));
        scene.run();
        assertTrue(scene.facts(0).onWall, "the atrax walked over the wall line");
        assertTrue(scene.facts(0).deep, "the atrax walked into deep water");
        assertTrue(scene.brokenBoulders() > 0, "the arkyid's legs broke boulders");
    }

    @Test
    void tanksCrushAndSlowOverWalls(){
        var scene = new Scene();
        // through the boulders, then over the enemy walls, which it crushes
        scene.spawn("conquer", 36, 14, 270f, new int[]{PREF, AT}, false, w(36, 5), w(48, 14), w(48, 21), w(47, 26), w(50, 21));
        // next to the enemy power nodes, which are fragile
        scene.spawn("locus", 42, 34, 270f, new int[]{PREF, ROTATE}, false, w(47, 30), w(50, 30), w(44, 26));
        // against the wall line, into the deep water, where it drowns only if all of it is deep
        scene.spawn("stell", 17, 8, 0f, new int[]{PREF, ROTATE, AT}, false, w(24, 9), w(17, 18), w(16, 29), w(9, 29), w(10, 28));
        scene.run();
        assertTrue(scene.brokenBoulders() > 0, "the conquer broke boulders");
        assertTrue(scene.crushed() > 0, "the conquer crushed enemy walls");
        assertTrue(scene.nodesLeft() < 2, "a fragile power node was crushed");
        assertTrue(scene.facts(2).touched && scene.facts(2).deep, "the stell hit the wall and reached deep water");
        scene.assertNeverOnTheWall(2);
    }

    @Test
    void crawlersCrushAndDrown(){
        var scene = new Scene();
        // over the enemy walls, which it crushes
        scene.spawn("latum", 38, 24, 0f, new int[]{PREF, AT}, false, w(50, 21), w(40, 30), w(30, 28), w(24, 30));
        // in the deep pool, where it drowns once most of the tiles under it are deep
        scene.spawn("renale", 10, 31, 180f, new int[]{PREF, ROTATE}, false, w(6, 31), w(11, 26), w(6, 26), w(11, 31));
        // over the wall line, which crawlers cross as legs do
        scene.spawn("renale", 17, 12, 0f, new int[]{AT, PREF}, false, w(24, 12), w(18, 16), w(31, 10), w(38, 10));
        scene.run();
        assertTrue(scene.crushed() > 0, "the latum crushed enemy walls");
        assertTrue(scene.facts(1).diedAt > 0, "the renale in the deep pool drowned");
        assertTrue(scene.facts(2).onWall, "the other renale crawled over the wall line");
    }

    @Test
    void navalUnitsKeepToTheWater(){
        var scene = new Scene();
        // deep water, shallow water, sand water, the shore and the wall in the water
        scene.spawn("risso", 6, 26, 0f, new int[]{PREF, AT}, false, w(16, 26), w(9, 28), w(10, 36), w(4, 33), w(19, 30), w(12, 24));
        scene.spawn("retusa", 11, 31, 180f, new int[]{PREF, ROTATE}, false, w(5, 29), w(16, 35), w(8, 26), w(14, 23));
        scene.spawn("minke", 15, 36, 90f, new int[]{AT, PREF}, false, w(6, 36), w(12, 30), w(17, 25), w(4, 24));
        scene.run();
        for(int i = 0; i < 3; i++){
            var facts = scene.facts(i);
            assertTrue(facts.touched && facts.diedAt == -1, "naval #" + i + " kept to the water against the shore");
        }
        assertTrue(scene.facts(0).deep && scene.facts(0).shallow && scene.facts(0).sand, "the risso sailed every water");
    }

    @Test
    void hoveringUnitsCrossWaterButNotWalls(){
        var scene = new Scene();
        scene.spawn("elude", 17, 12, 0f, new int[]{PREF, AT, ROTATE}, false, w(24, 12), w(18, 26), w(6, 30), w(25, 30), w(26, 27), w(10, 35));
        scene.run();
        var facts = scene.facts(0);
        assertTrue(facts.touched && facts.deep && facts.diedAt == -1, "the elude hit the walls and hovered over deep water");
        scene.assertNeverOnTheWall(0);
    }

    static float[] w(int tx, int ty){
        return new float[]{tx * 8f, ty * 8f};
    }

    /** The fixture in both worlds, the units on it and their scripts. */
    static final class Scene{
        final World world;
        final List<mindustry.gen.Unit> vanilla = new ArrayList<>();
        final List<Unit> shard = new ArrayList<>();
        final List<int[]> enemies = new ArrayList<>(), boulders = new ArrayList<>();
        final List<Facts> seen = new ArrayList<>();

        Scene(){
            Vanilla.emptyWorld(SIZE, SIZE);
            state.rules.disableUnitCap = true;
            state.rules.waves = false;
            world = UnitWorlds.world(SIZE, SIZE, 1);

            for(int ty = 4; ty <= 20; ty++) block("stone-wall", 0, 20, ty);
            block("copper-wall-large", 0, 25, 28);
            for(int tx = 4; tx <= 17; tx++){
                for(int ty = 24; ty <= 37; ty++) floor(ty >= 34 ? "sand-water" : tx <= 13 ? "deep-water" : "shallow-water", tx, ty);
            }
            block("stone-wall", 0, 8, 28);
            for(int tx = 30; tx <= 41; tx++){
                block("boulder", 0, tx, 10);
                boulders.add(new int[]{tx, 10});
            }
            for(int tx = 44; tx <= 50; tx++){
                for(int ty = 20; ty <= 22; ty++){
                    block("titanium-wall", Team.crux.id, tx, ty);
                    enemies.add(new int[]{tx, ty});
                }
            }
            for(int tx : new int[]{46, 48}){
                block("power-node", Team.crux.id, tx, 30);
                enemies.add(new int[]{tx, 30});
            }
            // the same random from here on: every draw the units make must match
            world.rand().setState(arc.math.Mathf.rand.getState(0), arc.math.Mathf.rand.getState(1));
        }

        void block(String block, int team, int x, int y){
            mindustry.Vars.world.tile(x, y).setBlock(content.block(block), Team.get(team), 0);
            world.buildings().setBlock(x, y, VanillaWorlds.VANILLA.block(block), team, 0, Cause.SERVER);
        }

        void floor(String floor, int x, int y){
            mindustry.Vars.world.tile(x, y).setFloor((Floor)content.block(floor));
            world.buildings().setFloor(x, y, VanillaWorlds.VANILLA.block(floor), Cause.SERVER);
        }

        void spawn(String type, int tx, int ty, float rotation, int[] modes, boolean boosts, float[]... route){
            int index = vanilla.size();
            var script = new Script(index, modes, boosts, route);
            mindustry.gen.Unit v = content.unit(type).spawn(Team.sharded, tx * 8f, ty * 8f, rotation);
            v.controller(new VanillaControl(script));
            vanilla.add(v);
            Unit s = Unit.spawn(world, VanillaWorlds.VANILLA.unit(type), Team.sharded.id, tx * 8f, ty * 8f, rotation);
            s.controller(new ShardControl(script));
            shard.add(s);
            seen.add(new Facts());
        }

        void run(){
            Vanilla.runPosted();
            compare("spawned");
            for(int frame = 0; frame < FRAMES; frame++){
                Script.frame = frame;
                Vanilla.frame();
                world.tick();
                compare("frame " + frame);
                for(int i = 0; i < shard.size(); i++) seen.get(i).see(shard.get(i), frame);
            }
            System.out.println(summary());
        }

        Facts facts(int index){
            return seen.get(index);
        }

        void assertNeverOnTheWall(int... units){
            for(int i : units) assertFalse(seen.get(i).onWall, shard.get(i).type().name() + " #" + i + " never stood on the wall line");
        }

        /** How many of the two enemy power nodes stand. */
        long nodesLeft(){
            return enemies.stream().filter(e -> e[1] == 30 && world.buildings().at(e[0], e[1]) != null).count();
        }

        /** How many boulders are gone. */
        long brokenBoulders(){
            return boulders.stream().filter(b -> world.tiles().block(world.tiles().pos(b[0], b[1])) == 0).count();
        }

        /** How many enemy buildings were hurt or destroyed. */
        long crushed(){
            return enemies.stream().filter(e -> {
                Building b = world.buildings().at(e[0], e[1]);
                return b == null || b.health < b.maxHealth;
            }).count();
        }

        String summary(){
            var out = new StringBuilder("after " + FRAMES + " frames:");
            for(int i = 0; i < shard.size(); i++){
                Unit unit = shard.get(i);
                out.append(String.format("%n  %s #%d at (%.1f, %.1f): %s", unit.type().name(), i, unit.x / 8f, unit.y / 8f, seen.get(i)));
            }
            return out.append(String.format("%n  %d boulders broken, %d enemy buildings crushed", brokenBoulders(), crushed())).toString();
        }

        /** Every difference of one frame, in one failure: the units' state, the buildings, the boulders, the random. */
        void compare(String at){
            var d = new Diffs();
            for(int i = 0; i < vanilla.size(); i++){
                var v = vanilla.get(i);
                var s = shard.get(i);
                String unit = v.type.name + " #" + i;
                d.eq(unit + " added", v.isAdded(), s.isAdded());
                d.eq(unit + " dead", v.dead, s.dead);
                d.same(unit + " x", v.x, s.x);
                d.same(unit + " y", v.y, s.y);
                d.same(unit + " rotation", v.rotation, s.rotation);
                d.same(unit + " vel.x", v.vel.x, s.vel.x);
                d.same(unit + " vel.y", v.vel.y, s.vel.y);
                d.same(unit + " drag", v.drag, s.drag);
                d.same(unit + " elevation", v.elevation, s.elevation);
                d.same(unit + " drownTime", v.drownTime, s.drownTime);
                shape(d, unit, v, s.shape);
            }
            for(int[] e : enemies){
                var v = mindustry.Vars.world.build(e[0], e[1]);
                Building s = world.buildings().at(e[0], e[1]);
                String where = "enemy at " + e[0] + "," + e[1];
                d.eq(where + " exists", v != null, s != null);
                if(v != null && s != null) d.same(where + " health", v.health, s.health);
            }
            for(int[] b : boulders){
                d.eq("boulder at " + b[0] + "," + b[1], (int)mindustry.Vars.world.tile(b[0], b[1]).block().id,
                    (int)world.tiles().block(world.tiles().pos(b[0], b[1])));
            }
            long v0 = arc.math.Mathf.rand.getState(0), v1 = arc.math.Mathf.rand.getState(1);
            if(v0 != world.rand().getState(0) || v1 != world.rand().getState(1)){
                d.add("the random is " + draws(world.rand(), v0, v1));
            }
            if(!d.isEmpty()){
                var units = new StringBuilder();
                for(int i = 0; i < vanilla.size(); i++){
                    var v = vanilla.get(i);
                    var s = shard.get(i);
                    units.append(String.format("%n  %s #%d vanilla (%s, %s) rot %s vel (%s, %s) elev %s | shard (%s, %s) rot %s vel (%s, %s) elev %s",
                        v.type.name, i, v.x, v.y, v.rotation, v.vel.x, v.vel.y, v.elevation, s.x, s.y, s.rotation, s.vel.x, s.vel.y, s.elevation));
                }
                fail(at + ": " + String.join("; ", d.list) + units);
            }
        }

        /** How many draws Shard's random is ahead of or behind vanilla's ({@code v0}, {@code v1}). */
        static String draws(com.xpdustry.shard.common.math.Rand shard, long v0, long v1){
            var ahead = shard.copy();
            var behind = new com.xpdustry.shard.common.math.Rand(v0, v1);
            for(int n = 1; n <= 64; n++){
                behind.nextLong();
                if(behind.getState(0) == shard.getState(0) && behind.getState(1) == shard.getState(1)) return n + " draws ahead of vanilla's";
                ahead.nextLong();
                if(ahead.getState(0) == v0 && ahead.getState(1) == v1) return n + " draws behind vanilla's";
            }
            return "not within 64 draws of vanilla's";
        }

        static void shape(Diffs d, String unit, mindustry.gen.Unit v, MoveShape shape){
            switch(shape){
                case MoveShape.Mech mech -> {
                    d.same(unit + " baseRotation", f(v, "baseRotation"), mech.baseRotation);
                    d.same(unit + " walkTime", f(v, "walkTime"), mech.walkTime);
                    d.same(unit + " walkExtension", f(v, "walkExtension"), mech.walkExtension);
                    d.eq(unit + " walked", b(v, "walked"), mech.walked);
                }
                case MoveShape.Tank tank -> {
                    d.same(unit + " treadTime", f(v, "treadTime"), tank.treadTime);
                    d.same(unit + " lastSlowdown", f(v, "lastSlowdown"), tank.lastSlowdown);
                    d.eq(unit + " walked", b(v, "walked"), tank.walked);
                    d.eq(unit + " lastDeepFloor", floorId(v, "lastDeepFloor"), tank.lastDeepFloor);
                }
                case MoveShape.Crawl crawl -> {
                    d.same(unit + " segmentRot", f(v, "segmentRot"), crawl.segmentRot);
                    d.same(unit + " crawlTime", f(v, "crawlTime"), crawl.crawlTime);
                    d.same(unit + " lastCrawlSlowdown", f(v, "lastCrawlSlowdown"), crawl.lastCrawlSlowdown);
                    d.eq(unit + " lastDeepFloor", floorId(v, "lastDeepFloor"), crawl.lastDeepFloor);
                }
                case MoveShape.Legs legs -> {
                    d.same(unit + " totalLength", f(v, "totalLength"), legs.totalLength);
                    d.same(unit + " moveSpace", f(v, "moveSpace"), legs.moveSpace);
                    d.same(unit + " baseRotation", f(v, "baseRotation"), legs.baseRotation);
                    var offset = (arc.math.geom.Vec2)get(v, "curMoveOffset");
                    d.same(unit + " curMoveOffset.x", offset.x, legs.curMoveOffset.x);
                    d.same(unit + " curMoveOffset.y", offset.y, legs.curMoveOffset.y);
                    d.eq(unit + " lastDeepFloor", floorId(v, "lastDeepFloor"), legs.lastDeepFloor);
                    var vl = (mindustry.entities.Leg[])get(v, "legs");
                    d.eq(unit + " legs", vl.length, legs.legs.length);
                    for(int i = 0; i < Math.min(vl.length, legs.legs.length); i++){
                        String leg = unit + " leg " + i;
                        d.same(leg + " joint.x", vl[i].joint.x, legs.legs[i].joint.x);
                        d.same(leg + " joint.y", vl[i].joint.y, legs.legs[i].joint.y);
                        d.same(leg + " base.x", vl[i].base.x, legs.legs[i].base.x);
                        d.same(leg + " base.y", vl[i].base.y, legs.legs[i].base.y);
                        d.same(leg + " stage", vl[i].stage, legs.legs[i].stage);
                        d.eq(leg + " group", vl[i].group, legs.legs[i].group);
                        d.eq(leg + " moving", vl[i].moving, legs.legs[i].moving);
                    }
                }
                default -> {}
            }
        }
    }

    /** What one unit did during a run, for the checks that the fixture exercised its shape. */
    static final class Facts{
        int diedAt = -1;
        /** Stood on the wall line ({@code x = 20}, {@code y = 4..20}); was pressed against a tile its shape collides with. */
        boolean onWall, touched;
        boolean flew, landed, deep, shallow, sand, boulder;

        void see(Unit unit, int frame){
            if(!unit.isAdded()){
                if(diedAt == -1) diedAt = frame;
                return;
            }
            var tiles = unit.world().tiles();
            int tx = unit.tileX(), ty = unit.tileY();
            if(tx == 20 && ty >= 4 && ty <= 20) onWall = true;
            if(unit.isFlying()) flew = true;
            else if(flew && unit.isGrounded()) landed = true;
            if(tiles.inBounds(tx, ty)){
                String floor = unit.world().content().block(tiles.floor(tiles.pos(tx, ty))).name();
                deep |= floor.equals("deep-water");
                shallow |= floor.equals("shallow-water");
                sand |= floor.equals("sand-water");
                boulder |= ty >= 9 && ty <= 11 && tx >= 30 && tx <= 41;
            }
            var solidity = unit.shape.solidity(unit);
            if(solidity != null){
                // the tile hitbox, half a world unit larger
                float size = Math.min(unit.hitSize * 0.66f, 7.8f) + 1f;
                for(int x = Math.round((unit.x - size / 2) / 8f); x <= Math.round((unit.x + size / 2) / 8f); x++){
                    for(int y = Math.round((unit.y - size / 2) / 8f); y <= Math.round((unit.y + size / 2) / 8f); y++){
                        if(com.xpdustry.shard.sim.spatial.TileSolidity.of(unit.world()).solid(solidity, x, y)) touched = true;
                    }
                }
            }
        }

        @Override
        public String toString(){
            return "died at " + diedAt + (onWall ? ", on the wall" : "") + (touched ? ", touched" : "") + (flew ? ", flew" : "")
                + (landed ? ", landed" : "") + (deep ? ", deep" : "") + (shallow ? ", shallow" : "") + (sand ? ", sand" : "")
                + (boulder ? ", boulders" : "");
        }
    }

    /** The differences found in one frame. */
    static final class Diffs{
        final List<String> list = new ArrayList<>();

        void same(String what, float vanilla, float shard){
            if(Float.floatToIntBits(vanilla) != Float.floatToIntBits(shard)) list.add(what + ": " + shard + ", vanilla " + vanilla);
        }

        void eq(String what, Object vanilla, Object shard){
            if(!Objects.equals(vanilla, shard)) list.add(what + ": " + shard + ", vanilla " + vanilla);
        }

        void add(String difference){
            list.add(difference);
        }

        boolean isEmpty(){
            return list.isEmpty();
        }
    }

    /** One unit's route, steering and boosting; the same on both sides. */
    record Script(int index, int[] modes, boolean boosts, float[][] route){
        static int frame;

        int leg(){
            return frame / LEG;
        }

        float[] target(){
            return route[leg() % route.length];
        }

        boolean braking(){
            return frame % LEG >= LEG - 10;
        }

        int mode(){
            return modes[leg() % modes.length];
        }

        boolean boost(){
            return boosts && leg() % 2 == 1;
        }
    }

    /** The script on vanilla's API. */
    static final class VanillaControl implements mindustry.entities.units.UnitController{
        final Script script;
        mindustry.gen.Unit unit;

        VanillaControl(Script script){
            this.script = script;
        }

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }

        @Override
        public void updateUnit(){
            float[] target = script.target();
            if(script.braking()){
                unit.approach(arc.math.geom.Vec2.ZERO);
            }else{
                var vec = new arc.math.geom.Vec2(target[0], target[1]).sub(unit.x, unit.y).limit(unit.speed());
                switch(script.mode()){
                    case PREF -> unit.movePref(vec);
                    case ROTATE -> unit.rotateMove(vec);
                    default -> unit.moveAt(vec);
                }
            }
            unit.lookAt(target[0], target[1]);
            if(unit.type.canBoost) unit.updateBoosting(script.boost());
        }
    }

    /** The same script on Shard's API. */
    static final class ShardControl implements UnitController{
        final Script script;

        ShardControl(Script script){
            this.script = script;
        }

        @Override
        public void updateUnit(Unit unit){
            float[] target = script.target();
            if(script.braking()){
                unit.approach(new Vec2());
            }else{
                var vec = new Vec2(target[0], target[1]).sub(unit.x, unit.y).limit(unit.speed());
                switch(script.mode()){
                    case PREF -> unit.movePref(vec);
                    case ROTATE -> unit.rotateMove(vec);
                    default -> unit.moveAt(vec);
                }
            }
            unit.lookAt(target[0], target[1]);
            if(unit.params().canBoost()) unit.updateBoosting(script.boost());
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    static Object get(Object target, String name){
        for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()){
            try{
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }catch(NoSuchFieldException e){
                // look further up
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }
        throw new AssertionError(target.getClass().getSimpleName() + " has no field " + name);
    }

    static float f(Object target, String name){
        return (float)get(target, name);
    }

    static boolean b(Object target, String name){
        return (boolean)get(target, name);
    }

    /** A {@code Floor} field as a content id, or -1 for null. */
    static int floorId(Object target, String name){
        var floor = (Floor)get(target, name);
        return floor == null ? -1 : floor.id;
    }
}
