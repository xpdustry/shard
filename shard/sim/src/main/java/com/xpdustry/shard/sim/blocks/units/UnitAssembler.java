package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.logic.lang.LAccess;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code UnitAssembler.UnitAssemblerBuild} (UnitAssembler.java:319): Erekir's tank, ship and mech assemblers.
 * It builds the unit of its plan in a square area in front of it ({@code areaSize} tiles, centred {@link #unitSpawn}),
 * out of payloads: units and blocks fed into it by payload conveyors or through its modules, each thrown into the area
 * ({@link #yeetPayload}) and counted in {@link #blocks}. Its plan is the one of its module tier ({@link #currentTier},
 * from the {@link UnitAssemblerModule}s linked to it, without gaps).
 *
 * <p>It makes {@code dronesCreated} drones of its own ({@code droneType}, tethered, running {@link AssemblerAi}), one per
 * {@code droneConstructTime} at its power, and places them at the area's four corners. It builds only while the area is
 * clear ({@link #wasOccupied}: no solid tile under a ground output, no unit in its way), its payloads, items and power
 * are there and the team's cap allows the unit; at the rate of the drones in position. A finished unit
 * ({@code assemblerUnitSpawned}) goes onto a payload block it stands on, or onto the ground, sent to the rally point
 * ({@link #commandPos}).
 *
 * <p>Vanilla quirks, kept: {@code powerWarmup} lerps from the power status, not from itself (UnitAssembler.java:474), and
 * the area's tile check reads {@code overlapsTile}'s radius from the last physics step (a radius of one tile, which is
 * {@link Collisions#overlapsTile}'s; see the class comment there). Its {@link #blocks} save in first-added order, where
 * vanilla's follow identity hashes ({@link PayloadSeq}).
 *
 * <p>Not ported: the drawing ({@code invalidWarmup}, {@code sameTypeWarmup}, {@code powerWarmup},
 * {@code totalDroneProgress} are kept, since a client draws them, but nothing reads them), the effects, which a server
 * never runs, and {@code whenSyncedUnits}, which only a client fills.
 */
@Kind("unit-assembler")
public final class UnitAssembler extends PayloadBuild<Payload> implements Checkpointable{
    /** {@code readUnits}: drone ids a load read, found on the first update. */
    public IntSeq readUnits = new IntSeq();
    /** {@code commandPos}: where new units are sent, or null. */
    public Vec2 commandPos;
    /** {@code units}: the drones. */
    public List<Unit> units = new ArrayList<>();
    /** {@code modules}: the linked modules, by tier. */
    public List<UnitAssemblerModule> modules = new ArrayList<>();
    /** {@code blocks}: the payloads received for the current unit. */
    public PayloadSeq blocks = new PayloadSeq();
    public float progress, warmup, droneWarmup, powerWarmup, sameTypeWarmup;
    public float invalidWarmup;
    public int currentTier;
    public int lastTier = -2;
    public boolean wasOccupied;
    public float droneProgress, totalDroneProgress;

    @Derived("the block's plans, area and drones, read on first use")
    private Params params;

    /** One {@code AssemblerUnitPlan}: the unit, its payloads, items and liquids, and its time. */
    record Plan(UnitDef unit, List<PayloadStock.Stack> requirements, Stacks itemReq, Stacks liquidReq, float time){
    }

    record Params(Plan[] plans, int areaSize, UnitDef droneType, int dronesCreated, float droneConstructTime, int[] capacities){
        static Params of(BlockDef block, ContentEpoch content){
            if(!block.baked()) return new Params(new Plan[0], 11, content.unit("assembly-drone"), 4, 60f * 4f, new int[content.items()]);
            var f = block.fields();
            List<Struct> list = f.structs("plans");
            var plans = new Plan[list.size()];
            for(int i = 0; i < plans.length; i++){
                Struct plan = list.get(i);
                var requirements = new ArrayList<PayloadStock.Stack>();
                for(Object entry : plan.list("requirements")){
                    var stack = ((Map<?, ?>)entry).entrySet().iterator().next();
                    String name = (String)stack.getKey();
                    int amount = ((Number)stack.getValue()).intValue();
                    ContentDef unit = content.find(ContentType.UNIT, name);
                    requirements.add(unit != null ? new PayloadStock.Stack(ContentType.UNIT, unit.id(), amount)
                        : new PayloadStock.Stack(ContentType.BLOCK, content.block(name).id(), amount));
                }
                plans[i] = new Plan(content.unit(plan.s("unit")), List.copyOf(requirements), stacks(plan, "itemReq", ContentType.ITEM, content),
                    stacks(plan, "liquidReq", ContentType.LIQUID, content), plan.f("time"));
            }
            return new Params(plans, f.i("areaSize"), content.unit(f.s("droneType")), f.i("dronesCreated"), f.f("droneConstructTime"),
                UnitBuild.readCapacities(block, content.items()));
        }

        /** A plan's item or liquid stacks, or null for none. */
        private static Stacks stacks(Struct plan, String field, ContentType type, ContentEpoch content){
            if(!plan.has(field) || plan.get(field) == null) return null;
            Map<String, Object> map = plan.map(field);
            int[] ids = new int[map.size()];
            float[] amounts = new float[map.size()];
            int at = 0;
            for(var entry : map.entrySet()){
                ids[at] = content.id(type, entry.getKey());
                amounts[at++] = ((Number)entry.getValue()).floatValue();
            }
            return Stacks.of(ids, amounts);
        }
    }

    Params params(){
        if(params == null) params = Params.of(block(), world().content());
        return params;
    }

    // region geometry

    /** {@code getRect(rect, x, y, rotation)} of {@code block}: its area for a centre at (x, y), as {x, y, width, height}. */
    static float[] rect(BlockDef block, float x, float y, int rotation){
        int areaSize = block.baked() ? block.fields().i("areaSize") : 11;
        float side = areaSize * Footprint.TILE_SIZE;
        float[] rect = {x - side / 2f, y - side / 2f, side, side};
        float len = Footprint.TILE_SIZE * (areaSize + block.size()) / 2f;
        rect[0] += Geometry.d4x(rotation) * len;
        rect[1] += Geometry.d4y(rotation) * len;
        return rect;
    }

    /** {@code block.offset}: half a tile for even sizes, so a centre sits between tiles. */
    static float offset(BlockDef block){
        return ((block.size() + 1) % 2) * Footprint.TILE_SIZE / 2f;
    }

    /** {@code Tile.relativeTo(x, y, cx, cy)} on world coordinates (Tile.java:103): the side (cx, cy) is on, or -1. */
    static int relativeTo(float x, float y, float cx, float cy){
        if(Math.abs(x - cx) > Math.abs(y - cy)){
            if(x <= cx - 1) return 0;
            if(x >= cx + 1) return 2;
        }else{
            if(y <= cy - 1) return 1;
            if(y >= cy + 1) return 3;
        }
        return -1;
    }

    /** {@code Rect.overlaps}. */
    static boolean overlaps(float[] a, float[] b){
        return a[0] < b[0] + b[2] && a[0] + a[2] > b[0] && a[1] < b[1] + b[3] && a[1] + a[3] > b[1];
    }

    /**
     * {@code canPlaceOn(tile, team, rotation)} (UnitAssembler.java:101): no other assembler's area, built or under
     * construction, overlaps this one's, grown by 0.1 so edges can't touch, unless it is the one being replaced.
     */
    public static boolean canPlaceOn(World world, BlockDef block, int x, int y, int team, int rotation){
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        float offset = offset(block);
        float[] area = rect(block, x * Footprint.TILE_SIZE + offset, y * Footprint.TILE_SIZE + offset, rotation);
        // grow(0.1f)
        area[0] -= 0.1f / 2f;
        area[1] -= 0.1f / 2f;
        area[2] += 0.1f;
        area[3] += 0.1f;
        Building here = tiles.inBounds(x, y) ? buildings.at(tiles.pos(x, y)) : null;
        for(Building b : buildings.flagged(team, BlockFlag.unitAssembler)){
            if(b != here && b.block().isA(KindKey.of("unit-assembler")) && overlaps(rect(b.block(), b.x(), b.y(), b.rotation), area)) return false;
        }
        int construct = world.content().id(ContentType.BLOCK, "build" + block.size());
        if(construct < 0) return true;
        for(Building b : buildings.teamBuildings(team, world.content().block(construct))){
            if(b == here || !(b instanceof ConstructBuild site) || site.current < 0) continue;
            BlockDef current = world.content().block(site.current);
            if(current.isA(KindKey.of("unit-assembler")) && overlaps(rect(current, b.x(), b.y(), b.rotation), area)) return false;
        }
        return true;
    }

    /** {@code getUnitSpawn()}: the centre of the area. Vanilla returns {@code Tmp.v4}; this is a new vector. */
    public Vec2 unitSpawn(){
        float len = Footprint.TILE_SIZE * (params().areaSize + block().size()) / 2f;
        return new Vec2(x() + Geometry.d4x(rotation) * len, y() + Geometry.d4y(rotation) * len);
    }

    /**
     * {@code moduleFits(other, ox, oy, rotation)}: a module of block {@code other} centred at (ox, oy) facing
     * {@code rotation} faces the area's centre and touches the area's edge with its front.
     */
    public boolean moduleFits(BlockDef other, float ox, float oy, int rotation){
        float dx = ox + Geometry.d4x(rotation) * (other.size() / 2f + 0.5f) * Footprint.TILE_SIZE,
            dy = oy + Geometry.d4y(rotation) * (other.size() / 2f + 0.5f) * Footprint.TILE_SIZE;
        Vec2 spawn = unitSpawn();
        if(relativeTo(ox, oy, spawn.x, spawn.y) != rotation) return false;
        float dst = Math.max(Math.abs(dx - spawn.x), Math.abs(dy - spawn.y));
        return Mathf.equal(dst, Footprint.TILE_SIZE * params().areaSize / 2f - Footprint.TILE_SIZE / 2f);
    }

    // endregion

    // region modules

    /** {@code updateModules(build)}: a module linked. */
    public void updateModules(UnitAssemblerModule build){
        if(!modules.contains(build)) modules.add(build);
        checkTier();
    }

    /** {@code removeModule(build)}. */
    public void removeModule(UnitAssemblerModule build){
        modules.remove(build);
        checkTier();
    }

    /** {@code checkTier()}: the highest tier reached from 0 in steps of one, over the modules sorted by tier (stable). */
    public void checkTier(){
        modules.sort(Comparator.comparingInt(UnitAssemblerModule::tier));
        int max = 0;
        for(UnitAssemblerModule mod : modules){
            if(mod.tier() == max || mod.tier() == max + 1){
                max = mod.tier();
            }else{
                // tier gap
                break;
            }
        }
        currentTier = max;
    }

    /** {@code plan()}: the plan of the current tier, clamped to the last. */
    public Plan plan(){
        Plan[] plans = params().plans;
        return plans[Math.min(currentTier, plans.length - 1)];
    }

    /** {@code unit()}. */
    public UnitDef unit(){
        return plan().unit;
    }

    // endregion

    @Override
    public boolean shouldConsume(){
        // liquid is only consumed when building is being done
        Consumers consumers = info().consumers;
        ConsumePayloads payloads = consumers.find(ConsumePayloads.class);
        ConsumeItems items = consumers.find(ConsumeItems.class);
        return enabled && !wasOccupied && Units.canCreate(world(), team, plan().unit) && (payloads == null || payloads.efficiency(this) > 0)
            && (items == null || items.efficiency(this) > 0) && activateUnitFactories();
    }

    @Override
    public void updateTile(){
        World world = world();
        Params p = params();
        if(!readUnits.isEmpty()){
            units.clear();
            EntityGroups groups = EntityGroups.of(world);
            for(int i = 0; i < readUnits.size; i++){
                if(groups != null && groups.byId(readUnits.get(i)) instanceof Unit unit) units.add(unit);
            }
            readUnits.clear();
        }

        if(lastTier != currentTier){
            if(lastTier >= 0f){
                progress = 0f;
            }
            lastTier = lastTier == -2 ? -1 : currentTier;
        }

        units.removeIf(u -> !u.isAdded() || u.dead || !(u.controller instanceof AssemblerAi));

        // unsupported
        if(!allowUpdate()){
            progress = 0f;
            for(Unit unit : units) unit.kill();
            units.clear();
        }

        float powerStatus = !enabled ? 0f : power == null ? 1f : power.status;
        // vanilla quirk: lerps from the power status, not from powerWarmup
        powerWarmup = Mathf.lerpDelta(powerStatus, powerStatus > 0.0001f ? 1f : 0f, 0.1f);
        droneWarmup = Mathf.lerpDelta(droneWarmup, units.size() < p.dronesCreated ? powerStatus : 0f, 0.1f);
        totalDroneProgress += droneWarmup * delta();

        if(units.size() < p.dronesCreated && enabled && (droneProgress += delta() * unitBuildSpeed() * powerStatus / p.droneConstructTime) >= 1f){
            Unit unit = Unit.create(world, p.droneType, team);
            // a unit without AssemblerAI (a bad patch) spawns nothing
            if(unit.controller instanceof AssemblerAi){
                if(unit.params().tethered()) unit.tether = this;
                unit.set(x(), y());
                unit.rotation = 90f;
                unit.add();
                units.add(unit);
                // Call.assemblerDroneSpawned runs droneSpawned(id) on the server too: Fx.spawn, then the progress restarts
                droneProgress = 0f;
                world.publish(new AssemblerEvent.DroneSpawned(world.ticks(), tile(), unit.id()));
            }else{
                droneProgress = 0f;
            }
        }

        if(units.size() >= p.dronesCreated){
            droneProgress = 0f;
        }

        Vec2 spawn = unitSpawn();

        if(moveInPayload() && !wasOccupied){
            yeetPayload(payload);
            payload = null;
        }

        // arrange units around perimeter
        for(int i = 0; i < units.size(); i++){
            Unit unit = units.get(i);
            var ai = (AssemblerAi)unit.controller;
            ai.targetPos.trns(i * 90f + 45f, p.areaSize / 2f * Mathf.sqrt2 * Footprint.TILE_SIZE).add(spawn);
            ai.targetAngle = i * 90f + 45f + 180f;
        }

        wasOccupied = checkSolid(spawn, false);
        boolean visualOccupied = checkSolid(spawn, true);
        int inPosition = 0;
        for(Unit unit : units) if(((AssemblerAi)unit.controller).inPosition()) inPosition++;
        float eff = inPosition / (float)p.dronesCreated;

        sameTypeWarmup = Mathf.lerpDelta(sameTypeWarmup, wasOccupied && !visualOccupied ? 0f : 1f, 0.1f);
        invalidWarmup = Mathf.lerpDelta(invalidWarmup, visualOccupied ? 1f : 0f, 0.1f);

        Plan plan = plan();

        // check if all requirements are met
        if(!wasOccupied && efficiency > 0 && Units.canCreate(world, team, plan.unit)){
            warmup = Mathf.lerpDelta(warmup, efficiency, 0.1f);

            if((progress += edelta() * unitBuildSpeed() * eff / plan.time) >= 1f){
                // Call.assemblerUnitSpawned(tile): the handler runs first, then the packet goes out
                spawned();
                world.publish(new AssemblerEvent.UnitSpawned(world.ticks(), tile()));
            }
        }else{
            warmup = Mathf.lerpDelta(warmup, 0f, 0.1f);
        }
    }

    /** {@code spawned()}: the {@code assemblerUnitSpawned} handler, which makes the unit and takes what it cost. */
    public void spawned(){
        World world = world();
        Plan plan = plan();
        Vec2 spawn = unitSpawn();
        consume();

        Unit unit = Unit.create(world, plan.unit, team);
        if(unit.controller instanceof CommandAi ai && commandPos != null){
            ai.commandPosition(commandPos);
        }
        Rand rand = world.rand();
        float sx = spawn.x + Mathf.range(rand, 0.001f);
        unit.set(sx, spawn.y + Mathf.range(rand, 0.001f));
        unit.rotation = rotdeg();
        int on = unit.tileOn();
        Building targetBuild = on == -1 ? null : world.buildings().at(on);
        // 'source' is the target build instead of this building: some blocks only accept things from certain angles
        var payload = new Payload.UnitPayload(unit);
        if(targetBuild != null && targetBuild.team == team && targetBuild.acceptPayload(targetBuild, payload)){
            targetBuild.handlePayload(targetBuild, payload);
        }else{
            unit.add();
            // Units.notifyUnitSpawn
            world.publish(new CombatEvent.UnitSpawned(unit.id()));
        }

        // createSound.at(spawn, 1f + Mathf.range(0.06f), volume)
        Mathf.range(rand, 0.06f);

        progress = 0f;
        blocks.clear();

        // Events.fire(new UnitCreateEvent(unit, this))
        ObjectiveState.unitCreated(world, team);
    }

    /**
     * {@code checkSolid(v, same)}: whether the area's centre is blocked for the plan's unit: by a solid tile under a
     * ground unit's box, or by a unit (not one from a core) in the way of its kind, within 1.4 hit sizes. With
     * {@code same}, units of the plan's own type don't count.
     */
    public boolean checkSolid(Vec2 v, boolean same){
        World world = world();
        UnitDef output = unit();
        UnitParams out = world.unitTable().params(output);
        float hitSize = out.hitSize();
        float hsize = hitSize * 1.4f;
        if(!out.flying() && Collisions.overlapsTile(v.x - hitSize / 2f, v.y - hitSize / 2f, hitSize, hitSize, TileSolidity.of(world), Solidity.SOLID)) return true;
        SpatialIndex index = SpatialIndex.of(world);
        return index != null && index.anyEntities(v.x - hsize / 2f, v.y - hsize / 2f, hsize, hsize, u -> (!same || u.type() != output) && !u.spawnedByCore
            && ((u.params().allowLegStep() && out.allowLegStep()) || (out.flying() && u.isFlying()) || (!out.flying() && u.isGrounded())));
    }

    /** {@code ready()}: requirements met. */
    public boolean ready(){
        return efficiency > 0 && !wasOccupied;
    }

    /** {@code yeetPayload(payload)}: counted, and thrown into the area (a sound's pitch draws). */
    public void yeetPayload(Payload payload){
        blocks.add(payload.content().type(), payload.content().id(), 1);
        // Sounds.shootPayload.at(x, y, 1f + Mathf.range(0.1f), 1f)
        Mathf.range(world().rand(), 0.1f);
    }

    @Override
    public BlockStatus status(double stateTick){
        if(!activateUnitFactories()) return BlockStatus.inactiveUnitFactory;
        return super.status(stateTick);
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress;
        return super.sense(sensor);
    }

    @Override
    public boolean acceptUnitPayload(Unit unit){
        for(PayloadStock.Stack b : plan().requirements){
            if(b.type() == ContentType.UNIT && b.id() == unit.type().id() && blocks.get(ContentType.UNIT, b.id()) < Mathf.round(b.amount() * unitCost())) return true;
        }
        return false;
    }

    @Override
    public PayloadStock payloads(){
        return blocks;
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        if(!(this.payload == null || source instanceof UnitAssemblerModule)) return false;
        ContentDef content = payload.content();
        int already = source instanceof UnitAssemblerModule && this.payload != null && this.payload.contentEquals(payload) ? 1 : 0;
        for(PayloadStock.Stack b : plan().requirements){
            if(b.type() == content.type() && b.id() == content.id() && blocks.get(b.type(), b.id()) < Mathf.round(b.amount() * unitCost()) - already) return true;
        }
        return false;
    }

    @Override
    public int getMaximumAccepted(int item){
        int[] capacities = params().capacities;
        return Mathf.round((item < capacities.length ? capacities[item] : 0) * unitCost());
    }

    @Override
    public boolean acceptItem(Building source, int item){
        Stacks itemReq = plan().itemReq;
        if(itemReq == null || items.get(item) >= getMaximumAccepted(item)) return false;
        for(int i = 0; i < itemReq.size(); i++) if(itemReq.id(i) == item) return true;
        return false;
    }

    /** {@code ConsumePayloadDynamic}'s {@code hook:unit-assembler.payloads}: the plan's payloads. */
    @Override
    public List<PayloadStock.Stack> consumedPayloads(){
        return plan().requirements;
    }

    /** {@code ConsumeItemDynamic}'s {@code hook:unit-assembler.items}: the plan's items, or none. */
    @Override
    public Stacks consumedItems(){
        Stacks req = plan().itemReq;
        return req != null ? req : Stacks.EMPTY;
    }

    /** {@code ConsumeLiquidsDynamic}'s {@code hook:unit-assembler.liquids}: the plan's liquids, or none. */
    @Override
    public Stacks consumedLiquids(){
        Stacks req = plan().liquidReq;
        return req != null ? req : Stacks.EMPTY;
    }

    /** {@code initCapacities}' multiplier on every consumer ({@code hook:unit-assembler.multiplier}): the team's unit cost. */
    @Override
    public float consumeMultiplier(Consume consume){
        return unitCost();
    }

    @Override
    public Vec2 getCommandPosition(){
        return commandPos;
    }

    /** {@code onCommand(target)}: the rally point. Vanilla keeps the caller's vector; Shard copies it. */
    @Override
    public void onCommand(Vec2 target){
        commandPos = new Vec2(target);
    }

    // shared rules

    /** {@code team.activateUnitFactories()}: the game is past the team's unit factory activation delay. */
    private boolean activateUnitFactories(){
        LogicPart logic = LogicPart.of(world());
        double tick = logic == null ? world().ticks() : logic.tick();
        return tick >= rules().unitActivationDelay(team);
    }

    private float unitCost(){
        return rules().unitCost(team);
    }

    private float unitBuildSpeed(){
        return rules().unitBuildSpeed(team);
    }

    // state

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitAssembler)into;
        out.readUnits = readUnits.copy();
        out.commandPos = commandPos == null ? null : new Vec2(commandPos);
        out.units = new ArrayList<>(units.size());
        for(Unit unit : units) out.units.add(context.map(unit));
        out.modules = new ArrayList<>(modules.size());
        for(UnitAssemblerModule module : modules) out.modules.add(context.map(module));
        out.blocks = new PayloadSeq(blocks);
        out.progress = progress;
        out.warmup = warmup;
        out.droneWarmup = droneWarmup;
        out.powerWarmup = powerWarmup;
        out.sameTypeWarmup = sameTypeWarmup;
        out.invalidWarmup = invalidWarmup;
        out.currentTier = currentTier;
        out.lastTier = lastTier;
        out.wasOccupied = wasOccupied;
        out.droneProgress = droneProgress;
        out.totalDroneProgress = totalDroneProgress;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.ints(readUnits.items, 0, readUnits.size).bool(commandPos != null);
        if(commandPos != null) hasher.f(commandPos.x).f(commandPos.y);
        hasher.i(units.size());
        for(Unit unit : units) hasher.i(unit.id());
        hasher.i(modules.size());
        for(UnitAssemblerModule module : modules) hasher.i(module.id());
        blocks.hash(hasher);
        hasher.f(progress).f(warmup).f(droneWarmup).f(powerWarmup).f(sameTypeWarmup).f(invalidWarmup).i(currentTier).i(lastTier)
            .bool(wasOccupied).f(droneProgress).f(totalDroneProgress);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.intSeq("readUnits", readUnits).obj("commandPos", commandPos).refs("units", units.toArray(new Unit[0]), units.size())
            .refs("modules", modules.toArray(new UnitAssemblerModule[0]), modules.size()).ints("blocks", blocks.toArray())
            .f("progress", progress).f("warmup", warmup).f("droneWarmup", droneWarmup).f("powerWarmup", powerWarmup)
            .f("sameTypeWarmup", sameTypeWarmup).f("invalidWarmup", invalidWarmup).i("currentTier", currentTier).i("lastTier", lastTier)
            .bool("wasOccupied", wasOccupied).f("droneProgress", droneProgress).f("totalDroneProgress", totalDroneProgress);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        readUnits = in.intSeq("readUnits");
        commandPos = in.obj("commandPos", Vec2.class);
        units = new ArrayList<>(Arrays.asList(in.refs("units", Unit.class)));
        modules = new ArrayList<>(Arrays.asList(in.refs("modules", UnitAssemblerModule.class)));
        blocks = new PayloadSeq();
        blocks.set(in.ints("blocks"));
        progress = in.f("progress");
        warmup = in.f("warmup");
        droneWarmup = in.f("droneWarmup");
        powerWarmup = in.f("powerWarmup");
        sameTypeWarmup = in.f("sameTypeWarmup");
        invalidWarmup = in.f("invalidWarmup");
        currentTier = in.i("currentTier");
        lastTier = in.i("lastTier");
        wasOccupied = in.bool("wasOccupied");
        droneProgress = in.f("droneProgress");
        totalDroneProgress = in.f("totalDroneProgress");
    }
}
