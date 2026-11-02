package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.ctype.*;
import mindustry.ctype.ContentType;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.payloads.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: Shard's projections encode to vanilla's bytes. Every unit type, in two states (as created, and with
 * statuses, an item stack, a carried unit, plans with configs, mounts, abilities, a tether, a {@code CommandAI} with
 * orders), and players with and without a unit: {@code writeSync} and {@code write} equal vanilla's byte for byte.
 * Entity snapshots' reused {@link UnitView} writes the same sync bytes. A unit's save body read back through
 * {@link UnitSink} writes what vanilla writes after its own {@code read()}.
 * {@link WorldWireContext} answers every method as vanilla's world does.
 */
class EntitySyncOracleTest{
    static final int SIZE = 50;
    static final Epoch EPOCH = Epoch.vanilla();
    static final EntityProjector PROJECTOR = new EntityProjector(EPOCH, BuildingCodecs.builtIn());
    /** One view for every unit of a test, as {@code EntitySnapshots} keeps one. */
    final UnitView view = new UnitView();

    World world;
    mindustry.gen.Building vanillaWall;
    Building shardWall;
    /** A building with a codec (the router), for block payloads. */
    mindustry.gen.Building vanillaRouter;
    Building shardRouter;
    mindustry.gen.Unit vanillaTarget;
    com.xpdustry.shard.sim.unit.Unit shardTarget;
    mindustry.gen.Player vanillaPlayer;
    com.xpdustry.shard.sim.unit.Player shardPlayer;

    @BeforeEach
    void worlds(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        world.part(WorldRules.class).rules.disableUnitCap = true;

        mindustry.Vars.world.tile(20, 20).setBlock(content.block("copper-wall"), Team.sharded, 0);
        vanillaWall = mindustry.Vars.world.build(20, 20);
        shardWall = world.buildings().create(EPOCH.block("copper-wall"), 1, 20, 20, 0, Cause.SERVER);
        mindustry.Vars.world.tile(22, 20).setBlock(content.block("router"), Team.sharded, 0);
        vanillaRouter = mindustry.Vars.world.build(22, 20);
        shardRouter = world.buildings().create(EPOCH.block("router"), 1, 22, 20, 0, Cause.SERVER);

        vanillaTarget = content.unit("dagger").spawn(Team.crux, 100f, 100f);
        shardTarget = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit("dagger"), 2);
        shardTarget.id(vanillaTarget.id);
        shardTarget.set(100f, 100f);
        shardTarget.add();

        vanillaPlayer = mindustry.gen.Player.create();
        vanillaPlayer.add();
        shardPlayer = com.xpdustry.shard.sim.unit.Player.create(world);
        shardPlayer.id(vanillaPlayer.id);
        shardPlayer.add();
        Vanilla.runPosted();
    }

    @Test
    void everyUnitTypeEncodesAsVanilla(){
        assertEquals(70, content.units().size);
        int index = 0;
        for(UnitType type : content.units()){
            mindustry.gen.Unit vanilla = type.create(Team.sharded);
            var shard = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit(type.name), 1);
            place(vanilla, shard, 40f + index, 60f + index * 0.5f, index * 5f);
            String name = type.name + " (" + vanilla.getClass().getSimpleName() + ")";
            assertEquals(vanilla.classId() & 0xff, EntityProjector.classId(shard), name + ": class");
            same(name + " as created", vanilla, shard);

            load(vanilla, shard, index);
            same(name + " loaded", vanilla, shard);
            if(vanilla instanceof Payloadc carrier){
                // Payload.write of a building: its block, version and writeAll, through its kind's codec
                carrier.addPayload(new BuildPayload(vanillaRouter));
                shard.payloads.add(new com.xpdustry.shard.sim.unit.Payload.BuildPayload(shardRouter));
                same(name + " carrying a building", vanilla, shard);
            }
            index++;
        }
    }

    @Test
    void playersEncodeAsVanilla(){
        same("a fresh player", vanillaPlayer, shardPlayer);

        vanillaPlayer.name = "[accent]oracle";
        shardPlayer.name = "[accent]oracle";
        vanillaPlayer.color.set(0x12345678);
        shardPlayer.color = 0x12345678;
        vanillaPlayer.admin = shardPlayer.admin = true;
        vanillaPlayer.typing = shardPlayer.typing = true;
        vanillaPlayer.shooting = shardPlayer.shooting = true;
        vanillaPlayer.boosting = shardPlayer.boosting = true;
        vanillaPlayer.mouseX = shardPlayer.mouseX = 123.25f;
        vanillaPlayer.mouseY = shardPlayer.mouseY = -7.5f;
        vanillaPlayer.selectedBlock = content.block("router");
        shardPlayer.selectedBlock = EPOCH.block("router").id();
        vanillaPlayer.selectedRotation = shardPlayer.selectedRotation = 3;
        vanillaPlayer.lastCommand = UnitCommand.rebuildCommand;
        shardPlayer.lastCommand = EPOCH.unitCommands().indexOf("rebuild");
        vanillaPlayer.team(Team.crux);
        shardPlayer.team(2);
        vanillaPlayer.set(80.5f, 90.25f);
        shardPlayer.x = 80.5f;
        shardPlayer.y = 90.25f;
        same("a player with fields set", vanillaPlayer, shardPlayer);

        // controlling a unit: the player refers to it, the unit to the player
        mindustry.gen.Unit vanillaUnit = content.unit("alpha").spawn(Team.crux, 40f, 40f);
        var shardUnit = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit("alpha"), 2);
        shardUnit.id(vanillaUnit.id);
        shardUnit.set(40f, 40f);
        shardUnit.add();
        vanillaPlayer.unit(vanillaUnit);
        shardPlayer.unit(shardUnit);
        vanillaPlayer.lastCommand = shardPlayer.lastCommand == -1 ? null : content.unitCommands().get(shardPlayer.lastCommand);
        same("a player controlling a unit", vanillaPlayer, shardPlayer);
        same("a player's unit", vanillaUnit, shardUnit);
    }

    @Test
    void savedUnitsReadBackAsVanilla(){
        int index = 0;
        for(UnitType type : content.units()){
            mindustry.gen.Unit vanilla = type.create(Team.sharded);
            var shard = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit(type.name), 1);
            place(vanilla, shard, 40f + index, 60f, index * 5f);
            load(vanilla, shard, index++);
            int classId = vanilla.classId() & 0xff;
            byte[] saved = Golden.bytes(vanilla::write);

            mindustry.gen.Unit back = (mindustry.gen.Unit)EntityMapping.map(classId).get();
            back.read(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(saved))));
            byte[] expected = Golden.bytes(back::write);

            var context = WorldWireContext.capture(world);
            var read = new com.xpdustry.shard.common.io.Reads(saved);
            var restored = UnitSink.readSave(world, read, classId, context);
            assertEquals(saved.length, read.position(), type.name + ": the whole body is read");
            var out = new com.xpdustry.shard.common.io.Writes();
            EntityClasses.writeSave(out, classId, PROJECTOR.unit(restored), context);
            byte[] actual = out.toByteArray();
            assertArrayEquals(expected, actual, () -> type.name + " read back\nvanilla " + Hex.of(expected) + "\nshard   " + Hex.of(actual));
        }
    }

    /**
     * A body whose ability count isn't its type's (a save from content with more or fewer abilities): vanilla's
     * placeholders keep their data where an index exists in both ({@code setType}), and the type's other abilities start
     * fresh. One fewer, one more and none, for types with and without abilities.
     */
    @Test
    void savedAbilityCountsThatDifferReadAsVanilla(){
        for(String name : List.of("quasar", "oct", "latum", "tecta", "dagger")){
            UnitType type = content.unit(name);
            for(int count : new int[]{type.abilities.size - 1, type.abilities.size + 1, 0}){
                if(count < 0 || count == type.abilities.size) continue;
                mindustry.gen.Unit vanilla = type.create(Team.sharded);
                vanilla.abilities = new mindustry.entities.abilities.Ability[count];
                for(int i = 0; i < count; i++){
                    vanilla.abilities[i] = new mindustry.entities.abilities.EmptyDataAbility();
                    vanilla.abilities[i].data = 7.25f * (i + 1);
                }
                int classId = vanilla.classId() & 0xff;
                byte[] saved = Golden.bytes(vanilla::write);
                mindustry.gen.Unit back = (mindustry.gen.Unit)EntityMapping.map(classId).get();
                back.read(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(saved))));
                byte[] expected = Golden.bytes(back::write);

                var context = WorldWireContext.capture(world);
                var restored = UnitSink.readSave(world, new com.xpdustry.shard.common.io.Reads(saved), classId, context);
                compare(name + " with " + count + " saved abilities", expected,
                    out -> EntityClasses.writeSave(out, classId, PROJECTOR.unit(restored), context));
            }
        }
    }

    @Test
    void contextAnswersAsVanilla(){
        // a few more synced entities, one of each kind of class
        for(String name : List.of("flare", "mace", "mega", "quell-missile", "manifold", "dummy")){
            mindustry.gen.Unit vanilla = content.unit(name).spawn(Team.sharded, 60f, 60f);
            var shard = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit(name), 1);
            shard.id(vanilla.id);
            shard.set(60f, 60f);
            shard.add();
        }
        Vanilla.runPosted();
        var context = WorldWireContext.capture(world);

        int maxId = 0;
        for(var entity : Groups.sync) maxId = Math.max(maxId, entity.id());
        assertTrue(maxId > 5, "synced entities: " + Groups.sync.size());
        for(int id = -3; id <= maxId + 3; id++){
            var entity = Groups.sync.getByID(id);
            int expected = entity == null ? -1 : entity.classId() & 0xff;
            assertEquals(expected, context.syncedClass(id), "syncedClass " + id);
        }
        for(int id = -2; id < content.statusEffects().size + 2; id++){
            boolean expected = content.getByID(ContentType.status, id) instanceof StatusEffect s && s.dynamic;
            assertEquals(expected, context.statusDynamic(id), "statusDynamic " + id);
        }
        for(int id = -2; id < content.blocks().size + 2; id++){
            var block = content.block(id);
            assertEquals(block != null, context.blockExists(id), "blockExists " + id);
            assertEquals(block != null && block.rotate, context.blockRotates(id), "blockRotates " + id);
        }
        //the types Shard's epoch models; for the others contentExists accepts every id, as WireContext's default does
        var modeled = java.util.Set.of("item", "block", "bullet", "liquid", "status", "unit", "weather", "sector", "planet");
        for(int type = 0; type < ContentType.all.length; type++){
            int count = content.getBy(ContentType.all[type]).size;
            for(int id = -2; id < count + 3; id++){
                var found = content.getByID(ContentType.all[type], id);
                assertEquals(found instanceof UnlockableContent, context.unlockable(type, id), "unlockable " + ContentType.all[type] + " " + id);
                if(modeled.contains(ContentType.all[type].name())){
                    assertEquals(found != null, context.contentExists(type, id), "contentExists " + ContentType.all[type] + " " + id);
                }
            }
        }
        int[] coordinates = {-32768, -2, -1, 0, 1, SIZE / 2, SIZE - 1, SIZE, SIZE + 1, 32767};
        for(int x : coordinates){
            for(int y : coordinates){
                int pos = Point2.pack(x, y);
                assertEquals(mindustry.Vars.world.tile(pos) != null, context.tileExists(pos), "tileExists " + x + "," + y);
            }
        }
        for(int pos : new int[]{-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x7fff_0000, 0x0000_7fff}){
            assertEquals(mindustry.Vars.world.tile(pos) != null, context.tileExists(pos), "tileExists " + pos);
        }
    }

    /** Positions both units and aligns the Shard unit's id to vanilla's. */
    static void place(mindustry.gen.Unit vanilla, com.xpdustry.shard.sim.unit.Unit shard, float x, float y, float rotation){
        shard.id(vanilla.id);
        vanilla.set(x, y);
        shard.x = x;
        shard.y = y;
        vanilla.rotation = rotation;
        shard.rotation = rotation;
    }

    /** The same busy state on both sides. */
    void load(mindustry.gen.Unit vanilla, com.xpdustry.shard.sim.unit.Unit shard, int seed){
        vanilla.vel.set(1.25f, -0.5f);
        shard.vel.set(1.25f, -0.5f);
        vanilla.elevation = shard.elevation = 0.5f;
        vanilla.health = shard.health = 17.75f + seed;
        vanilla.shield = shard.shield = 3.5f;
        vanilla.flag = shard.flag = 1234.5678 + seed;
        vanilla.aimX = shard.aimX = -12.5f;
        vanilla.aimY = shard.aimY = 99f;
        vanilla.isShooting = shard.isShooting = true;
        vanilla.spawnedByCore = shard.spawnedByCore = true;
        vanilla.updateBuilding = shard.updateBuilding = false;
        for(int i = 0; i < vanilla.mounts.length; i++){
            vanilla.mounts[i].aimX = shard.mounts[i].aimX = i * 2.5f;
            vanilla.mounts[i].aimY = shard.mounts[i].aimY = -i;
            vanilla.mounts[i].shoot = shard.mounts[i].shoot = i % 2 == 0;
            vanilla.mounts[i].rotate = shard.mounts[i].rotate = i % 3 == 0;
        }
        for(int i = 0; i < vanilla.abilities.length; i++) vanilla.abilities[i].data = shard.abilities[i].data = 2.5f * (i + 1);

        vanilla.apply(content.statusEffect("wet"), 120f);
        vanilla.apply(content.statusEffect("overclock"), 240f);
        var dynamic = vanilla.applyDynamicStatus();
        dynamic.time = 300f;
        dynamic.damageMultiplier = 1.5f;
        dynamic.speedMultiplier = 0.5f;
        dynamic.armorOverride = 4f;
        shard.statuses.clear();
        for(var entry : statuses(vanilla)){
            var copy = new com.xpdustry.shard.sim.unit.StatusEntry(entry.effect.id, entry.time);
            copy.damageMultiplier = entry.damageMultiplier;
            copy.healthMultiplier = entry.healthMultiplier;
            copy.speedMultiplier = entry.speedMultiplier;
            copy.reloadMultiplier = entry.reloadMultiplier;
            copy.buildSpeedMultiplier = entry.buildSpeedMultiplier;
            copy.dragMultiplier = entry.dragMultiplier;
            copy.armorOverride = entry.armorOverride;
            shard.statuses.add(copy);
        }

        vanilla.stack.set(content.item("titanium"), 7);
        shard.stackItem = EPOCH.item("titanium").id();
        shard.stackAmount = 7;
        vanilla.mineTile = mindustry.Vars.world.tile(5, 6);
        shard.mineTile = Point2.pack(5, 6);

        vanilla.plans.clear();
        shard.plans.clear();
        vanilla.plans.addLast(new mindustry.entities.units.BuildPlan(3, 4));
        shard.plans.addLast(com.xpdustry.shard.sim.unit.BuildPlan.breaking(3, 4));
        Object[][] configs = {
            {12, 12}, {"hello", "hello"}, {null, null}, {true, true},
            {new arc.math.geom.Point2(3, 4), new Point2(3, 4)},
            {new arc.math.geom.Vec2(1.5f, -2f), new Vec2(1.5f, -2f)},
            {arc.struct.IntSeq.with(1, 2, 3), new int[]{1, 2, 3}},
            // content, as a sorter or a liquid source takes it, and nested in an array (review 38)
            {content.item("copper"), ContentRef.item(EPOCH.item("copper").id())},
            {content.liquid("water"), ContentRef.liquid(EPOCH.liquid("water").id())},
            {new Object[]{content.item("lead"), content.liquid("oil"), 3}, new Object[]{ContentRef.item(EPOCH.item("lead").id()),
                ContentRef.liquid(EPOCH.liquid("oil").id()), 3}},
        };
        for(int i = 0; i < configs.length; i++){
            String block = i % 2 == 0 ? "router" : "conveyor";
            var plan = new mindustry.entities.units.BuildPlan(6 + i, 7, i % 4, content.block(block), configs[i][0]);
            vanilla.plans.addLast(plan);
            // planRotation() zeroes a non-rotating block's rotation: WP32's to port
            shard.plans.addLast(com.xpdustry.shard.sim.unit.BuildPlan.place(6 + i, 7, plan.rotation, EPOCH.block(block).id(), configs[i][1]));
        }

        if(vanilla instanceof Payloadc carrier){
            mindustry.gen.Unit carriedVanilla = content.unit("dagger").create(Team.sharded);
            var carriedShard = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit("dagger"), 1);
            place(carriedVanilla, carriedShard, 1f, 2f, 3f);
            carriedVanilla.health = carriedShard.health = 50f;
            carrier.addPayload(new UnitPayload(carriedVanilla));
            shard.payloads.add(new com.xpdustry.shard.sim.unit.Payload.UnitPayload(carriedShard));
        }
        if(vanilla instanceof BuildingTetherc tether){
            tether.building(vanillaWall);
            shard.tether = shardWall;
        }
        if(vanilla instanceof TimedKillc timed){
            timed.time(12.5f);
            timed.lifetime(300f);
            shard.time = 12.5f;
            shard.lifetime = 300f;
        }
        if(vanilla instanceof Mechc mech){
            mech.baseRotation(33f);
            ((MoveShape.Mech)shard.shape).baseRotation = 33f;
        }

        if(vanilla.type.playerControllable){
            var vanillaAi = new CommandAI();
            vanilla.controller(vanillaAi);
            vanillaAi.targetPos = new arc.math.geom.Vec2(10.5f, 20.25f);
            vanillaAi.attackTarget = seed % 2 == 0 ? vanillaWall : vanillaTarget;
            vanillaAi.command = content.unitCommands().get(seed % content.unitCommands().size);
            vanillaAi.commandQueue.clear();
            vanillaAi.commandQueue.add(new arc.math.geom.Vec2(1f, 2f));
            vanillaAi.commandQueue.add(vanillaWall);
            vanillaAi.commandQueue.add(vanillaTarget);
            vanillaAi.setStance(content.unitStances().get(1 + seed % (content.unitStances().size - 1)));
            vanillaAi.setStance(content.unitStances().get(1 + (seed * 7) % (content.unitStances().size - 1)));

            var shardAi = new CommandAi(world);
            shard.controller(shardAi);
            shardAi.targetPos = new Vec2(10.5f, 20.25f);
            shardAi.attackTarget = seed % 2 == 0 ? shardWall : shardTarget;
            shardAi.command = vanillaAi.command.id;
            shardAi.queue = new CommandAi.QueuedTarget[]{new CommandAi.QueuedTarget(1f, 2f, null),
                new CommandAi.QueuedTarget(0f, 0f, shardWall), new CommandAi.QueuedTarget(0f, 0f, shardTarget)};
            shardAi.stances.clear();
            for(var stance : content.unitStances()) if(vanillaAi.hasStance(stance)) shardAi.stances.set(stance.id);
        }else if(seed % 3 == 0){
            vanilla.controller(vanillaPlayer);
            shard.controller(new PlayerControl(shardPlayer));
        }
    }

    @SuppressWarnings("unchecked")
    static arc.struct.Seq<mindustry.entities.units.StatusEntry> statuses(mindustry.gen.Unit unit){
        try{
            for(Class<?> c = unit.getClass(); c != null; c = c.getSuperclass()){
                for(var field : c.getDeclaredFields()){
                    if(field.getName().equals("statuses")){
                        field.setAccessible(true);
                        return (arc.struct.Seq<mindustry.entities.units.StatusEntry>)field.get(unit);
                    }
                }
            }
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        throw new AssertionError(unit.getClass() + " has no statuses");
    }

    void same(String what, mindustry.gen.Unit vanilla, com.xpdustry.shard.sim.unit.Unit shard){
        int classId = vanilla.classId() & 0xff;
        var context = WorldWireContext.capture(world);
        UnitProjection projection = PROJECTOR.unit(shard);
        compare(what + " sync", Golden.bytes(vanilla::writeSync), out -> EntityClasses.writeSync(out, classId, projection, context));
        // the live view entity snapshots encode through, reused from unit to unit (W4.6)
        compare(what + " sync, live view", Golden.bytes(vanilla::writeSync),
            out -> EntityClasses.writeSync(out, classId, PROJECTOR.project(shard, view), context));
        compare(what + " save", Golden.bytes(vanilla::write), out -> EntityClasses.writeSave(out, classId, projection, context));
    }

    void same(String what, mindustry.gen.Player vanilla, com.xpdustry.shard.sim.unit.Player shard){
        var context = WorldWireContext.capture(world);
        PlayerProjection projection = PROJECTOR.player(shard);
        compare(what + " sync", Golden.bytes(vanilla::writeSync), out -> EntityClasses.writeSync(out, EntityClasses.PLAYER, projection, context));
        compare(what + " save", Golden.bytes(vanilla::write), out -> EntityClasses.writeSave(out, EntityClasses.PLAYER, projection, context));
    }

    static void compare(String what, byte[] expected, java.util.function.Consumer<com.xpdustry.shard.common.io.Writes> writer){
        var out = new com.xpdustry.shard.common.io.Writes();
        writer.accept(out);
        byte[] actual = out.toByteArray();
        assertArrayEquals(expected, actual, () -> what + "\nvanilla " + Hex.of(expected) + "\nshard   " + Hex.of(actual));
    }
}
