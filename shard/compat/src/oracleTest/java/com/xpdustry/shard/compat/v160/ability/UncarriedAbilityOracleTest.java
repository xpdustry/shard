package com.xpdustry.shard.compat.v160.ability;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;
import com.xpdustry.shard.oracle.*;
import mindustry.entities.abilities.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP62) for the three abilities no vanilla unit carries, so vanilla's pack has no kind for them: the schema gets
 * them here, with vanilla's class defaults, and an overlay gives them to units, as the same objects added to vanilla's
 * types do. Daggers wear an {@code ArmorPlateAbility} and make flares ({@code UnitSpawnAbility}); fast horizons throw
 * lightning as they fly ({@code MoveLightningAbility}). Exact frame by frame for 600 frames ({@link AbilityRun}).
 */
class UncarriedAbilityOracleTest{
    static final String KINDS = """
          armor-plate-ability:
            class: mindustry.entities.abilities.ArmorPlateAbility
            parent: ability
            fields:
              healthMultiplier: {type: float, tag: shared, default: 0.2}
          move-lightning-ability:
            class: mindustry.entities.abilities.MoveLightningAbility
            parent: ability
            fields:
              alternate: {type: bool, tag: shared, default: true}
              bullet: {type: bullet, tag: shared, default: null}
              bulletAngle: {type: float, tag: shared, default: 0.0}
              bulletSpread: {type: float, tag: shared, default: 0.0}
              chance: {type: float, tag: shared, default: 0.15}
              damage: {type: float, tag: shared, default: 35.0}
              length: {type: int, tag: shared, default: 12}
              maxSpeed: {type: float, tag: shared, default: 1.2}
              minSpeed: {type: float, tag: shared, default: 0.8}
              x: {type: float, tag: shared, default: 0.0}
              y: {type: float, tag: shared, default: 0.0}
          unit-spawn-ability:
            class: mindustry.entities.abilities.UnitSpawnAbility
            parent: ability
            fields:
              spawnTime: {type: float, tag: shared, default: 60.0}
              spawnX: {type: float, tag: shared, default: 0.0}
              spawnY: {type: float, tag: shared, default: 0.0}
              unit: {type: unit, tag: shared, default: null}
        """;

    static final String OVERLAY = """
        - type: unit
          id: dagger
          overlay: true
          abilities:
            - {kind: armor-plate-ability}
            - {kind: unit-spawn-ability, unit: flare, spawnTime: 150.0, spawnX: 4.0, spawnY: -6.0}
        - type: unit
          id: horizon
          overlay: true
          abilities:
            - {kind: move-lightning-ability, minSpeed: 0.5, maxSpeed: 1.0, chance: 0.3, x: 4.0, y: 2.0}
        """;

    static Epoch epoch(){
        var diagnostics = new Diagnostics();
        String path = "content/vanilla/kinds.yml";
        Schema schema = Schema.read(YamlReader.read(path, Schema.resource(path) + KINDS, diagnostics), diagnostics);
        diagnostics.check();
        Pack vanilla = Pack.vanilla();
        var files = new ArrayList<>(vanilla.files());
        files.add(new Pack.Source("abilities.yml", OVERLAY));
        return Epoch.load(new Pack(vanilla.name(), schema, vanilla.ids(), files));
    }

    @Test
    void armorPlatesSpawnersAndMovingLightning(){
        Vanilla.boot();
        Epoch epoch = epoch();
        UnitType dagger = content.unit("dagger"), horizon = content.unit("horizon");
        var daggerAbilities = dagger.abilities.copy();
        var horizonAbilities = horizon.abilities.copy();
        try(var run = new AbilityRun(50, "stone", epoch)){
            // Shard sends the flare as it is after the tick (CombatCallMapping), vanilla as it was when made
            run.inexact(com.xpdustry.shard.wire.v160_7.Packets.UnitSpawn.ID);
            dagger.abilities.add(new ArmorPlateAbility(), new UnitSpawnAbility(content.unit("flare"), 150f, 4f, -6f));
            // damage, length, chance, y, minSpeed, maxSpeed, color
            horizon.abilities.add(new MoveLightningAbility(35f, 12, 0.3f, 2f, 0.5f, 1f, arc.graphics.Color.white){{
                x = 4f;
            }});
            int armored = run.fighter("dagger", Team.sharded, 160f, 200f, 0f, 160f, 200f, Team.crux, 140f);
            run.fighter("dagger", Team.sharded, 160f, 230f, 0f, 160f, 230f, Team.crux, 140f);
            for(int i = 0; i < 3; i++) run.fighter("dagger", Team.crux, 280f, 180f + i * 25f, 180f, 260f, 190f + i * 15f, Team.sharded, 140f);
            // horizons fly back and forth over the field
            run.fighter("horizon", Team.crux, 40f, 100f, 0f, 360f, 100f, null, 0f);
            run.fighter("horizon", Team.crux, 360f, 320f, 180f, 40f, 320f, null, 0f);
            float[] multiplier = {1f};
            int[] flares = {0};
            run.watches.add(() -> {
                var v = run.vanillaUnits.get(armored);
                if(v != null && v.isAdded()) multiplier[0] = Math.max(multiplier[0], v.healthMultiplier);
                int n = 0;
                for(var u : mindustry.gen.Groups.unit) if(u.type == content.unit("flare")) n++;
                flares[0] = Math.max(flares[0], n);
            });
            run.run(600);
            assertTrue(multiplier[0] > 1.1f, "the plate armored the shooting dagger (" + multiplier[0] + ")");
            assertTrue(flares[0] >= 2, "the daggers made flares (" + flares[0] + ")");
            assertTrue(lightning() > 0, "the horizons threw lightning");
        }finally{
            dagger.abilities.set(daggerAbilities);
            horizon.abilities.set(horizonAbilities);
        }
    }

    static int lightning(){
        try{
            var field = mindustry.entities.Lightning.class.getDeclaredField("lastSeed");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
