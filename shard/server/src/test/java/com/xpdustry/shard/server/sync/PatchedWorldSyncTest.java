package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W6.2 ({@code PatchedWorldProjectionTest} for the stream): a world of a map's patched content syncs with its own content, not the host's epoch. A mega there
 * carries a passthrough building whose save bytes carry the patched hash ({@code PassthroughBuilding.body} gives them
 * back only for that hash): the host's epoch can't project it; the world's sync and stream do, bytes and all.
 */
class PatchedWorldSyncTest{
    static final Epoch VANILLA = Epoch.vanilla();
    static final Epoch PATCHED = DataPatcher.apply(VANILLA, List.of(new DataAsset.PatchAsset("patch.json", "{\"block.conveyor.speed\": 0.08}")), "serpulo").epoch();
    /** The carried building's save bytes, as a file of the patched content left them. */
    static final byte[] BODY = {0x5a, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x01};
    static final int SIZE = 32;

    Loopback loopback;
    World world;
    PassthroughBuilding carried;
    Unit mega;

    @BeforeEach
    void open() throws Exception{
        loopback = new Loopback();
        // the container as an unported kind: its bytes pass through, as an unported block's in a loaded map
        KindRegistry kinds = KindRegistry.builtIn().withPassthrough().withKind(VANILLA.block("container").kind(), PassthroughBuilding.class, PassthroughBuilding::new);
        world = new World(WorldConfig.of(PATCHED, SIZE, SIZE, 1).withKinds(kinds));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, PATCHED.block("stone").id());
        world.buildings().endLoad(false);
        world.part(WorldRules.class).rules.disableUnitCap = true;
        carried = (PassthroughBuilding)world.buildings().setBlock(5, 5, PATCHED.block("container"), 1, 0, Cause.SERVER);
        carried.load(BODY.clone(), 0, PATCHED.hash());
        mega = Unit.spawn(world, PATCHED.unit("mega"), 1, 5 * 8 + 8, 5 * 8 + 8, 0f);
        mega.controller(new NoAi());
        Payloads.pickedBuildPayload(mega, carried, true, Cause.SERVER);
        assertEquals(1, mega.payloads.size(), "the mega carries the container");
    }

    @AfterEach
    void close() throws Exception{
        loopback.close();
    }

    @Test
    void theSyncProjectsWithTheWorldsOwnContent() throws Exception{
        var outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> fail("overflow"), loopback);
        // HostedWorld makes its sync with the host's epoch before the world exists; the world it attaches decides
        var sync = new WorldSync(0x5eed, SyncSettings.DEFAULT, outbox, VANILLA, BuildingCodecs.builtIn());
        sync.attached(world);
        assertSame(world.content(), sync.content(), "bound to the attached world's content");
        Session alpha = loopback.session("alpha");
        Player player = Player.create(world);
        player.name = "alpha";
        player.team(1);
        player.add();
        var roster = new TreeMap<Long, Player>(Map.of(alpha.id(), player));
        outbox.member(alpha);
        outbox.capture(alpha, 1);
        outbox.confirmed(alpha.id(), 1);
        for(int i = 0; i < 24; i++){
            world.tick();
            sync.beginTick(world, roster);
            assertFalse(sync.drain());
            sync.endTick(world, new MapState());
        }
        List<Loopback.Sent> snapshots = loopback.to(alpha).stream().filter(s -> s.id() == Packets.ENTITY_SNAPSHOT).toList();
        assertFalse(snapshots.isEmpty(), "entity snapshots went out");
        boolean carriedBytes = snapshots.stream().anyMatch(s -> indexOf(s.frame().toByteArray(), BODY) >= 0);
        assertTrue(carriedBytes, "the mega's snapshot carries the building's own bytes, in " + snapshots.size() + " snapshots");
    }

    static int indexOf(byte[] haystack, byte[] needle){
        outer:
        for(int i = 0; i + needle.length <= haystack.length; i++){
            for(int j = 0; j < needle.length; j++) if(haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
