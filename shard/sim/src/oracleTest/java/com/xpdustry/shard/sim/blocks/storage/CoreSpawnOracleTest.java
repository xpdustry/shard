package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.sim.unit.Player;
import com.xpdustry.shard.sim.unit.Unit;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: a dead player respawns as vanilla's does ({@code PlayerComp.update}, {@code bestCore},
 * {@code CoreBuild.requestSpawn}, {@code Call.playerSpawn}). A player joins a world with cores in both vanilla (its net in
 * server mode, the player on the fake connection) and Shard; every frame for {@value #FRAMES} frames both are compared:
 * the player's position and death timer, its unit (entity id, type, position, velocity, rotation, controller), the
 * next entity id, the generator's state, and the {@code playerSpawn} Calls sent against the
 * {@link CoreEvent.PlayerSpawn}s published, in order. The unit is killed twice to respawn it. One run per core block
 * shows its unit type; another, with three cores, shows the choice of core.
 *
 * <p>The unit stays at the core's centre in both, despite its {@code impulse(0, 3)}: the player snaps a remote unit's
 * interpolation when it takes it over ({@code PlayerComp.java:308}), and the server then holds it at its last client
 * snapshot every frame ({@code SyncComp.update}).
 */
class CoreSpawnOracleTest{
    static final int SIZE = 48, FRAMES = 400;

    World world;
    Observer events;
    int calls;

    static List<String> cores(){
        return List.of("core-shard", "core-foundation", "core-nucleus", "core-bastion", "core-citadel", "core-acropolis");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cores")
    void eachCoreSpawnsItsUnitType(String block){
        boolean erekir = block.equals("core-bastion") || block.equals("core-citadel") || block.equals("core-acropolis");
        setUp(erekir);
        place(block, 20, 20, Team.sharded);
        String type = run();
        assertEquals(((mindustry.world.blocks.storage.CoreBlock)content.block(block)).unitType.name, type);
    }

    /** The largest core wins, then the nearest; a core of another team or one that can't spawn here never does. */
    @Test
    void theBestCoreSpawns(){
        setUp(false);
        place("core-shard", 8, 8, Team.sharded);
        place("core-foundation", 30, 10, Team.sharded);
        place("core-foundation", 12, 36, Team.sharded);
        place("core-nucleus", 36, 36, Team.crux);
        assertEquals("beta", run());
    }

    void setUp(boolean erekir){
        Vanilla.emptyWorld(SIZE, SIZE);
        world = VanillaWorlds.world(SIZE, SIZE, 1);
        var rules = world.part(WorldRules.class).rules;
        state.rules.waves = rules.waves = false;
        if(erekir){
            state.rules.env = Planets.erekir.defaultEnv;
            rules.env = Planets.erekir.defaultEnv;
        }
        assertEquals(state.rules.env, rules.env);
        events = world.observe(256, List.of(CoreEvent.class), null);
        calls = 0;
    }

    void place(String block, int x, int y, Team team){
        mindustry.Vars.world.tile(x, y).setBlock(content.block(block), team, 0);
        world.buildings().setBlock(x, y, VanillaWorlds.VANILLA.block(block), team.id, 0, Cause.SERVER);
    }

    /** Runs the script; returns the type of the first unit spawned. */
    String run(){
        String[] first = {null};
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            mindustry.gen.Player vanilla = mindustry.gen.Player.create();
            vanilla.con = Vanilla.connection();
            vanilla.name = "oracle";
            vanilla.team(Team.sharded);
            vanilla.set(100f, 100f);
            vanilla.add();
            Player shard = Player.create(world);
            shard.name = "oracle";
            shard.team(Team.sharded.id);
            shard.x = shard.y = 100f;
            shard.add();
            assertEquals(vanilla.id, shard.id());
            // the same generator from here on
            world.rand().setState(arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1);
            for(int frame = 0; frame < FRAMES; frame++){
                if(frame == 150 || frame == 300){
                    if(vanilla.unit() != null) vanilla.unit().kill();
                    if(shard.unit() != null) shard.unit().kill();
                }
                boolean dead = vanilla.dead();
                Vanilla.frame();
                world.tick();
                if(first[0] == null && vanilla.unit() != null && !vanilla.dead()) first[0] = vanilla.unit().type.name;
                compare(vanilla, shard, frame);
                if(dead && !vanilla.dead()){
                    // the spawn frame: the core's centre, the impulse in the velocity only
                    var core = vanilla.bestCore();
                    assertEquals(core.y, vanilla.unit().y, "vanilla's unit at the core, frame " + frame);
                    assertEquals(core.y, shard.unit().y, "Shard's unit at the core, frame " + frame);
                }
            }
        });
        assertNotNull(first[0], "a unit spawned");
        return first[0];
    }

    void compare(mindustry.gen.Player vanilla, Player shard, int frame){
        assertEquals(state(vanilla), state(shard), "frame " + frame);
        assertEquals(lastId(), world.ids().peek(), "next entity id, frame " + frame);
        assertEquals(List.of(arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1), List.of(world.rand().getState(0), world.rand().getState(1)),
            "generator, frame " + frame);
        var expected = new ArrayList<String>();
        List<Vanilla.Capture.Sent> sent = Vanilla.captured();
        for(; calls < sent.size(); calls++){
            if(sent.get(calls).packet() instanceof PlayerSpawnCallPacket p) expected.add("playerSpawn " + p.tile.pos() + " " + p.player.id);
        }
        var actual = new ArrayList<String>();
        var tiles = world.tiles();
        for(WorldEvent event : events.drain()){
            if(event instanceof CoreEvent.PlayerSpawn spawn){
                actual.add("playerSpawn " + com.xpdustry.shard.common.geom.Point2.pack(tiles.x(spawn.pos()), tiles.y(spawn.pos())) + " " + spawn.player());
            }
        }
        assertEquals(expected, actual, "calls, frame " + frame);
    }

    static String state(mindustry.gen.Player player){
        var out = new StringBuilder("player " + player.id + " at " + player.x + "," + player.y + " timer " + player.deathTimer);
        mindustry.gen.Unit unit = player.unit();
        if(unit == null || !unit.isValid()) return out.append(" dead").toString();
        out.append(" unit ").append(unit.id).append(' ').append(unit.type.name).append(" at ").append(unit.x).append(',').append(unit.y)
            .append(" vel ").append(unit.vel.x).append(',').append(unit.vel.y).append(" rotation ").append(unit.rotation)
            .append(" core ").append(unit.spawnedByCore).append(" team ").append(unit.team.id).append(" player ").append(unit.controller() == player);
        return out.toString();
    }

    static String state(Player player){
        var out = new StringBuilder("player " + player.id() + " at " + player.x + "," + player.y + " timer " + player.deathTimer);
        Unit unit = player.unit();
        if(unit == null || !unit.isValid()) return out.append(" dead").toString();
        out.append(" unit ").append(unit.id()).append(' ').append(unit.type().name()).append(" at ").append(unit.x).append(',').append(unit.y)
            .append(" vel ").append(unit.vel.x).append(',').append(unit.vel.y).append(" rotation ").append(unit.rotation)
            .append(" core ").append(unit.spawnedByCore).append(" team ").append(unit.team).append(" player ").append(unit.player() == player);
        return out.toString();
    }

    /** {@code EntityGroup.lastId}: the id the next entity takes. */
    static int lastId(){
        try{
            var field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
