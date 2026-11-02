package com.xpdustry.shard.compat.v160.blocks.production;

import arc.struct.*;
import com.xpdustry.shard.compat.v160.fixtures.Worlds;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.DataAsset.ExternalAsset;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.net.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (W5.4, W6.2): worlds with data patches play exactly as vanilla's, frame by frame, from the file each side loads
 * ({@link ProductionRun#loadFile}): the random, the entity ids, the clock and every building with its items, drills and
 * conveyors after every frame for {@value #FRAMES} frames, then the save chunks.
 * <ul>
 *   <li>the bundled maps with patches, hidden/242 and littoralShipyard, pinned as {@code ProductionMapOracleTest} pins
 *   Salt Flats: units, waves and the unported buildings that update removed on both sides;</li>
 *   <li>a fixture vanilla builds and saves with a data patch (the mechanical drill's {@code drillTime} and the conveyor's
 *   {@code speed}) and an embedded sprite: the patch is in each side's content, it speeds the drills and belts (more
 *   copper reaches the containers than in the same world unpatched), the world stream and the asset streams carry
 *   what vanilla's do byte for byte, and a vanilla client reading Shard's stream gets the patched content; then vanilla's
 *   fresh save of the played fixture, with its assets embedded, loads on both sides and plays on exact
 *   ({@link #freshSave}).</li>
 * </ul>
 */
class PatchedMapsOracleTest{
    static final int FRAMES = 600;
    static final String PATCH = "{\"name\": \"faster\", \"block.mechanical-drill.drillTime\": 200, \"block\": {\"conveyor\": {\"speed\": 0.08}}}";
    /** A 1x1 PNG. */
    static final byte[] SPRITE = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==");

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    static List<String> maps(){
        return List.of("serpulo/hidden/242", "serpulo/littoralShipyard");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void theBundledMapsWithPatchesPlayAsVanillas(String map){
        var run = ProductionRun.load(map);
        assertFalse(state.data.getPatches().isEmpty(), "vanilla loaded the map's patches");
        run.compare("loaded, as saved");
        pin(run, map);
        run.frames(FRAMES);
        System.out.println(map + ": " + FRAMES + " frames exact, " + run.buildings().size() + " buildings");
        assertTrue(run.buildings().size() > 20, "the map's buildings stand");
    }

    @Test
    void aFixtureWithAPatchAndASpritePlaysAsVanillas() throws IOException{
        byte[][] files = fixture();
        byte[] control = files[0], patched = files[1];

        // Shard's view of the file: its assets and content
        SaveFile save = ProductionRun.READER.read(patched);
        MapLoader.Loaded loaded = ProductionRun.LOADER.load(save, Worlds.config(0), ProductionRun.LOADER.saveRules(save));
        var patchedContent = (Epoch)loaded.world().content();
        assertTrue(patchedContent.isPatched(), "the world plays its own patched content");
        assertEquals(200f, patchedContent.block("mechanical-drill").fields().f("drillTime"));
        assertEquals(0.08f, patchedContent.block("conveyor").fields().f("speed"));
        assertEquals(600f, Epoch.vanilla().block("mechanical-drill").fields().f("drillTime"), "other worlds keep vanilla's");
        assertEquals(1, loaded.state().assets.patches().size());
        ExternalAsset sprite = loaded.state().assets.externals().getFirst();
        assertArrayEquals(SPRITE, sprite.data(), "the server holds the sprite's bytes");

        var run = ProductionRun.loadFile(patched);
        assertEquals(200f, ((Drill)content.block("mechanical-drill")).drillTime, "vanilla's content is patched by the load");
        assertTrue(((Epoch)run.shard.content()).isPatched());
        // the section vanilla holds once loaded, as its stream and its saves write it
        var vanillaSection = new ByteArrayOutputStream();
        SaveIO.getSaveWriter().writeDataPatches(new DataOutputStream(vanillaSection), false);
        assertEquals(hex(vanillaSection.toByteArray()), hex(loaded.state().patches), "the data patch section");
        assetStreamsAreVanillas(loaded.state().assets);

        run.frames(FRAMES);
        int patchedCopper = copper(run.buildings());
        System.out.println("fixture: " + FRAMES + " frames exact, " + patchedCopper + " copper in the containers");
        freshSave(run, sprite);

        // the same world without the patch, in Shard alone
        SaveFile plain = ProductionRun.READER.read(control);
        World unpatched = ProductionRun.LOADER.load(plain, Worlds.config(0), ProductionRun.LOADER.saveRules(plain)).world();
        assertSame(Epoch.vanilla(), unpatched.content());
        for(int i = 0; i < FRAMES; i++) unpatched.tick();
        int plainCopper = 0;
        for(int pos = 0; pos < unpatched.tiles().size(); pos++){
            var b = unpatched.buildings().at(pos);
            if(b != null && b.tile() == pos && b.block().name().equals("container")) plainCopper += b.items.get(Epoch.vanilla().item("copper").id());
        }
        System.out.println("fixture unpatched: " + plainCopper + " copper in the containers");
        assertTrue(patchedCopper > plainCopper + 4 && plainCopper > 0, "the patch speeds the drills and belts: " + patchedCopper + " vs " + plainCopper);

        // the world stream: the section vanilla's has, and a vanilla client reading Shard's gets the patched content
        Player player = Player.create();
        player.name = "oracle";
        player.team(Team.sharded);
        byte[] vanillaStream = Golden.worldStream(player).raw();
        WorldStreamSource source = new WorldProjection(ProductionRun.EPOCH, ProductionRun.CODECS)
            .capture(loaded.world(), loaded.state(), PlayerState.fresh(loaded.world().ids().next(), "oracle", Team.sharded.id));
        byte[] stream = WorldStreamWriter.write(source);
        assertEquals(hex(Arrays.copyOf(vanillaStream, loaded.state().patches.length)), hex(Arrays.copyOf(stream, loaded.state().patches.length)));
        state.data.unload();
        assertEquals(600f, ((Drill)Blocks.mechanicalDrill).drillTime, "unloaded");
        Oracle.WorldView view = Oracle.decodeWorldStream(stream);
        assertEquals(List.of(), view.warnings());
        assertEquals(loaded.state().patches.length, view.section("data patches").length());
        assertEquals(200f, ((Drill)Blocks.mechanicalDrill).drillTime, "the client applied Shard's patch");
        assertEquals(1, state.data.getImages().size, "and holds the sprite asset");
        Vanilla.reset();
        assertEquals(600f, ((Drill)Blocks.mechanicalDrill).drillTime, "and undoes it when the world unloads");
    }

    /**
     * Review 45: patched-map play after a save and a reload. Vanilla saves the played fixture as the server's
     * {@code save <slot> true} does (its assets embedded) and both sides load that save: Shard keeps the patch's text
     * and the sprite's bytes, both sides play the patched content, the data patch section is vanilla's, and the save
     * chunks and states stay equal for {@link ProductionRun#AFTER_LOAD} more frames. A save with the assets by hash
     * ({@code save <slot>}) is refused: Shard has no asset cache to serve the sprite from.
     */
    static void freshSave(ProductionRun run, ExternalAsset sprite) throws IOException{
        var byHash = new ByteArrayOutputStream();
        SaveIO.write(byHash, new SaveOptions());
        var refused = assertThrows(MsavException.class, () -> ProductionRun.LOADER.load(ProductionRun.READER.read(byHash.toByteArray()), Worlds.config(0),
            ProductionRun.LOADER.saveRules(ProductionRun.READER.read(byHash.toByteArray()))));
        assertTrue(refused.getMessage().contains("by hash only"), refused.getMessage());

        var embed = new SaveOptions();
        embed.embedAssets = true;
        var checked = new boolean[1];
        run.reload(embed, loaded -> {
            assertEquals(List.of(PATCH), loaded.state().assets.patches().stream().map(com.xpdustry.shard.content.patch.DataAsset.PatchAsset::text).toList(), "the patch's text, kept");
            ExternalAsset kept = loaded.state().assets.externals().getFirst();
            assertEquals(sprite.path(), kept.path());
            assertArrayEquals(SPRITE, kept.data(), "the sprite's bytes, kept");
            assertArrayEquals(sprite.hash(), kept.hash());
            var content = (Epoch)loaded.world().content();
            assertTrue(content.isPatched());
            assertEquals(200f, content.block("mechanical-drill").fields().f("drillTime"));
            assertEquals(0.08f, content.block("conveyor").fields().f("speed"));
            assertEquals(200f, ((Drill)mindustry.Vars.content.block("mechanical-drill")).drillTime, "vanilla's load patched its content again");
            var vanillaSection = new ByteArrayOutputStream();
            try{
                SaveIO.getSaveWriter().writeDataPatches(new DataOutputStream(vanillaSection), false);
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
            assertEquals(hex(vanillaSection.toByteArray()), hex(loaded.state().patches), "the data patch section after the reload");
            checked[0] = true;
        });
        assertTrue(checked[0]);
        run.chunks("save chunk " + ProductionRun.AFTER_LOAD + " frames after the reload");
        System.out.println("fixture: the fresh save played " + ProductionRun.AFTER_LOAD + " frames exact, " + copper(run.buildings()) + " copper in the containers");
    }

    /**
     * {@code NetServer.sendAssetRequirements}, {@code requestAssets} and {@code NetworkIO.loadAssets}: a client reading
     * Shard's requirement stream lists vanilla's hashes, and Shard's asset stream puts the sprite in its cache.
     */
    static void assetStreamsAreVanillas(com.xpdustry.shard.content.patch.DataAssets assets) throws IOException{
        Seq<DataAsset> external = state.data.getAllExternalAssets();
        assertEquals(1, external.size);
        // NetServer.sendAssetRequirements: writeRequiredAssets through Arc's FastDeflaterOutputStream, byte for byte
        var vanillaRequirements = new ByteArrayOutputStream();
        NetworkIO.writeRequiredAssets(new arc.util.io.FastDeflaterOutputStream(vanillaRequirements), external);
        assertEquals(hex(vanillaRequirements.toByteArray()), hex(AssetStreams.requirements(assets)), "the requirement stream's bytes");
        Seq<String> required = NetworkIO.readRequiredAssets(new InflaterInputStream(new ByteArrayInputStream(AssetStreams.requirements(assets))));
        assertEquals(List.of(DataAssetCache.encodeHash(external.first().byteHash)), List.of(required.toArray(String.class)));
        assertEquals(List.of(), AssetStreams.requested(assets, new short[]{-1, 4}), "out of range ids are skipped");
        var requested = AssetStreams.requested(assets, new short[]{0, 0});
        assertEquals(1, requested.size(), "a repeat is skipped");
        var vanilla = new ByteArrayOutputStream();
        NetworkIO.writeAssets(vanilla, external);
        assertEquals(hex(vanilla.toByteArray()), hex(AssetStreams.assets(requested)));
        NetworkIO.loadAssets(new ByteArrayInputStream(AssetStreams.assets(requested)));
        assertTrue(assetCache.has(DataAssetCache.encodeHash(external.first().byteHash)));
    }

    /**
     * Two mechanical drills on copper ore, each feeding three conveyors into a container, built and saved by vanilla:
     * once as is, once with {@link #PATCH} and the sprite loaded ({@code state.data.load}) and embedded.
     */
    static byte[][] fixture(){
        Vanilla.reset();
        var sim = Sim.empty(12, 9);
        for(int row : new int[]{1, 5}){
            for(int dx = 0; dx < 2; dx++) for(int dy = 0; dy < 2; dy++) world.tile(1 + dx, row + dy).setOverlay(Blocks.oreCopper);
            sim.place(Blocks.mechanicalDrill, 1, row, 0, Team.sharded);
            for(int x = 3; x <= 5; x++) sim.place(Blocks.conveyor, x, row, 0, Team.sharded);
            sim.place(Blocks.container, 6, row, 0, Team.sharded);
        }
        Vanilla.runPosted();
        state.rules.waveTimer = false;
        state.rules.waves = false;
        byte[] control = save(false);
        var patch = new PatchAsset(PATCH);
        var image = new ImageAsset();
        image.setPath("shard-mark.png");
        image.updateData(SPRITE);
        state.data.load(Seq.with(patch, image));
        assertFalse(patch.error, patch.warnings.toString());
        byte[] patched = save(true);
        state.data.unload();
        return new byte[][]{control, patched};
    }

    static byte[] save(boolean embed){
        var options = new SaveOptions();
        options.embedAssets = embed;
        var bytes = new ByteArrayOutputStream();
        try(var out = new DeflaterOutputStream(bytes)){
            SaveIO.write(out, options);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static int copper(List<mindustry.gen.Building> buildings){
        int total = 0;
        for(var b : buildings) if(b.block == Blocks.container) total += b.items.get(Items.copper);
        return total;
    }

    /**
     * As {@code ProductionMapOracleTest}: no units, no waves, no unported buildings that update. Also vanilla's entity
     * pools emptied: a bullet pooled by an earlier map in this JVM would come back with its old id, where a fresh one
     * draws a new id, as {@code LiquidRun} does.
     */
    static void pin(ProductionRun run, String map){
        emptyBulletPool();
        HazardPools.empty();
        for(mindustry.gen.Unit unit : Groups.unit.copy(new Seq<>())) unit.remove();
        unitPhysics.reset();
        try{
            var pending = mindustry.async.PhysicsProcess.class.getDeclaredField("pendingAdds");
            pending.setAccessible(true);
            ((Seq<?>)pending.get(unitPhysics)).clear();
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        unitPhysics.init();
        var units = new ArrayList<Unit>();
        for(int i = 0; i < run.shard.units().size(); i++) units.add(run.shard.units().get(i));
        for(Unit unit : units) unit.remove();
        state.rules.waveTimer = false;
        run.shard.part(WorldRules.class).rules.waveTimer = false;
        // GlobalVars.rand (logic's op rand) isn't in a save: Shard's takes vanilla's, as the logic runners do
        com.xpdustry.shard.sim.logic.LogicPart.require(run.shard).logic()
            .rand(new com.xpdustry.shard.common.math.Rand(mindustry.logic.GlobalVars.rand.seed0, mindustry.logic.GlobalVars.rand.seed1));
        var removed = new TreeMap<String, Integer>();
        var tiles = run.shard.tiles();
        var at = new ArrayList<int[]>();
        for(int pos = 0; pos < tiles.size(); pos++){
            if(run.shard.buildings().at(pos) instanceof PassthroughBuilding b && b.tile() == pos && run.vanilla(b.tileX(), b.tileY()).block.update){
                at.add(new int[]{b.tileX(), b.tileY()});
            }
        }
        for(int[] xy : at){
            removed.merge(world.tile(xy[0], xy[1]).block().name, 1, Integer::sum);
            run.remove(xy[0], xy[1]);
        }
        System.out.println(map + ": removed " + units.size() + " units and the unported buildings that update: " + removed);
    }

    /** {@code BlockRun.emptyBulletPool}: the pool made if missing, then emptied, keeping the random and the id counter. */
    static void emptyBulletPool(){
        long seed0 = arc.math.Mathf.rand.seed0, seed1 = arc.math.Mathf.rand.seed1;
        int id = ProductionRun.lastId();
        Bullet.create();
        arc.util.pooling.Pools.get(Bullet.class, () -> null).clear();
        try{
            var lastId = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            lastId.setAccessible(true);
            lastId.setInt(null, id);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
        arc.math.Mathf.rand.setState(seed0, seed1);
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
