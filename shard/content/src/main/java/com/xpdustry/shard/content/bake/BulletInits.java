package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

import java.util.*;

import static com.xpdustry.shard.content.bake.Bake.*;

/** {@code BulletType.init()}, {@code calculateRange()} and {@code estimateDPS()}, which turrets and units read. */
final class BulletInits{
    private BulletInits(){
    }

    static void register(){
        // BulletType.java:832
        init("bullet-type", (bake, b, sup) -> {
            if(b.i("pierceCap") >= 1) b.set("pierce", true);
            if(b.b("setDefaults")){
                if(b.i("lightning") > 0 && "none".equals(b.s("status"))) b.set("status", "shocked");
                if(b.get("fragBullet") != null || b.f("splashDamageRadius") > 0 || b.i("lightning") > 0) b.set("despawnHit", true);
            }
            if(b.get("fragBullet") != null){
                Mut frag = bullet(bake, b.s("fragBullet"));
                frag.set("keepVelocity", false);
                frag.set("scaleKeepVelocity", false);
            }
            if(b.get("lightningType") == null){
                b.set("lightningType", !b.b("collidesAir") ? "damage-lightning-ground" : !b.b("collidesGround") ? "damage-lightning-air"
                    : "damage-lightning");
            }
            if(b.f("lightRadius") <= -1) b.set("lightRadius", Math.max(18, b.f("hitSize") * 5f));
            b.set("drawSize", Math.max(b.f("drawSize"), b.i("trailLength") * b.f("speed") * 2f));
            b.set("range", calculateRange(bake, b));
        });
        for(String kind : List.of("shrapnel-bullet-type", "continuous-bullet-type", "laser-bullet-type")){
            init(kind, (bake, b, sup) -> {
                sup.run();
                b.set("drawSize", Math.max(b.f("drawSize"), b.f("length") * 2f));
            });
        }
    }

    static Mut bullet(Bake bake, String name){
        return bake.get(ContentType.BULLET, name);
    }

    /** The farthest a bullet of this type travels. */
    static float calculateRange(Bake bake, Mut b){
        b = b.base; // virtual: the object's own kind decides
        if(b.is("multi-bullet-type")){
            float max = 0f;
            for(Object other : b.list("bullets")) max = Math.max(max, calculateRange(bake, bullet(bake, (String)other)));
            return max;
        }
        if(b.is("rail-bullet-type")) return b.f("length");
        if(b.is("lightning-bullet-type")) return (b.i("lightningLength") + b.i("lightningLengthRand") / 2f) * 6f;
        if(b.is("laser-bullet-type") || b.is("continuous-bullet-type") || b.is("shrapnel-bullet-type") || b.is("sap-bullet-type")){
            return Math.max(b.f("length"), b.f("maxRange"));
        }
        if(b.f("rangeOverride") > 0) return b.f("rangeOverride");
        Mut spawn = unit(bake, b.s("spawnUnit")), despawn = unit(bake, b.s("despawnUnit"));
        if(spawn != null) return spawn.f("lifetime") * spawn.f("speed");
        if(despawn != null) return despawn.f("lifetime") * despawn.f("speed");
        float drag = b.f("drag"), speed = b.f("speed"), lifetime = b.f("lifetime");
        return Math.max(Mathf.zero(drag) ? speed * lifetime : speed * (1f - Mathf.pow(1f - drag, lifetime)) / drag, b.f("maxRange"));
    }

    /** Estimated damage per shot, for {@code UnitType.estimateDps}; BulletType caches it in {@code cachedDps}. */
    static float estimateDPS(Bake bake, Mut b){
        b = b.base;
        if(b.is("continuous-bullet-type") && b.b("continuous") || b.is("point-laser-bullet-type")){
            return b.f("damage") * 100f / b.f("damageInterval") * 3f;
        }
        if(b.is("laser-bullet-type")) return baseDPS(bake, b) * 3f;
        if(b.is("lightning-bullet-type")) return baseDPS(bake, b) * Math.max(b.i("lightningLength") / 10f, 1);
        if(b.is("multi-bullet-type")){
            float sum = 0f;
            for(Object other : b.list("bullets")) sum += estimateDPS(bake, bullet(bake, (String)other));
            return sum;
        }
        return baseDPS(bake, b);
    }

    // BulletType.java:422
    private static float baseDPS(Bake bake, Mut b){
        Float cached = bake.cachedDps.get(b.base);
        if(cached != null) return cached;
        Mut spawn = unit(bake, b.s("spawnUnit")), despawn = unit(bake, b.s("despawnUnit"));
        if(spawn != null) return UnitInits.estimateDps(bake, spawn);
        if(despawn != null) return UnitInits.estimateDps(bake, despawn);
        int pierceCap = b.i("pierceCap");
        float sum = (b.f("damage") + b.f("splashDamage") * 0.75f) * (b.b("pierce") ? pierceCap == -1 ? 2 : Mathf.clamp(pierceCap, 1, 2) : 1f);
        String frag = b.s("fragBullet");
        if(frag != null && !frag.equals(bake.name(b))) sum += estimateDPS(bake, bullet(bake, frag)) * b.i("fragBullets") / 2f;
        for(Object other : b.list("spawnBullets")) sum += estimateDPS(bake, bullet(bake, (String)other));
        bake.cachedDps.put(b.base, sum);
        return sum;
    }

    static boolean heals(Mut b){
        return b.f("healPercent") > 0 || b.f("healAmount") > 0;
    }

    private static Mut unit(Bake bake, String name){
        return name == null ? null : bake.get(ContentType.UNIT, name);
    }
}
