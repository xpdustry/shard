package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.load.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EpochTest{
    static final Epoch vanilla = Epoch.vanilla();

    @Test
    void vanillaHasEveryContentTypeDense(){
        assertEquals(22, vanilla.items());
        assertEquals(11, vanilla.liquids());
        assertEquals(70, vanilla.units());
        assertEquals(Pack.vanilla().ids().of(ContentType.BLOCK).size(), vanilla.blocks());
        for(ContentType type : ContentType.values()){
            for(int id = 0; id < vanilla.count(type); id++){
                ContentDef def = vanilla.get(type, id);
                assertEquals(id, def.id());
                assertEquals(type, def.type());
                assertSame(def, vanilla.find(type, def.name()));
                assertFalse(def.added());
            }
        }
    }

    @Test
    void hashIsStableAndFollowsContent(){
        assertTrue(vanilla.hash().matches("[0-9a-f]{64}"), vanilla.hash());
        assertEquals(vanilla.hash(), Epoch.load(Pack.vanilla()).hash());
        Epoch changed = Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("world.yml", """
            - type: item
              id: copper
              overlay: true
              cost: 0.7
            """))));
        assertNotEquals(vanilla.hash(), changed.hash());
    }

    @Test
    void blocksHaveTypedFieldsAndKinds(){
        BlockDef duo = vanilla.block("duo");
        assertEquals(KindKey.of("item-turret"), duo.kind());
        assertEquals(KindKey.of("item-turret"), duo.buildKind());
        assertEquals(1, duo.size());
        assertEquals(250f, duo.health());
        assertEquals(Map.of("copper", 35), duo.requirements());
        assertTrue(duo.update());
        // Cliff.size is a float that hides Block.size; the typed getter reads Block's
        assertEquals(1, vanilla.block("cliff").size());
        assertEquals(KindKey.of("block"), vanilla.block("cliff").buildKind());
        // two vanilla building classes are both named DuctBridgeBuild
        assertNotEquals(vanilla.block("duct-bridge").buildKind(), vanilla.block("reinforced-bridge-conduit").buildKind());
    }

    @Test
    void blocksHaveTheTileFields(){
        BlockDef wall = vanilla.block("stone-wall"), core = vanilla.block("core-shard"), processor = vanilla.block("world-processor");
        assertTrue(wall.baked() && wall.solid() && wall.fillsTile() && wall.isStatic());
        assertFalse(wall.destructible() || wall.update());
        assertEquals(Map.of("sand", 1f), wall.attributes());
        assertTrue(vanilla.block("copper-wall").destructible());
        assertFalse(vanilla.block("cliff").fillsTile());
        assertTrue(processor.privileged() && processor.isA(KindKey.of("logic-block")));
        assertTrue(vanilla.block("duo").isA(KindKey.of("turret")));
        assertEquals(List.of("core", "hasFogRadius"), core.flags());
        assertTrue(core.targetable());
        assertEquals(-1, core.forceTeam());
        assertSame(vanilla.item("sand"), vanilla.block("sand-floor").itemDrop());
        assertNull(wall.itemDrop());
        assertTrue(vanilla.block("boulder").alwaysReplace() && vanilla.block("boulder").breakable());
        assertFalse(vanilla.block("empty").placeableOn());
        assertFalse(core instanceof FloorDef);
    }

    @Test
    void floorsAreFloorDefs(){
        var water = (FloorDef)vanilla.block("deep-water");
        assertTrue(water.isLiquid() && water.isDeep());
        assertSame(vanilla.status("wet"), water.status());
        assertSame(vanilla.liquid("water"), water.liquidDrop());
        assertFalse(water.isOverlay());
        var slag = (FloorDef)vanilla.block("molten-slag");
        assertTrue(slag.damages(), "a floor whose status hurts damages");
        assertTrue(((FloorDef)vanilla.block("ore-copper")).isOverlay());
        assertTrue(vanilla.block("air") instanceof FloorDef, "air is vanilla's AirBlock, a floor");
        int floors = 0;
        for(int id = 0; id < vanilla.blocks(); id++){
            BlockDef block = vanilla.block(id);
            assertEquals(block.isA(FloorDef.FLOOR), block instanceof FloorDef, block.name());
            if(block instanceof FloorDef) floors++;
        }
        assertEquals(107, floors);
    }

    @Test
    void unitsHaveTheirTypeFields(){
        UnitDef dagger = vanilla.unit("dagger"), atrax = vanilla.unit("atrax");
        assertTrue(dagger.entityClass().has("mech"));
        assertEquals(4, dagger.entityClass().classId());
        assertEquals("hook:default-controller", dagger.controller());
        assertTrue(dagger.physics() && dagger.useUnitCap() && dagger.targetable());
        assertTrue(atrax.entityClass().has("legs") && atrax.allowLegStep() && atrax.hovering());
        assertEquals(4, atrax.legCount());
        assertTrue(vanilla.unit("risso").naval());
        assertEquals("no-ai", vanilla.unit("dummy").controller());
        assertEquals("missile-ai", vanilla.unit("quell-missile").controller());
        assertTrue(vanilla.unit("quell-missile").entityClass().has("timed-kill"));
        assertEquals(0f, dagger.weapons().getFirst().baseRotation());
    }

    @Test
    void blocksHaveWhatTheirBuildingsRead(){
        BlockDef sorter = vanilla.block("sorter"), mender = vanilla.block("mender");
        assertTrue(sorter.configurable() && sorter.saveConfig() && sorter.instantTransfer());
        assertEquals("transportation", sorter.group());
        assertEquals(8, vanilla.block("core-shard").unitCapModifier());
        assertEquals(List.of("consume-power", "consume-items"), mender.consumers().stream().map(c -> c.kind().kindKey().name()).toList());
        assertTrue(mender.hasConsumers() && mender.consumesPower() && !mender.outputsPower());
        assertTrue(vanilla.block("combustion-generator").outputsPower());
    }

    @Test
    void unitsHaveWhatMiningBuildingAndCarryingRead(){
        UnitDef mono = vanilla.unit("mono"), poly = vanilla.unit("poly");
        assertEquals(1, mono.mineTier());
        assertEquals(2.5f, mono.mineSpeed());
        assertTrue(mono.mineItems().contains("copper"));
        assertEquals(0.4f, poly.buildSpeed());
        assertEquals(2, poly.mineTier());
        assertEquals(4 * 64f, vanilla.unit("mega").payloadCapacity(), "(2 * 2) * tilePayload");
        assertTrue(vanilla.unit("dagger").canDrown() && vanilla.unit("dagger").allowedInPayloads());
    }

    @Test
    void unitsHaveTheirControlMovementAndAbilities(){
        for(String core : List.of("alpha", "beta", "gamma")) assertEquals("hook:core-unit-controller", vanilla.unit(core).controller(), core);
        assertEquals("mine", vanilla.unit("mono").defaultCommand());
        assertTrue(vanilla.unit("dagger").commands().contains("move") && vanilla.unit("dagger").playerControllable());
        assertEquals(2f, vanilla.unit("nova").boostMultiplier());
        UnitDef arkyid = vanilla.unit("arkyid");
        assertEquals(1f, arkyid.legMoveSpace());
        assertEquals(32f, arkyid.legSplashDamage());
        AbilityDef field = vanilla.unit("oct").abilityDefs().stream().filter(a -> a.isA(KindKey.of("force-field-ability"))).findFirst().orElseThrow();
        assertEquals(7000f, field.max());
        assertEquals(vanilla.unit("oct").abilityDefs().stream().map(AbilityDef::kind).toList(), vanilla.unit("oct").abilityKinds());
    }

    @Test
    void unitsHaveMirroredWeaponsWithTheirBullets(){
        UnitDef dagger = vanilla.unit("dagger");
        assertEquals("MechUnit", dagger.entity());
        List<WeaponDef> weapons = dagger.weapons();
        assertEquals(2, weapons.size(), "the mirrored copy is a weapon of its own");
        assertEquals(-weapons.get(0).x(), weapons.get(1).x());
        assertSame(weapons.get(0).bullet(), weapons.get(1).bullet());
        assertEquals("dagger/weapons.0.bullet", weapons.get(0).bullet().name());
        assertEquals(9f, weapons.get(0).bullet().damage());
        assertEquals(KindKey.of("unit-type"), vanilla.unit(69).kind());
        assertEquals("turret-unit-build-tower", vanilla.unit(69).name());
    }

    @Test
    void referencesResolveToDefinitions(){
        assertSame(vanilla.status("wet"), vanilla.liquid("water").effect());
        assertSame(vanilla.status("wet"), vanilla.weather("rain").status());
        assertEquals(List.of(vanilla.status("burning"), vanilla.status("melting")), vanilla.status("wet").opposites());
        assertSame(vanilla.bullet("duo/ammoTypes.copper"), vanilla.bullet(vanilla.id(ContentType.BULLET, "duo/ammoTypes.copper")));
    }

    /** The getters :compat and :logic read; the oracle tests there check the same values against a booted vanilla. */
    @Test
    void namesAndClientTables(){
        assertEquals(Pack.vanilla().ids().of(ContentType.ITEM), vanilla.names(ContentType.ITEM));
        assertEquals("copper", vanilla.names(ContentType.ITEM).getFirst());
        assertEquals(vanilla.id(ContentType.BLOCK, "duo"), vanilla.block("duo").id());
        assertEquals(List.of("move", "repair"), vanilla.unitCommands().subList(0, 2));
        assertEquals("stop", vanilla.unitStances().getFirst());
        assertEquals(95, vanilla.sectors().size());
        assertEquals("groundZero", vanilla.sectors().getFirst());
        assertEquals(List.of("sun", "erekir", "gier", "notva", "tantros", "serpulo", "verilus"), vanilla.planets());
        // logicids.dat: not every item and block is lookable
        assertEquals(List.of(262, 56, 20, 11), List.of(vanilla.logicIds(ContentType.BLOCK).size(), vanilla.logicIds(ContentType.UNIT).size(),
            vanilla.logicIds(ContentType.ITEM).size(), vanilla.logicIds(ContentType.LIQUID).size()));
        assertEquals(List.of(), vanilla.logicIds(ContentType.STATUS));
        assertEquals(63544, vanilla.item("copper").emoji());
        assertEquals(0, vanilla.bullet("duo/ammoTypes.copper").emoji());
        assertEquals("sharded", vanilla.teamName(1));
        assertEquals("team#7", vanilla.teamName(7));
    }

    @Test
    void blocksKnowTheirBuildingsAndUnitsTheirFlags(){
        assertTrue(vanilla.block("duo").hasBuilding());
        assertTrue(vanilla.block("duo").ownsBlockUnit());
        assertFalse(vanilla.block("segment").ownsBlockUnit(), "point defense is a ReloadTurret, not a Turret");
        assertFalse(vanilla.block("stone-wall").hasBuilding());
        assertTrue(vanilla.block("copper-wall").hasBuilding());
        assertTrue(vanilla.block("legacy-mech-pad").legacy());
        assertFalse(vanilla.block("router").legacy());
        assertTrue(vanilla.unit("block").internal());
        assertFalse(vanilla.unit("dagger").internal());
    }

    @Test
    void addedContentIsFlagged(){
        Epoch epoch = Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("world.yml", """
            - type: item
              id: test-ore
              wire: added
              kind: item
              hardness: 2
            """))));
        ItemDef ore = epoch.item("test-ore");
        assertTrue(ore.added());
        assertEquals(vanilla.items(), ore.id());
        assertEquals(2, ore.hardness());
    }
}
