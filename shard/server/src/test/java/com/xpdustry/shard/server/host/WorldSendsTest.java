package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The stream's player section, built from W2.2's {@link PlayerProjection}, is the player's save bytes. */
class WorldSendsTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final EntityProjector PROJECTOR = new EntityProjector(EPOCH, BuildingCodecs.builtIn());

    static World world(){
        WorldConfig template = HostedSystems.template(EPOCH, 1, 256);
        return new World(new WorldConfig(template.content(), 16, 16, 1, template.systems(), template.kinds(), template.inboxCapacity(), template.journal()));
    }

    static void same(Player player){
        PlayerProjection projection = PROJECTOR.player(player);
        var stream = new Writes();
        PlayerState.of(projection).write(stream);
        var save = new Writes();
        save.i(player.id());
        EntityClasses.writeSave(save, EntityClasses.PLAYER, projection, null);
        assertArrayEquals(save.toByteArray(), stream.toByteArray());
    }

    @Test
    void aFreshPlayerIsItsSave(){
        World world = world();
        Player player = Player.create(world);
        same(player);
        assertArrayEquals(bytes(PlayerState.fresh(player.id(), player.name, player.team)), bytes(PlayerState.of(PROJECTOR.player(player))));
    }

    @Test
    void everyFieldIsItsSave(){
        World world = world();
        Player player = Player.create(world);
        player.name = "[accent]shard";
        player.color = 0x12345678;
        player.admin = player.typing = player.shooting = player.boosting = true;
        player.mouseX = 123.25f;
        player.mouseY = -7.5f;
        player.selectedBlock = EPOCH.block("router").id();
        player.selectedRotation = 3;
        player.lastCommand = EPOCH.unitCommands().indexOf("rebuild");
        player.team(2);
        player.x = 80.5f;
        player.y = 90.25f;
        same(player);

        Unit unit = Unit.create(world, EPOCH.unit("alpha"), 2);
        unit.set(40f, 40f);
        unit.add();
        player.unit(unit);
        same(player);
    }

    @Test
    void joinersInOneTickShareTheMap(){
        World world = world();
        var players = List.of(Player.create(world), Player.create(world));
        var sends = new WorldSends(EPOCH, BuildingCodecs.builtIn());
        MapState state = new MapState();
        List<WorldStreamSource> sources = sends.capture(world, state, players);
        assertEquals(2, sources.size());
        assertSame(sources.get(0).map(), sources.get(1).map());
        assertEquals(players.get(1).id(), sources.get(1).player().id());
        assertNotEquals(sources.get(0).player().id(), sources.get(1).player().id());
    }

    @Test
    void warmingLeavesTheWorldAlone(){
        World world = world();
        var before = world.hash();
        assertTrue(new WorldSends(EPOCH, BuildingCodecs.builtIn()).warm(world, new MapState()).deflated() > 0);
        assertEquals(before, world.hash());
        assertEquals(Player.create(world).id(), Player.create(world()).id(), "no entity id taken");
    }

    static byte[] bytes(PlayerState state){
        var out = new Writes();
        state.write(out);
        return out.toByteArray();
    }
}
