package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;
import java.util.function.*;

/**
 * The serializers of the foundation-era objects that other work packages own: the {@code Entity}, {@code Building}
 * and {@code Unit} bases and the building modules (WAVES.md "Checkpoints"). Field names are the Java names, so the
 * coverage test can match them. When an owner adds a field, the coverage test fails until it is stored here.
 */
final class Foundation{
    /** {@code Entity}: the world is the restoring one, set when the reader creates the object. */
    static final Plan.Layer<Entity> ENTITY = new Plan.Layer<>(){
        @Override
        public void write(Entity entity, CheckpointOut out){
            out.implicit("world").i("id", entity.id()).bool("added", entity.isAdded());
            out.attachments("attachments", entity);
        }

        @Override
        public void read(Entity entity, CheckpointIn in){
            in.implicit("world");
            entity.id(in.i("id"));
            Access.ENTITY_ADDED.set(entity, in.bool("added"));
            in.attachments("attachments", entity);
        }
    };

    /** {@code Building}: the block comes with the object's type, since it decides the kind to instantiate. */
    static final Plan.Layer<Building> BUILDING = new Plan.Layer<>(){
        @Override
        public void write(Building building, CheckpointOut out){
            out.implicit("block").i("tile", building.tile()).i("index", building.index())
                .i("updateIndex", Building.updateSlot().get(building)).bool("sleeping", building.sleeping())
                .f("sleepTime", (float)Access.BUILDING_SLEEP_TIME.get(building));
            // proximity order is history (BuildingComp.updateProximity appends): stored, never rebuilt
            out.refs("proximity", (Building[])Access.BUILDING_PROXIMITY.get(building), building.proximitySize())
                .i("proximitySize", building.proximitySize());
            out.i("team", building.team).i("rotation", building.rotation).f("health", building.health)
                .f("maxHealth", building.maxHealth).bool("dead", building.dead);
            out.f("efficiency", building.efficiency).f("optionalEfficiency", building.optionalEfficiency)
                .f("potentialEfficiency", building.potentialEfficiency).f("timeScale", building.timeScale)
                .f("timeScaleDuration", building.timeScaleDuration).bool("enabled", building.enabled);
            out.obj("items", building.items).obj("liquids", building.liquids).obj("power", building.power);
            out.bool("shouldConsumePower", building.shouldConsumePower).ref("lastDisabler", building.lastDisabler)
                .l("visibleFlags", building.visibleFlags).i("cdump", building.cdump).f("dumpAccum", building.dumpAccum)
                .f("lastDamageTime", building.lastDamageTime).f("healSuppressionTime", building.healSuppressionTime)
                .f("lastHealTime", building.lastHealTime).bool("wasDamaged", building.wasDamaged).floats("timer", building.timer);
            // a payload's position off its tile (WP65), NaN on the map
            out.f("payloadX", (float)Access.BUILDING_PAYLOAD_X.get(building)).f("payloadY", (float)Access.BUILDING_PAYLOAD_Y.get(building));
        }

        @Override
        public void read(Building building, CheckpointIn in){
            in.implicit("block");
            Access.BUILDING_TILE.set(building, in.i("tile"));
            Access.BUILDING_INDEX.set(building, in.i("index"));
            Building.updateSlot().set(building, in.i("updateIndex"));
            Access.BUILDING_SLEEPING.set(building, in.bool("sleeping"));
            Access.BUILDING_SLEEP_TIME.set(building, in.f("sleepTime"));
            Building[] proximity = in.refs("proximity", Building.class);
            int proximitySize = in.i("proximitySize");
            if(proximity == null || proximity.length != proximitySize) throw new CheckpointException(building + "'s proximity is inconsistent");
            Access.BUILDING_PROXIMITY.set(building, proximitySize == 0 ? (Building[])Access.BUILDING_PROXIMITY.get(building) : proximity);
            Access.BUILDING_PROXIMITY_SIZE.set(building, proximitySize);
            building.team = in.i("team");
            building.rotation = in.i("rotation");
            building.health = in.f("health");
            building.maxHealth = in.f("maxHealth");
            building.dead = in.bool("dead");
            building.efficiency = in.f("efficiency");
            building.optionalEfficiency = in.f("optionalEfficiency");
            building.potentialEfficiency = in.f("potentialEfficiency");
            building.timeScale = in.f("timeScale");
            building.timeScaleDuration = in.f("timeScaleDuration");
            building.enabled = in.bool("enabled");
            building.items = in.obj("items", ItemModule.class);
            building.liquids = in.obj("liquids", LiquidModule.class);
            building.power = in.obj("power", PowerModule.class);
            building.shouldConsumePower = in.bool("shouldConsumePower");
            building.lastDisabler = in.ref("lastDisabler", Building.class);
            building.visibleFlags = in.l("visibleFlags");
            building.cdump = in.i("cdump");
            building.dumpAccum = in.f("dumpAccum");
            building.lastDamageTime = in.f("lastDamageTime");
            building.healSuppressionTime = in.f("healSuppressionTime");
            building.lastHealTime = in.f("lastHealTime");
            building.wasDamaged = in.bool("wasDamaged");
            building.timer = in.floats("timer");
            Access.BUILDING_PAYLOAD_X.set(building, in.f("payloadX"));
            Access.BUILDING_PAYLOAD_Y.set(building, in.f("payloadY"));
        }
    };

    /**
     * {@code Unit}, in its declaration order: the type comes with the object's type, like a building's block. The
     * type's derived stats are rebuilt last, by {@code restoreType()}, once the controller, mounts and abilities are
     * read: {@code setType} would make a default controller, which draws from the random, and remake mounts and
     * abilities whose count differs from the type's.
     */
    static final Plan.Layer<Unit> UNIT = new Plan.Layer<>(){
        @Override
        public void write(Unit unit, CheckpointOut out){
            out.implicit("type").i("updateIndex", Unit.updateSlot().get(unit));
            out.f("x", unit.x).f("y", unit.y).f("rotation", unit.rotation).obj("vel", unit.vel).f("drag", unit.drag);
            out.f("lastX", unit.lastX).f("lastY", unit.lastY).f("deltaX", unit.deltaX).f("deltaY", unit.deltaY)
                .bool("hasPhysicsRef", unit.hasPhysicsRef);
            out.i("team", unit.team).f("health", unit.health).bool("dead", unit.dead).f("shield", unit.shield);
            out.f("elevation", unit.elevation).d("flag", unit.flag).bool("spawnedByCore", unit.spawnedByCore)
                .obj("controller", unit.controller).list("abilities", Arrays.asList(unit.abilities)).i("dockedType", unit.dockedType)
                .bool("hasTarget", unit.hasTarget).bool("wasPlayer", unit.wasPlayer).bool("wasHealed", unit.wasHealed)
                .bool("wasFlying", unit.wasFlying).f("resupplyTime", unit.resupplyTime).f("drownTime", unit.drownTime)
                .f("splashTimer", unit.splashTimer)
                .i("lastDrownFloor", unit.lastDrownFloor).i("lastFogPos", unit.lastFogPos);
            out.list("mounts", Arrays.asList(unit.mounts)).bool("isRotate", unit.isRotate).bool("isShooting", unit.isShooting)
                .f("aimX", unit.aimX).f("aimY", unit.aimY);
            out.obj("statuses", unit.statuses).i("stackItem", unit.stackItem).i("stackAmount", unit.stackAmount);
            out.obj("plans", unit.plans).bool("updateBuilding", unit.updateBuilding).f("buildCounter", unit.buildCounter)
                .i("mineTile", unit.mineTile).f("mineTimer", unit.mineTimer);
            out.obj("shape", unit.shape).obj("payloads", unit.payloads).ref("tether", unit.tether).ref("blockTile", unit.blockTile).f("time", unit.time)
                .f("lifetime", unit.lifetime);
        }

        @Override
        public void read(Unit unit, CheckpointIn in){
            in.implicit("type");
            Unit.updateSlot().set(unit, in.i("updateIndex"));
            unit.x = in.f("x");
            unit.y = in.f("y");
            unit.rotation = in.f("rotation");
            unit.vel = in.obj("vel", Vec2.class);
            unit.drag = in.f("drag");
            unit.lastX = in.f("lastX");
            unit.lastY = in.f("lastY");
            unit.deltaX = in.f("deltaX");
            unit.deltaY = in.f("deltaY");
            unit.hasPhysicsRef = in.bool("hasPhysicsRef");
            unit.team = in.i("team");
            unit.health = in.f("health");
            unit.dead = in.bool("dead");
            unit.shield = in.f("shield");
            unit.elevation = in.f("elevation");
            unit.flag = in.d("flag");
            unit.spawnedByCore = in.bool("spawnedByCore");
            unit.controller = in.obj("controller", UnitController.class);
            unit.abilities = array(in.list("abilities", UnitAbility.class), UnitAbility[]::new, unit, "abilities");
            unit.dockedType = in.i("dockedType");
            unit.hasTarget = in.bool("hasTarget");
            unit.wasPlayer = in.bool("wasPlayer");
            unit.wasHealed = in.bool("wasHealed");
            unit.wasFlying = in.bool("wasFlying");
            unit.resupplyTime = in.f("resupplyTime");
            unit.drownTime = in.f("drownTime");
            unit.splashTimer = in.f("splashTimer");
            unit.lastDrownFloor = in.i("lastDrownFloor");
            unit.lastFogPos = in.i("lastFogPos");
            unit.mounts = array(in.list("mounts", WeaponMount.class), WeaponMount[]::new, unit, "mounts");
            unit.isRotate = in.bool("isRotate");
            unit.isShooting = in.bool("isShooting");
            unit.aimX = in.f("aimX");
            unit.aimY = in.f("aimY");
            unit.statuses = in.obj("statuses", Statuses.class);
            unit.stackItem = in.i("stackItem");
            unit.stackAmount = in.i("stackAmount");
            unit.plans = in.obj("plans", PlanQueue.class);
            unit.updateBuilding = in.bool("updateBuilding");
            unit.buildCounter = in.f("buildCounter");
            unit.mineTile = in.i("mineTile");
            unit.mineTimer = in.f("mineTimer");
            unit.shape = in.obj("shape", MoveShape.class);
            unit.payloads = in.obj("payloads", PayloadSlot.class);
            unit.tether = in.ref("tether", Building.class);
            unit.blockTile = in.ref("blockTile", Building.class);
            unit.time = in.f("time");
            unit.lifetime = in.f("lifetime");
            if(unit.vel == null || unit.statuses == null || unit.plans == null) throw new CheckpointException(unit + " is missing a part");
            if(unit.type() != null) unit.restoreType();
        }

        private static <T> T[] array(List<T> stored, IntFunction<T[]> make, Unit unit, String name){
            if(stored == null || stored.contains(null)) throw new CheckpointException(unit + "'s " + name + " are missing");
            return stored.toArray(make.apply(0));
        }
    };

    /** {@code ItemModule}: its array is final and sized by the content, so it is read whole. */
    static final class Items extends Plan{
        Items(){
            super(ItemModule.class);
        }

        /** An empty module, for the coverage check; reading builds one of the stored size. */
        @Override
        Object create(){
            return new ItemModule(0);
        }

        @Override
        boolean owned(){
            return true;
        }

        @Override
        void write(Object value, CheckpointOut out){
            var items = (ItemModule)value;
            out.ints("items", items.items).i("total", items.total).i("takeRotation", items.takeRotation);
        }

        @Override
        Object read(CheckpointIn in){
            int[] counts = in.ints("items");
            var items = new ItemModule(counts.length);
            System.arraycopy(counts, 0, items.items, 0, counts.length);
            items.total = in.i("total");
            items.takeRotation = in.i("takeRotation");
            return items;
        }

        @Override
        void fill(Object value, CheckpointIn in){
            throw new UnsupportedOperationException();
        }
    }

    static final class Liquids extends Plan{
        Liquids(){
            super(LiquidModule.class);
        }

        /** An empty module, for the coverage check; reading builds one of the stored size. */
        @Override
        Object create(){
            return new LiquidModule(0);
        }

        @Override
        boolean owned(){
            return true;
        }

        @Override
        void write(Object value, CheckpointOut out){
            var liquids = (LiquidModule)value;
            out.floats("liquids", liquids.liquids).i("current", liquids.current);
        }

        @Override
        Object read(CheckpointIn in){
            float[] amounts = in.floats("liquids");
            var liquids = new LiquidModule(amounts.length);
            System.arraycopy(amounts, 0, liquids.liquids, 0, amounts.length);
            liquids.current = in.i("current");
            return liquids;
        }

        @Override
        void fill(Object value, CheckpointIn in){
            throw new UnsupportedOperationException();
        }
    }

    static final class Power extends Plan{
        Power(){
            super(PowerModule.class);
        }

        /** An empty module, for the coverage check; reading builds one of the stored size. */
        @Override
        Object create(){
            return new PowerModule();
        }

        @Override
        boolean owned(){
            return true;
        }

        @Override
        void write(Object value, CheckpointOut out){
            var power = (PowerModule)value;
            out.f("status", power.status).bool("init", power.init).intSeq("links", power.links);
            out.ref("graph", power.graph);
        }

        @Override
        Object read(CheckpointIn in){
            var power = new PowerModule();
            power.status = in.f("status");
            power.init = in.bool("init");
            in.intSeq("links", power.links);
            power.graph = in.ref("graph", com.xpdustry.shard.sim.blocks.power.PowerGraph.class);
            return power;
        }

        @Override
        void fill(Object value, CheckpointIn in){
            throw new UnsupportedOperationException();
        }
    }

    private Foundation(){}
}
