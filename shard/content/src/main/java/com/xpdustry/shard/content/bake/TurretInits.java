package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

import static com.xpdustry.shard.content.bake.Bake.*;
import static com.xpdustry.shard.content.bake.BlockInits.*;
import static com.xpdustry.shard.content.bake.Values.*;

/** The turret kinds' {@code init()}. Blocks initialize before bullets, so ammo is read un-initialized, as in vanilla. */
final class TurretInits{
    private TurretInits(){
    }

    static void register(){
        // BaseTurret.java:46
        init("base-turret", (bake, b, sup) -> {
            Mut coolant = b.m("coolant");
            if(coolant == null){
                coolant = find(b, "consume-coolant");
                b.set("coolant", coolant);
            }else{
                // the pack writes the coolant and its consumer separately; in vanilla they are one object
                for(Mut c : b.muts("consumes")){
                    if(c.freeze().equals(coolant.freeze())){
                        coolant = c;
                        b.set("coolant", c);
                        break;
                    }
                }
            }
            checkInitCoolant(bake, b);
            float range = b.f("range");
            if(!b.b("disableOverlapCheck")) b.set("placeOverlapRange", Math.max(b.f("placeOverlapRange"), range + b.f("placeOverlapMargin")));
            b.set("fogRadius", Math.max(Mathf.round(range / tilesize * b.f("fogRadiusMultiplier")), b.i("fogRadius")));
            sup.run();
        });
        // Turret.java:254
        init("turret", (bake, b, sup) -> {
            if(b.f("shootY") == Float.NEGATIVE_INFINITY) b.set("shootY", b.i("size") * tilesize / 2f);
            if(b.f("elevation") < 0) b.set("elevation", b.i("size") / 2f);
            if(b.f("recoilTime") < 0f) b.set("recoilTime", b.f("reload"));
            if(b.f("cooldownTime") < 0f) b.set("cooldownTime", b.f("reload"));
            if(b.f("newTargetInterval") <= 0f) b.set("newTargetInterval", b.f("targetInterval"));
            if(!b.b("targetGround")) b.set("disableOverlapCheck", true);
            sup.run();
            b.set("trackingRange", Math.max(b.f("range"), b.f("trackingRange")));
        });
        init("item-turret", (bake, b, sup) -> {
            consume(bake, b, bake.create("consume-item-filter").set("filter", "hook:item-turret.filter"));
            ammoOverlap(bake, b);
            sup.run();
        });
        init("liquid-turret", (bake, b, sup) -> {
            consume(bake, b, bake.create("consume-liquid-filter").set("filter", "hook:liquid-turret.filter").set("amount", 1f));
            ammoOverlap(bake, b);
            sup.run();
        });
        init("continuous-liquid-turret", (bake, b, sup) -> {
            consume(bake, b, bake.create("consume-liquid-filter").set("filter", "hook:continuous-liquid-turret.filter")
                .set("amount", b.get("liquidConsumed")).set("multiplier", "hook:continuous-liquid-turret.multiplier"));
            ammoOverlap(bake, b);
            sup.run();
        });
        init("laser-turret", (bake, b, sup) -> {
            sup.run();
            if(b.get("coolant") == null) b.set("coolant", find(b, "consume-liquid-base"));
        });
        init("tractor-beam-turret", (bake, b, sup) -> {
            sup.run();
            b.set("clipSize", Math.max(b.f("clipSize"), b.i("size") * tilesize + (b.f("range") + tilesize) * 2f));
        });
        // BuildTurret.java:61: the turret's builder unit is content of its own, created here
        init("build-turret", (bake, b, sup) -> {
            sup.run();
            if(b.f("elevation") < 0) b.set("elevation", b.i("size") / 2f);
            String name = "turret-unit-" + bake.name(b);
            Mut unit = bake.create("unit-type")
                .set("hidden", true)
                .set("internal", true)
                .set("speed", 0f)
                .set("hitSize", 0f)
                .set("health", 1f)
                .set("itemCapacity", 0)
                .set("rotateSpeed", b.get("rotateSpeed"))
                .set("buildBeamOffset", b.get("buildBeamOffset"))
                .set("buildRange", b.get("range"))
                .set("buildSpeed", b.get("buildSpeed"))
                .set("constructor", "BlockUnitUnit");
            bake.derive(ContentType.UNIT, name, unit);
            b.set("unitType", name);
        });
    }

    // BaseTurret.java:70
    private static void checkInitCoolant(Bake bake, Mut b){
        Mut coolant = b.m("coolant");
        if(coolant == null) return;
        coolant.set("update", false);
        coolant.set("booster", true);
        coolant.set("optional", true);
        // json parsing does not add to consumes
        boolean has = false;
        for(Mut c : b.muts("consumes")) has |= c.same(coolant);
        if(!has) consume(bake, b, coolant);
    }

    private static void ammoOverlap(Bake bake, Mut b){
        if(!b.b("targetGround")) return;
        for(Object bullet : b.map("ammoTypes").values()){
            float change = bake.get(ContentType.BULLET, (String)bullet).f("rangeChange");
            b.set("placeOverlapRange", Math.max(b.f("placeOverlapRange"), b.f("range") + change + b.f("placeOverlapMargin")));
        }
    }
}
