package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.DataAsset.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard's port of vanilla's patcher and asset section without vanilla; {@code ContentGatesTest.mapPatchesApplyAsVanillas}
 * in {@code :tools:content-extract} holds the patcher to vanilla's.
 */
class DataPatcherTest{
    static final Epoch vanilla = Epoch.vanilla();

    static Epoch patched(String... texts){
        var patches = new ArrayList<PatchAsset>();
        for(int i = 0; i < texts.length; i++) patches.add(new PatchAsset("patch-" + i + ".json", texts[i]));
        return DataPatcher.apply(vanilla, patches, "serpulo").epoch();
    }

    @Test
    void aPatchMakesTheWorldsOwnEpochAndLeavesTheBase(){
        Epoch world = patched("{\"block.conveyor.speed\": 0.08}");
        assertTrue(world.isPatched());
        assertSame(vanilla, world.base());
        assertNotEquals(vanilla.hash(), world.hash());
        assertEquals(0.08f, world.block("conveyor").fields().f("speed"));
        assertEquals(0.035f, vanilla.block("conveyor").fields().f("speed"), "the base is untouched");
        assertEquals(vanilla.id(com.xpdustry.shard.content.ContentType.BLOCK, "conveyor"), world.block("conveyor").id(), "ids are the base's");
        assertTrue(vanilla.serves(world));
        assertTrue(world.serves(vanilla));
    }

    @Test
    void twoWorldsDontSeeEachOthersPatch(){
        Epoch fast = patched("{\"block.conveyor.speed\": 0.08}");
        Epoch tough = patched("{\"block.conveyor.health\": 500}");
        assertEquals(0.08f, fast.block("conveyor").fields().f("speed"));
        assertEquals(45, fast.block("conveyor").fields().i("health"));
        assertEquals(0.035f, tough.block("conveyor").fields().f("speed"));
        assertEquals(500, tough.block("conveyor").fields().i("health"));
        assertNotEquals(fast.hash(), tough.hash());
    }

    @Test
    void aPatchThatChangesNothingIsTheBase(){
        // hidden/242's and littoralShipyard's patches hold only a name
        assertSame(vanilla, patched("{\"name\":\"Patch0\"}"));
        assertSame(vanilla, patched("{\"name\":\"a\"}", "{\"name\":\"b\"}"));
    }

    @Test
    void dottedNestedAndHjsonFormsAgree(){
        Epoch dotted = patched("{\"block.mechanical-drill.drillTime\": 300, \"block.mechanical-drill.tier\": 3}");
        Epoch nested = patched("{\"block\": {\"mechanical-drill\": {\"drillTime\": 300, \"tier\": 3}}}");
        Epoch hjson = patched("""
            # braces are optional
            block.mechanical-drill: {
              drillTime: 300
              tier: 3 // a comment
            }
            """);
        assertEquals(dotted.hash(), nested.hash());
        assertEquals(dotted.hash(), hjson.hash());
        assertEquals(300f, dotted.block("mechanical-drill").fields().f("drillTime"));
    }

    @Test
    void laterKeysWin(){
        assertEquals(500, patched("{\"block.duo.health\": 400, \"block.duo.health\": 500}").block("duo").fields().i("health"));
        assertEquals(500, patched("{\"block.duo.health\": 400}", "{\"block.duo.health\": 500}").block("duo").fields().i("health"));
    }

    @Test
    void requiredPlanetsDropsAPatchOffItsPlanet(){
        var erekir = new PatchAsset("e.json", "{\"requiredPlanets\": \"erekir\", \"block.conveyor.speed\": 1}");
        var both = new PatchAsset("b.json", "{\"requiredPlanets\": [\"serpulo\", \"erekir\"], \"block.conveyor.speed\": 0.5}");
        var applied = DataPatcher.apply(vanilla, List.of(erekir, both), "serpulo");
        assertEquals(List.of(both), applied.kept());
        assertEquals(0.5f, applied.epoch().block("conveyor").fields().f("speed"));
    }

    @Test
    void vanillasWarningsAreSkipped(){
        var applied = DataPatcher.apply(vanilla, List.of(new PatchAsset("w.json", """
            {"block.conveyor.size": 2, "block.no-such-block.health": 5, "nothing.x": 1, "block.duo.health": 99}
            """)), "serpulo");
        assertEquals(5, applied.warnings().size(), applied.warnings().toString());
        assertEquals(1, applied.epoch().block("conveyor").fields().i("size"), "@NoPatch");
        assertEquals(99, applied.epoch().block("duo").fields().i("health"));
    }

    @Test
    void aUnitsPathTypeIsDerivedAgain(){
        Epoch world = patched("{\"unit.mono.naval\": true}");
        var mono = world.unit(vanilla.id(com.xpdustry.shard.content.ContentType.UNIT, "mono")).fields();
        assertEquals(2, mono.i("flowfieldPathType"));
        assertEquals("cost:costNaval", mono.s("pathCost"));
        assertEquals(3, mono.i("pathCostId"));
    }

    @Test
    void whatShardCantDoLikeVanillaIsRefused(){
        for(String text : List.of(
            "{\"block.duo.ammoTypes.copper.damage\": 50}",
            "{\"block.duo.shootType\": {\"type\": \"BasicBulletType\"}}",
            "{\"block.graphite-press.consumes\": {\"power\": 1}}",
            "{\"block.conveyor.noSuchField\": 1}",
            "{\"block.ground-factory.itemCapacity\": 1}",
            "{\"block.conveyor\": \"-\"}",
            "{\"planet.serpulo.radius\": 2}",
            "{\"block.conveyor.speed\": '''\n0.1\n'''}",
            "[1, 2]")){
            assertThrows(DataPatcher.Unsupported.class, () -> patched(text), text);
        }
    }

    @Test
    void jsonKeepsOrderDuplicatesAndNumberTypes(){
        var obj = (PatchJson.Obj)PatchJson.read("a: 1\nb: 1.5\na: x y\nc: [1, 2,]\nd: '\\u0041'");
        assertEquals(List.of("a", "b", "a", "c", "d"), obj.members().stream().map(Map.Entry::getKey).toList());
        assertEquals(1L, obj.members().get(0).getValue());
        assertEquals(1.5, obj.members().get(1).getValue());
        assertEquals("x y", obj.members().get(2).getValue());
        assertEquals(List.of(1L, 2L), obj.members().get(3).getValue());
        assertEquals("A", obj.members().get(4).getValue());
    }

    // the asset section

    static byte[] section(int formatVersion, Object... assets) throws IOException{
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(formatVersion);
        out.writeInt(assets.length / 3);
        for(int i = 0; i < assets.length; i += 3){
            int type = (Integer)assets[i];
            out.writeByte(type);
            out.writeUTF((String)assets[i + 1]);
            byte[] data = (byte[])assets[i + 2];
            boolean embedded = data != null;
            out.writeBoolean(embedded);
            if(type == 1) out.writeShort(6);
            if(embedded){
                out.writeInt(data.length);
                out.write(data);
            }else{
                out.write(new byte[32]);
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void sectionsReadAndWriteAsVanillas() throws IOException{
        byte[] png = {(byte)0x89, 'P', 'N', 'G', 1, 2, 3};
        byte[] file = section(2, 3, "sprites\\dp-thing.png", png, 0, "patch-a.json", "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        List<DataAsset> read = DataAssets.read(file, 13);
        assertEquals(2, read.size());
        assertEquals("sprites/dp-thing.png", read.get(0).path(), "setPath turns backslashes into slashes");
        var assets = DataAssets.loaded(read);
        assertInstanceOf(PatchAsset.class, assets.all().get(0), "DataManager orders by type");
        ExternalAsset image = assets.externals().getFirst();
        assertArrayEquals(com.xpdustry.shard.common.io.Sha256.of(png), image.hash());
        assertTrue(image.held());

        // a server writes the image by hash, a map export embeds it
        var stream = new DataInputStream(new ByteArrayInputStream(assets.write()));
        assertEquals(PatchSection.formatVersion, stream.readInt());
        assertEquals(2, stream.readInt());
        assertEquals(0, stream.readByte());
        assertEquals("patch-a.json", stream.readUTF());
        assertTrue(stream.readBoolean());
        stream.skipNBytes(stream.readInt());
        assertEquals(3, stream.readByte());
        assertEquals("sprites/dp-thing.png", stream.readUTF());
        assertFalse(stream.readBoolean());
        assertArrayEquals(image.hash(), stream.readNBytes(32));
        assertEquals(0, stream.available());
        assertEquals(DataAssets.loaded(read), DataAssets.loaded(DataAssets.read(assets.write(true), 13)));
        assertArrayEquals(assets.write(), DataAssets.loaded(DataAssets.read(assets.write(), 13)).write(), "a written section reads back alike");
    }

    @Test
    void anEmptyEmbeddedAssetHasNoHashAndWritesEmpty() throws IOException{
        var assets = DataAssets.loaded(DataAssets.read(section(2, 3, "a.png", new byte[0]), 13));
        assertNull(assets.externals().getFirst().hash());
        var stream = new DataInputStream(new ByteArrayInputStream(assets.write()));
        stream.skipNBytes(8 + 1 + 2 + "a.png".length());
        assertTrue(stream.readBoolean(), "embedded, having no hash");
        assertEquals(0, stream.readInt());
    }

    @Test
    void oldSectionsGetStablePaths() throws IOException{
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(1);
        byte[] text = "{\"block.duo.health\": 9}".getBytes(StandardCharsets.UTF_8);
        out.writeInt(text.length);
        out.write(text);
        List<DataAsset> first = DataAssets.read(bytes.toByteArray(), 11), second = DataAssets.read(bytes.toByteArray(), 11);
        assertEquals(first, second);
        assertTrue(first.getFirst().path().startsWith("patch-") && first.getFirst().path().endsWith(".json"));
    }

    @Test
    void badSectionsAreRefused() throws IOException{
        assertThrows(IllegalArgumentException.class, () -> DataAssets.read(section(2, 9, "x", new byte[1]), 13), "type 9");
        assertThrows(IllegalArgumentException.class, () -> DataAssets.read(Arrays.copyOf(section(2, 0, "p", new byte[4]), 20), 13), "cut short");
        assertThrows(IllegalArgumentException.class, () -> DataAssets.read(section(2, 0, "p", null), 13), "a patch by hash");
    }

    @Test
    void worldsOfOneMapShareAnEpochThatLivesOnlyWhileTheyDo(){
        var patches = List.of(new PatchAsset("cache-test.json", "{\"block.conveyor.speed\": 0.0731}"));
        Epoch first = DataPatcher.of(vanilla, patches, "serpulo").epoch();
        Epoch second = DataPatcher.of(vanilla, patches, "serpulo").epoch();
        assertSame(first, second, "worlds of one map share one epoch");
        assertTrue(first.isPatched());
        int alive = DataPatcher.alive();
        assertTrue(alive >= 1);

        var ref = new java.lang.ref.WeakReference<>(first);
        first = null;
        second = null;
        // a count, not a deadline: each round asks for a collection; a pinned epoch never goes
        int rounds = 0;
        while(ref.get() != null && rounds++ < 200){
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(ref.get(), "the cache still pins an epoch no world uses, after " + rounds + " collections");
        assertEquals(alive - 1, DataPatcher.alive());
        var again = DataPatcher.of(vanilla, patches, "serpulo").epoch();
        assertEquals(0.0731f, again.block("conveyor").fields().f("speed"), "a dropped epoch is applied again");
    }
}
