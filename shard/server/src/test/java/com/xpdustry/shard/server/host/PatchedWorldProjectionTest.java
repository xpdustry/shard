package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W6.2: a world of a map's patched content streams and syncs with its own content, not the host's epoch. A mega there
 * carries a passthrough building whose save bytes carry the patched hash ({@code PassthroughBuilding.body} gives them
 * back only for that hash): the host's epoch can't project it; the world's sync and stream do, bytes and all.
 */
class PatchedWorldProjectionTest{
    static final Epoch VANILLA = Epoch.vanilla();
    static final Epoch PATCHED = DataPatcher.apply(VANILLA, List.of(new DataAsset.PatchAsset("patch.json", "{\"block.conveyor.speed\": 0.08}")), "serpulo").epoch();
    /** The carried building's save bytes, as a file of the patched content left them. */
    static final byte[] BODY = {0x5a, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x01};
    static final int SIZE = 32;

    World world;
    PassthroughBuilding carried;
    Unit mega;

    @BeforeEach
    void open(){
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

    @Test
    void theHostsEpochCantProjectTheCarriedBuilding(){
        // what WorldSync and WorldSends projected with before W6.2
        var host = new EntityProjector(VANILLA, BuildingCodecs.builtIn());
        var e = assertThrows(IllegalStateException.class, () -> host.payload(mega.payloads.get(0)));
        assertTrue(e.getMessage().contains(PATCHED.hash()), e.getMessage());
        var own = new EntityProjector(PATCHED, BuildingCodecs.builtIn()).payload(mega.payloads.get(0));
        assertNotNull(own);
    }

    @Test
    void theStreamProjectsWithTheWorldsOwnContent(){
        var sends = new WorldSends(VANILLA, BuildingCodecs.builtIn());
        Player player = Player.create(world);
        player.name = "alpha";
        player.team(1);
        player.add();
        List<WorldStreamSource> sources = sends.capture(world, new MapState(), List.of(player));
        boolean carriedBytes = sources.getFirst().entities().stream().anyMatch(chunk -> indexOf(chunk.body(), BODY) >= 0);
        assertTrue(carriedBytes, "the stream's mega carries the building's own bytes");
        byte[] raw = WorldStreamWriter.write(sources.getFirst());
        assertTrue(indexOf(raw, BODY) >= 0);
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
