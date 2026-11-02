package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

import static com.xpdustry.shard.content.bake.Bake.*;
import static com.xpdustry.shard.content.bake.BulletInits.*;
import static com.xpdustry.shard.content.bake.Values.*;

/** {@code UnitType.init()}: movement presets from the entity class, ranges, mirrored weapons, commands, DPS. */
final class UnitInits{
    private UnitInits(){
    }

    /** {@code ControlPathfinder.costTypes}, whose index is {@code pathCostId}. */
    private static final List<String> costTypes = List.of("cost:costGround", "cost:costHover", "cost:costLegs", "cost:costNaval");

    static void register(){
        init("unit-type", UnitInits::unit);
        postInit("unit-type", (bake, u, sup) -> {
            String tag = u.s("databaseTag");
            if(tag == null || tag.isEmpty()) u.set("databaseTag", u.b("flying") ? "unit-air" : u.b("naval") ? "unit-naval" : "unit-ground");
            sup.run();
        });
        postInit("unlockable-content", (bake, c, sup) -> {
            String category = c.s("databaseCategory");
            if(category == null || category.isEmpty()) c.set("databaseCategory", bake.type(c).yamlName());
            String tag = c.s("databaseTag");
            if(tag == null || tag.isEmpty()) c.set("databaseTag", "default");
            for(Object planet : c.list("shownPlanets")) addToSet(bake, c, "databaseTabs", "planet:" + planet);
        });
        init("weapon", (bake, w, sup) -> {
            if(w.b("alwaysContinuous")) w.set("continuous", true);
        });
        init("repair-beam-weapon", (bake, w, sup) -> {
            sup.run();
            bullet(bake, w.s("bullet")).set("healPercent", w.get("fractionRepairSpeed"));
        });
        init("suppression-field-ability", (bake, a, sup) -> {
            if(!a.b("active")) a.set("display", false);
        });
    }

    // UnitType.java:915
    private static void unit(Bake bake, Mut u, Runnable sup){
        sup.run();
        Schema.Entity example = bake.schema.entity(u.s("constructor"));
        if(example == null){
            bake.diagnostics.error(Mark.none, "unit " + bake.name(u) + " has no entity class");
            return;
        }
        u.set("allowLegStep", example.has("legs") || example.has("crawl"));

        // water preset
        if(example.has("water-move") || example.has("water-crawl")){
            u.set("naval", true);
            u.set("canDrown", false);
            u.set("emitWalkSound", false);
            u.set("omniMovement", false);
            addToSet(bake, u, "immunities", "wet");
            if(u.f("shadowElevation") < 0f) u.set("shadowElevation", 0.11f);
        }

        initPathType(u);

        if(u.b("flying")) u.set("envEnabled", u.i("envEnabled") | 2); // Env.space

        float hitSize = u.f("hitSize");
        if(unset(u, "deathSound")) u.set("deathSound", hitSize < 12f ? "sound:unitExplode1" : hitSize < 22f ? "sound:unitExplode2" : "sound:unitExplode3");
        if(unset(u, "wreckSound")) u.set("wreckSound", hitSize >= 22f ? "sound:wreckFallBig" : "sound:wreckFall");
        if(u.f("lightRadius") == -1) u.set("lightRadius", Math.max(60f, hitSize * 2.3f));
        if(u.f("flyingLayer") < 0) u.set("flyingLayer", u.b("lowAltitude") ? 90f : 115f); // Layer.flyingUnitLow, flyingUnit
        u.set("clipSize", Math.max(u.f("clipSize"), u.f("lightRadius") * 1.1f));

        List<Mut> weapons = u.muts("weapons");
        u.set("singleTarget", u.b("singleTarget") | weapons.size() <= 1 && !u.b("forceMultiTarget"));

        if(u.i("itemCapacity") < 0) u.set("itemCapacity", Math.max(Mathf.round((int)(hitSize * 4f), 10), 10));

        // assume slight range margin
        float margin = 4f;
        if(u.f("range") < 0){
            float range = Float.MAX_VALUE, maxRange = u.f("maxRange");
            for(Mut w : weapons){
                if(!w.b("useAttackRange")) continue;
                range = Math.min(range, weaponRange(bake, w) - margin);
                maxRange = Math.max(maxRange, weaponRange(bake, w) - margin);
            }
            u.set("range", range);
            u.set("maxRange", maxRange);
        }
        if(u.f("maxRange") < 0){
            float maxRange = Math.max(0f, u.f("range"));
            for(Mut w : weapons){
                if(!w.b("useAttackRange")) continue;
                maxRange = Math.max(maxRange, weaponRange(bake, w) - margin);
            }
            u.set("maxRange", maxRange);
        }

        if(u.f("fogRadius") < 0) u.set("fogRadius", Math.max(58f * 3f, hitSize * 2f) / 8f);

        boolean attackRange = false;
        for(Mut w : weapons) attackRange |= w.b("useAttackRange");
        if(!attackRange){
            if(u.f("range") < 0 || u.f("range") == Float.MAX_VALUE) u.set("range", u.f("mineRange"));
            if(u.f("maxRange") < 0 || u.f("maxRange") == Float.MAX_VALUE) u.set("maxRange", u.f("mineRange"));
        }

        if(u.f("mechStride") < 0) u.set("mechStride", 4f + (hitSize - 8f) / 2.1f);
        if(u.f("segmentSpacing") < 0) u.set("segmentSpacing", hitSize);
        if(u.f("aimDst") < 0){
            boolean fixed = false;
            for(Mut w : weapons) fixed |= !w.b("rotate");
            u.set("aimDst", fixed ? hitSize * 2f : hitSize / 2f);
        }
        if(u.f("stepShake") < 0){
            u.set("stepShake", (float)Mathf.round((hitSize - 11f) / 9f));
            u.set("mechStepParticles", hitSize > 15f);
        }

        if(u.f("engineSize") > 0){
            u.muts("engines").add(bake.create("unit-engine").set("x", 0f).set("y", -u.f("engineOffset")).set("radius", u.f("engineSize"))
                .set("rotation", -90f));
        }

        if(u.get("treadEffect") == null){
            u.set("treadEffect", bake.create("effect").set("lifetime", 50f).set("clip", 50f).set("renderer", "hook:unit-type.tread-effect")
                .set("layer", 20f)); // Layer.debris
        }

        if(u.f("mineBeamOffset") == Float.NEGATIVE_INFINITY) u.set("mineBeamOffset", hitSize / 2);

        for(Mut ability : u.muts("abilities")) bake.init(ability);

        // add mirrored weapon variants
        List<Object> mapped = new ArrayList<>();
        for(Mut w : weapons){
            if(w.f("recoilTime") < 0) w.set("recoilTime", w.f("reload"));
            mapped.add(w);

            // mirrors are copies with X values negated
            if(w.b("mirror")){
                Mut copy = w.copy();
                flip(bake, copy);
                mapped.add(copy);

                // since there are now two weapons, the reload and recoil time must be doubled
                w.set("recoilTime", w.f("recoilTime") * 2f);
                copy.set("recoilTime", copy.f("recoilTime") * 2f);
                w.set("reload", w.f("reload") * 2f);
                copy.set("reload", copy.f("reload") * 2f);

                w.set("otherSide", mapped.size() - 1);
                copy.set("otherSide", mapped.size() - 2);
            }
        }
        u.set("weapons", mapped);
        weapons = u.muts("weapons");
        for(Mut w : weapons) bake.init(w);

        boolean canHeal = false, canAttack = false;
        for(Mut w : weapons){
            canHeal |= heals(bullet(bake, w.s("bullet")));
            canAttack |= !w.b("noAttack");
        }
        u.set("canHeal", canHeal);
        u.set("canAttack", canAttack);

        // assign default commands
        List<Object> commands = u.list("commands");
        if(commands.isEmpty()){
            commands.add("move");
            if(u.b("allowedInPayloads")) commands.add("enterPayload");
            if(u.b("canBoost")){
                if(u.f("buildSpeed") > 0f) commands.addAll(List.of("rebuild", "assist"));
                if(u.i("mineTier") > 0) commands.add("mine");
            }
            // healing, mining and building is only supported for flying units
            if(u.b("flying")){
                if(canHeal) commands.add("repair");
                if(u.f("buildSpeed") > 0) commands.addAll(List.of("rebuild", "assist"));
                if(u.i("mineTier") > 0) commands.add("mine");
                if(example.has("payload")) commands.addAll(List.of("loadUnits", "loadBlocks", "unloadPayload", "loopPayload"));
            }
        }
        if(u.get("defaultCommand") == null && !commands.isEmpty()) u.set("defaultCommand", commands.getFirst());

        List<Object> stances = u.list("stances");
        if(stances.isEmpty()){
            if(canAttack){
                stances.addAll(List.of("stop", "holdfire", "pursuetarget", "patrol"));
                if(!u.b("flying")) stances.add("ram");
                if(u.b("canBoost")) stances.add("boost");
            }else{
                stances.addAll(List.of("stop", "patrol"));
            }
        }

        estimateDps(bake, u);
    }

    // UnitType.java:891
    private static void initPathType(Mut u){
        boolean naval = u.b("naval"), legs = u.b("allowLegStep"), hovering = u.b("hovering");
        if(u.i("flowfieldPathType") == -1){
            // Pathfinder.costNaval, costLegs, costNone, costHover, costGround
            u.set("flowfieldPathType", naval ? 2 : legs ? 1 : u.b("flying") ? 4 : hovering ? 5 : 0);
        }
        if(u.get("pathCost") == null){
            u.set("pathCost", naval ? "cost:costNaval" : legs ? "cost:costLegs" : hovering ? "cost:costHover" : "cost:costGround");
        }
        u.set("pathCostId", Math.max(costTypes.indexOf(u.s("pathCost")), 0));
    }

    /** {@code Weapon.range()}. */
    private static float weaponRange(Bake bake, Mut w){
        return bullet(bake, w.s("bullet")).f("range");
    }

    // Weapon.java:551
    private static void flip(Bake bake, Mut w){
        w.set("x", w.f("x") * -1);
        w.set("shootX", w.f("shootX") * -1);
        w.set("baseRotation", w.f("baseRotation") * -1f);
        w.set("flipSprite", !w.b("flipSprite"));
        Mut shoot = w.m("shoot").copy();
        flipShoot(shoot);
        w.set("shoot", shoot);
    }

    /** {@code ShootPattern.flip()} per pattern kind. */
    private static void flipShoot(Mut s){
        if(s.is("shoot-alternate")){
            s.set("mirror", !s.b("mirror"));
        }else if(s.is("shoot-barrel")){
            List<Object> barrels = s.list("barrels");
            for(int i = 0; i < barrels.size(); i += 3){
                barrels.set(i, (Float)barrels.get(i) * -1);
                barrels.set(i + 2, (Float)barrels.get(i + 2) * -1);
            }
        }else if(s.is("shoot-multi")){
            Mut source = s.m("source").copy();
            flipShoot(source);
            s.set("source", source);
            List<Object> dest = s.list("dest");
            for(int i = 0; i < dest.size(); i++){
                Mut d = ((Mut)dest.get(i)).copy();
                flipShoot(d);
                dest.set(i, d);
            }
        }
    }

    // UnitType.java:1170
    static float estimateDps(Bake bake, Mut u){
        u = u.base;
        if(u.f("dpsEstimate") < 0){
            float dps = 0f;
            boolean suicide = false;
            for(Mut w : u.muts("weapons")){
                Mut bullet = bullet(bake, w.s("bullet"));
                // Weapon.dps(); RepairBeamWeapon's is 0
                if(!w.is("repair-beam-weapon")) dps += estimateDPS(bake, bullet) / w.f("reload") * w.m("shoot").i("shots") * 60f;
                suicide |= bullet.b("killShooter");
            }
            // suicide enemy: scale down DPS to be insignificant
            if(suicide) dps /= 15f;
            u.set("dpsEstimate", dps);
        }
        return u.f("dpsEstimate");
    }
}
