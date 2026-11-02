package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.storage.CoreEvent;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.world.Cause;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.sim.world.World;
import com.xpdustry.shard.sim.world.WorldConfig;
import com.xpdustry.shard.sim.world.WorldEvent;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP25 with WP23): cores on the wire. Two teams' cores with items, a player that spawns at its core, is killed
 * and spawns again, for {@value #FRAMES} frames: each frame, the {@code playerSpawn} Calls vanilla sent against what
 * {@link CoreCallMapping} makes of the {@link CoreEvent}s, byte for byte ({@link CallFrames}); every 12 frames, the
 * state snapshot's core data ({@code writeStateSnapshot}) against {@link GameState}'s, as the items change.
 */
class CoreSyncOracleTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final int SIZE = 48, FRAMES = 300;

    @Test
    void playerSpawnsAndCoreItemsAreVanillas(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.waves = false;
        var world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        world.part(WorldRules.class).rules.waves = false;

        place(world, "core-shard", 10, 10, Team.sharded);
        place(world, "core-foundation", 32, 30, Team.crux);
        give(world, 10, 10, "copper", 700);
        give(world, 10, 10, "silicon", 25);
        give(world, 32, 30, "lead", 90);

        Observer observer = world.observe(256, List.of(CoreEvent.class), null);
        var map = new MapState();
        int spawns = 0, states = 0;
        var vanilla = new mindustry.gen.Player[1];
        var shard = new com.xpdustry.shard.sim.unit.Player[1];
        Vanilla.net(Vanilla.NetMode.server, () -> {
            vanilla[0] = mindustry.gen.Player.create();
            vanilla[0].con = Vanilla.connection();
            vanilla[0].name = "oracle";
            vanilla[0].team(Team.sharded);
            vanilla[0].set(100f, 100f);
            vanilla[0].add();
        });
        shard[0] = com.xpdustry.shard.sim.unit.Player.create(world);
        shard[0].id(vanilla[0].id);
        shard[0].name = "oracle";
        shard[0].team(Team.sharded.id);
        shard[0].x = shard[0].y = 100f;
        shard[0].add();
        world.rand().setState(arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1);

        for(int frame = 0; frame < FRAMES; frame++){
            int f = frame;
            var sent = new ArrayList<Vanilla.Capture.Sent>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                if(f == 150){
                    if(vanilla[0].unit() != null) vanilla[0].unit().kill();
                    if(shard[0].unit() != null) shard[0].unit().kill();
                }
                if(f == 200){
                    // the cores' items change: the snapshots follow
                    give(world, 10, 10, "graphite", 40);
                    mindustry.Vars.world.build(32, 30).items.remove(content.item("lead"), 30);
                    world.buildings().at(32, 30).items.remove(EPOCH.item("lead").id(), 30);
                }
                Vanilla.frame();
                sent.addAll(Vanilla.captured());
            });
            world.tick();
            List<WorldEvent> events = observer.drain();
            List<String> expected = CallFrames.vanilla(sent, Set.of(Packets.PlayerSpawn.ID, Packets.SetTile.ID));
            CallFrames.same(expected, CallFrames.shard(world, events), "frame " + f);
            spawns += expected.size();

            if(f % 12 != 0) continue;
            StateSnapshotCallPacket want = SnapshotOracleTest.sent(StateSnapshotCallPacket.class, netServer::writeStateSnapshot).getFirst();
            Packets.StateSnapshot got = GameState.of(world, map, want.tps & 0xff, 0).packet();
            assertArrayEquals(want.coreData, got.coreData(), () -> "frame " + f + "\nvanilla " + Hex.of(want.coreData) + "\nshard   " + Hex.of(got.coreData()));
            assertEquals(2, want.coreData[0], "two teams with a core");
            states++;
        }
        assertEquals(2, spawns, "spawned, killed, spawned again");
        assertEquals(FRAMES / 12, states);
    }

    static void place(World world, String block, int x, int y, Team team){
        mindustry.Vars.world.tile(x, y).setBlock(content.block(block), team, 0);
        world.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
    }

    static void give(World world, int x, int y, String item, int amount){
        mindustry.Vars.world.build(x, y).items.add(content.item(item), amount);
        Building core = world.buildings().at(x, y);
        core.items.add(EPOCH.item(item).id(), amount);
    }
}
