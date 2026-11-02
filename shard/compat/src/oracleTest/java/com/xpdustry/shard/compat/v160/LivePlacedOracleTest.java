package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.MapState;
import com.xpdustry.shard.compat.v160.world.PlayerState;
import com.xpdustry.shard.compat.v160.world.WorldStreamWriter;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: blocks Shard doesn't port, placed live, have no save bytes, yet the world still streams and saves; and blocks it
 * ports, finished by construction, stream and save as vanilla's. They are
 * placed by a world processor's {@code setblock} ({@code tile.setNet} in vanilla), or finished by construction
 * ({@code ConstructBlock.constructed} on both sides). Vanilla reads back Shard's world stream and Shard's save: the
 * same tiles as its own stream and save give, and the same live buildings (block, team, rotation, health, version,
 * fields).
 */
class LivePlacedOracleTest{
    static final int SIZE = 24;
    /**
     * Unported blocks: an assembler module, a tank assembler and a ship assembler, each 5x5 (bodies with payloads, ints
     * and floats). Every vanilla block is ported since W5.1, so these run on {@link #PROBE}, the built-in kinds without
     * the assembler's two: Shard keeps them as passthroughs and writes vanilla's fresh bodies.
     */
    static final List<String> BLOCKS = List.of("basic-assembler-module", "tank-assembler", "ship-assembler");
    static final KindRegistry PROBE = without(com.xpdustry.shard.content.KindKey.of("unit-assembler"), com.xpdustry.shard.content.KindKey.of("unit-assembler-module"));
    /** The unit assemblers and the module (WP81), each with a payload block's body and its own, built by construction. */
    static final List<String> ASSEMBLERS = List.of("tank-assembler", "ship-assembler", "mech-assembler", "basic-assembler-module");
    static final int[][] ASSEMBLERS_AT = {{3, 3}, {9, 3}, {15, 3}, {3, 10}};
    static final int[][] AT = {{5, 5}, {10, 11}, {17, 5}};
    /**
     * Ported kinds with a body or none, finished by construction (WP39, WP40, WP66, WP70): walls, doors, a shield wall, a
     * mine, a launch pad, a unit factory and a reconstructor, the cargo unload point and loader. A landing pad draws its
     * priority and a target dummy makes its unit, which this gate doesn't align.
     */
    static final List<String> PORTED = List.of("copper-wall", "door-large", "blast-door", "shielded-wall", "shock-mine", "thruster",
        "launch-pad", "ground-factory", "additive-reconstructor", "unit-cargo-unload-point", "unit-cargo-loader");
    static final int[][] PORTED_AT = {{2, 2}, {4, 2}, {7, 2}, {10, 2}, {12, 2}, {16, 3}, {9, 8}, {20, 19}, {20, 14}, {19, 10}, {15, 15}};
    /** The payload blocks of WP80, each with a payload block's body: none held, as construction leaves them. */
    static final List<String> PAYLOAD = List.of("constructor", "payload-mass-driver", "payload-loader", "payload-unloader",
        "small-deconstructor", "payload-void", "payload-source");
    static final int[][] PAYLOAD_AT = {{2, 2}, {6, 2}, {10, 2}, {14, 2}, {18, 2}, {4, 8}, {10, 8}};

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void liveUnportedBlocksStreamAndSaveAsVanillas() throws IOException{
        // Shard: a world processor places them
        var shard = emptyWorld(PROBE);
        LogicPart.require(shard).logic().rand(new Rand(0));
        // a world processor is configured only where the rules allow editing them
        shard.buildings().rules().allowEditWorldProcessors = true;
        var code = new StringBuilder();
        for(int i = 0; i < BLOCKS.size(); i++) code.append("setblock block @").append(BLOCKS.get(i)).append(' ').append(AT[i][0]).append(' ').append(AT[i][1]).append(" @sharded 0\n");
        code.append("stop\n");
        shard.buildings().setBlock(1, 1, Worlds.EPOCH.block("world-processor"), Team.sharded.id, 0, Cause.SERVER);
        shard.buildings().at(1, 1).configureAny(LogicConfig.compress(code.toString(), List.of()));
        for(int i = 0; i < 10; i++) shard.tick();
        for(int i = 0; i < BLOCKS.size(); i++){
            var placed = shard.buildings().at(AT[i][0], AT[i][1]);
            assertTrue(placed instanceof PassthroughBuilding passthrough && !passthrough.hasBody() && placed.block().name().equals(BLOCKS.get(i)),
                BLOCKS.get(i) + " placed live, unported: " + placed);
        }
        compare(shard, BLOCKS, () -> {
            // vanilla: the same tiles, the blocks placed as setblock does
            world.tile(1, 1).setBlock(Blocks.worldProcessor, Team.sharded, 0);
            for(int i = 0; i < BLOCKS.size(); i++) world.tile(AT[i][0], AT[i][1]).setNet(content.block(BLOCKS.get(i)), Team.sharded, 0);
        });
    }

    @Test
    void unportedBlocksBuiltByConstructionStreamAndSaveAsVanillas() throws IOException{
        // Shard: construction finishes them (a player's plan or a rebuild)
        var shard = emptyWorld(PROBE);
        for(int i = 0; i < BLOCKS.size(); i++){
            Build.constructed(shard, AT[i][1] * SIZE + AT[i][0], Worlds.EPOCH.block(BLOCKS.get(i)), null, 0, Team.sharded.id, null);
        }
        shard.tick();
        for(int i = 0; i < BLOCKS.size(); i++){
            var placed = shard.buildings().at(AT[i][0], AT[i][1]);
            assertTrue(placed instanceof PassthroughBuilding passthrough && !passthrough.hasBody() && placed.block().name().equals(BLOCKS.get(i)),
                BLOCKS.get(i) + " built live, unported: " + placed);
        }
        compare(shard, BLOCKS, () -> {
            // vanilla: the same blocks, finished as construction finishes them
            for(int i = 0; i < BLOCKS.size(); i++){
                ConstructBlock.constructed(world.tile(AT[i][0], AT[i][1]), content.block(BLOCKS.get(i)), null, (byte)0, Team.sharded, null);
            }
        });
    }

    @Test
    void portedDefenseAndSandboxBlocksBuiltByConstructionStreamAndSaveAsVanillas() throws IOException{
        var shard = emptyWorld();
        for(int i = 0; i < PORTED.size(); i++){
            Build.constructed(shard, PORTED_AT[i][1] * SIZE + PORTED_AT[i][0], Worlds.EPOCH.block(PORTED.get(i)), null, 0, Team.sharded.id, null);
        }
        shard.tick();
        for(int i = 0; i < PORTED.size(); i++){
            var placed = shard.buildings().at(PORTED_AT[i][0], PORTED_AT[i][1]);
            assertFalse(placed instanceof PassthroughBuilding, PORTED.get(i) + " is ported: " + placed);
        }
        compare(shard, PORTED, () -> {
            for(int i = 0; i < PORTED.size(); i++){
                ConstructBlock.constructed(world.tile(PORTED_AT[i][0], PORTED_AT[i][1]), content.block(PORTED.get(i)), null, (byte)0, Team.sharded, null);
            }
        });
    }

    @Test
    void portedPayloadBlocksBuiltByConstructionStreamAndSaveAsVanillas() throws IOException{
        var shard = emptyWorld();
        for(int i = 0; i < PAYLOAD.size(); i++){
            Build.constructed(shard, PAYLOAD_AT[i][1] * SIZE + PAYLOAD_AT[i][0], Worlds.EPOCH.block(PAYLOAD.get(i)), null, 0, Team.sharded.id, null);
        }
        shard.tick();
        for(int i = 0; i < PAYLOAD.size(); i++){
            var placed = shard.buildings().at(PAYLOAD_AT[i][0], PAYLOAD_AT[i][1]);
            assertFalse(placed instanceof PassthroughBuilding, PAYLOAD.get(i) + " is ported: " + placed);
        }
        compare(shard, PAYLOAD, () -> {
            for(int i = 0; i < PAYLOAD.size(); i++){
                ConstructBlock.constructed(world.tile(PAYLOAD_AT[i][0], PAYLOAD_AT[i][1]), content.block(PAYLOAD.get(i)), null, (byte)0, Team.sharded, null);
            }
        });
    }

    @Test
    void portedAssemblersBuiltByConstructionStreamAndSaveAsVanillas() throws IOException{
        var shard = emptyWorld();
        for(int i = 0; i < ASSEMBLERS.size(); i++){
            Build.constructed(shard, ASSEMBLERS_AT[i][1] * SIZE + ASSEMBLERS_AT[i][0], Worlds.EPOCH.block(ASSEMBLERS.get(i)), null, 0, Team.sharded.id, null);
        }
        shard.tick();
        for(int i = 0; i < ASSEMBLERS.size(); i++){
            var placed = shard.buildings().at(ASSEMBLERS_AT[i][0], ASSEMBLERS_AT[i][1]);
            assertFalse(placed instanceof PassthroughBuilding, ASSEMBLERS.get(i) + " is ported: " + placed);
        }
        compare(shard, ASSEMBLERS, () -> {
            for(int i = 0; i < ASSEMBLERS.size(); i++){
                ConstructBlock.constructed(world.tile(ASSEMBLERS_AT[i][0], ASSEMBLERS_AT[i][1]), content.block(ASSEMBLERS.get(i)), null, (byte)0, Team.sharded, null);
            }
        });
    }

    /** The built-in kinds without {@code removed}, with passthrough: their blocks stay unported. */
    static KindRegistry without(com.xpdustry.shard.content.KindKey... removed){
        KindRegistry all = KindRegistry.builtIn();
        var classes = new ArrayList<Class<? extends com.xpdustry.shard.sim.building.Building>>();
        var tiles = new ArrayList<com.xpdustry.shard.content.KindKey>();
        for(com.xpdustry.shard.content.KindKey key : all.keys()){
            if(List.of(removed).contains(key)) continue;
            if(all.tileKind(key)) tiles.add(key);
            else classes.add(all.type(key));
        }
        return KindRegistry.of(classes, tiles).withPassthrough();
    }

    static World emptyWorld(){
        return emptyWorld(KindRegistry.builtIn().withPassthrough());
    }

    static World emptyWorld(KindRegistry kinds){
        var shard = new World(WorldConfig.of(Worlds.EPOCH, SIZE, SIZE, 1).withKinds(kinds));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, Worlds.EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        return shard;
    }

    /** Shard's stream and save of {@code shard} read back in vanilla as vanilla's own, after {@code placeVanilla}. */
    static void compare(World shard, List<String> blocks, Runnable placeVanilla) throws IOException{
        var source = PROJECTION.capture(shard, new MapState(), PlayerState.fresh(shard.ids().next(), "oracle", Team.sharded.id));
        byte[] stream = WorldStreamWriter.write(source);
        byte[] save = MsavWriter.write(MsavWriter.meta(source, new LinkedHashMap<>(), Team.sharded.id, true, 0L), source);

        Vanilla.emptyWorld(SIZE, SIZE);
        placeVanilla.run();
        Vanilla.runPosted();
        Player player = Player.create();
        player.name = "oracle";
        player.team(Team.sharded);
        byte[] vanillaStream = Golden.worldStream(player).raw();
        var vanillaSave = new ByteArrayOutputStream();
        SaveIO.write(vanillaSave, new SaveOptions());

        var differences = new ArrayList<String>();
        Decoded expected = decode(vanillaStream);
        List<Oracle.BuildingView> expectedBuildings = placed(blocks);
        Decoded actual = decode(stream);
        assertEquals(List.of(), actual.view().warnings(), "stream warnings");
        for(String line : tileDifferences(expected.tiles(), actual.tiles(), i -> true)) differences.add("stream: " + line);
        for(String line : listDifferences("building", expectedBuildings, placed(blocks))) differences.add("stream: " + line);

        Vanilla.reset();
        SaveIO.load(new ByteArrayInputStream(vanillaSave.toByteArray()), world.context);
        Vanilla.runPosted();
        Tiles expectedTiles = Tiles.current();
        expectedBuildings = placed(blocks);
        Vanilla.reset();
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(save)), world.context);
        Vanilla.runPosted();
        for(String line : tileDifferences(expectedTiles, Tiles.current(), i -> true)) differences.add("save: " + line);
        for(String line : listDifferences("building", expectedBuildings, placed(blocks))) differences.add("save: " + line);
        assertEquals(blocks.size(), expectedBuildings.size(), "vanilla has the placed buildings");
        assertEquals(List.of(), differences);
    }

    /** The live-placed buildings as vanilla loaded them. */
    static List<Oracle.BuildingView> placed(List<String> blocks){
        return Oracle.buildings().stream().filter(view -> blocks.contains(view.block())).toList();
    }
}
