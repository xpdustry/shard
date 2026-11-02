package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.defense.BuildTurret;
import com.xpdustry.shard.sim.blocks.defense.ForceProjector;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Processors' unit statements and {@code query}, as {@code LExecutor}'s {@code UnitBindI}, {@code UnitControlI},
 * {@code UnitLocateI}, {@code RadarI} and {@code QueryI} run them (LExecutor.java:169-555, 727-831, 1441-1533), for
 * {@link WorldHost}. A unit a processor controls gets a {@link LogicAi}; per-instruction state is the processor's
 * ({@link Processor#instructionState}) or the AI's, keyed as vanilla keys it by instruction object
 * ({@link Processor#generation()}, {@link Processor#hostInstruction()}).
 *
 * <p>Items move through {@link ItemTransfers}, as the player's and the miners' do, so each transfer sends its Call
 * once; {@code ucontrol mine} sets the tile {@link Miner} mines.
 *
 * <p>Payloads move through {@link Payloads}' Calls, as a player's and {@code CommandAI}'s do: {@code payDrop} and
 * {@code payTake} share the item transfers' timeout, {@code payEnter} has none.
 */
final class UnitStatements{
    /** {@code Vars.logicItemTransferRange}. */
    static final float ITEM_TRANSFER_RANGE = 45f;
    /** {@code Vars.buildingRange}. */
    static final float BUILDING_RANGE = 220f;
    static final float TILESIZE = 8f;
    private static final KindKey MEND_PROJECTOR = KindKey.of("mend-projector"), OVERDRIVE_PROJECTOR = KindKey.of("overdrive-projector"),
        REPAIR_TURRET = KindKey.of("repair-turret"), REPAIR_TOWER = KindKey.of("repair-tower");

    private final WorldHost host;
    private final World world;

    UnitStatements(WorldHost host){
        this.host = host;
        this.world = host.world;
    }

    private LogicUnits units(){
        return LogicUnits.of(world);
    }

    private boolean allowed(Processor p){
        return p.privileged || host.rules().logicUnitControl;
    }

    /** {@code exec.thisv.building()}: the processor's building, or null for a script. */
    private static Building self(Processor p){
        return p.building() instanceof Building building ? building : null;
    }

    /** The processor's building id, which with the code generation and the index names vanilla's instruction object. */
    private static int processorId(Processor p){
        Building self = self(p);
        return self == null ? -1 : self.id();
    }

    //region ubind

    /** UnitBindI (LExecutor.java:169): the next unit of a type, round-robin per processor, or one unit given. */
    void bind(Processor p, UnitBind u){
        if(!allowed(p)) return;
        Object type = p.obj(u.type());
        if(type instanceof UnitDef def && def.type() == ContentType.UNIT && logicControllable(def)){
            TeamUnits teams = TeamUnits.of(world);
            UnitSeq seq = teams == null ? null : teams.units(p.team(), world.content().unit(def.id()));
            if(seq != null && !seq.isEmpty()){
                int[] binds = binds(p);
                binds[def.id()] %= seq.size();
                if(binds[def.id()] < seq.size()) p.bindUnit(seq.get(binds[def.id()]));
                binds[def.id()]++;
                saveBinds(p, binds);
            }else{
                // no units of this type found
                p.bindUnit(null);
            }
        }else if(type instanceof Unit unit && (unit.team == p.team() || p.privileged) && logicControllable(unit.type())){
            p.bindUnit(unit);
        }else{
            p.bindUnit(null);
        }
    }

    private static boolean logicControllable(UnitDef type){
        return type.baked() && type.logicControllable();
    }

    /** {@code exec.binds}, one counter per unit type: kept in the processor's host data, which its code changes keep. */
    private int[] binds(Processor p){
        int types = world.content().units();
        int[] out = new int[types];
        if(p.hostData instanceof List<?> stored && stored.size() == types){
            for(int i = 0; i < types; i++) out[i] = (Integer)stored.get(i);
        }
        return out;
    }

    private static void saveBinds(Processor p, int[] binds){
        var stored = new ArrayList<Object>(binds.length);
        for(int bind : binds) stored.add(bind);
        p.hostData = stored;
    }

    //endregion
    //region ucontrol

    /**
     * {@code checkLogicAI(exec, unitObj, control)} (LExecutor.java:323): the bound, valid unit's {@link LogicAi}, now
     * this processor's; with {@code control}, a unit under any other AI that a processor may take is given a new one,
     * and stops mining and building. Null otherwise.
     */
    LogicAi checkLogicAi(Processor p, Object unitObj, boolean control){
        if(unitObj instanceof Unit unit && unit.isValid() && p.unit() == unit && (unit.team == p.team() || p.privileged)
            && logicControllable(unit.controller)){
            if(unit.controller instanceof LogicAi ai){
                ai.controller = self(p);
                return ai;
            }else if(control){
                var ai = new LogicAi(world);
                ai.controller = self(p);
                unit.controller(ai);
                // clear old state
                unit.mineTile = -1;
                Builder.clearBuilding(unit);
                return ai;
            }
        }
        return null;
    }

    /** {@code controller.isLogicControllable()}: an AI, but not a command AI with a position order; never a player. */
    static boolean logicControllable(UnitController controller){
        if(controller instanceof CommandAi ai) return ai.targetPos == null;
        return controller instanceof AiController;
    }

    /** UnitControlI (LExecutor.java:343). */
    void control(Processor p, UnitControl u){
        if(!allowed(p)) return;
        Object unitObj = p.unit();
        LUnitControl type = u.type();
        boolean control = type != LUnitControl.unbind && type != LUnitControl.within;
        LogicAi ai = checkLogicAi(p, unitObj, control);

        // only control standard AI units
        if(!(unitObj instanceof Unit unit) || (ai == null && control)) return;
        if(ai != null) ai.controlTimer = LogicAi.CONTROL_TIMEOUT;
        float x1 = p.numf(u.p1()) * TILESIZE, y1 = p.numf(u.p2()) * TILESIZE, d1 = p.numf(u.p3()) * TILESIZE;

        switch(type){
            case idle, autoPathfind -> ai.control = type;
            case move, stop, approach, pathfind -> {
                ai.control = type;
                ai.moveX = x1;
                ai.moveY = y1;
                if(type == LUnitControl.approach) ai.moveRad = d1;
                // stop mining/building
                if(type == LUnitControl.stop){
                    unit.mineTile = -1;
                    Builder.clearBuilding(unit);
                }
            }
            case unbind -> {
                if(unit.controller instanceof LogicAi) unit.resetController();
            }
            case within -> p.setnum(u.p4(), unit.within(x1, y1, d1) ? 1 : 0);
            case target -> {
                ai.posX = x1;
                ai.posY = y1;
                ai.aimControl = type;
                ai.mainTarget = null;
                ai.shoot = p.bool(u.p3());
            }
            case targetp -> {
                ai.aimControl = type;
                ai.mainTarget = p.obj(u.p1()) instanceof Unit || p.obj(u.p1()) instanceof Building ? (Entity)p.obj(u.p1()) : null;
                ai.shoot = p.bool(u.p2());
            }
            case boost -> ai.boost = p.bool(u.p1());
            case flag -> unit.flag = p.num(u.p1());
            case mine -> {
                // world.tileWorld(x1, y1): null off the map
                int tx = toTile(x1), ty = toTile(y1);
                int tile = world.tiles().inBounds(tx, ty) ? Point2.pack(tx, ty) : -1;
                if(Miner.canMine(unit)) unit.mineTile = Miner.validMine(unit, tile) ? tile : -1;
            }
            case payDrop -> payDrop(unit);
            case payTake -> payTake(p, u, unit);
            case payEnter -> payEnter(unit);
            case build -> build(p, u, unit, ai, x1, y1);
            case deconstruct -> deconstruct(unit, ai, x1, y1, p.privileged);
            case getBlock -> getBlock(p, u, unit, x1, y1);
            case itemDrop -> itemDrop(p, u, unit);
            case itemTake -> itemTake(p, u, unit);
        }
    }

    /** {@code World.toTile(coord)}. */
    private static int toTile(float coord){
        return Math.round(coord / TILESIZE);
    }

    private void build(Processor p, UnitControl u, Unit unit, LogicAi ai, float x1, float y1){
        Rules rules = host.rules();
        if(!((rules.logicUnitBuild || p.privileged) && Builder.canBuild(unit) && p.obj(u.p3()) instanceof BlockDef block
            && block.type() == ContentType.BLOCK && canBeBuilt(block))){
            return;
        }
        // vanilla quirk: the offset in tiles is taken off a position in world units
        float offset = Construction.of(world).info(block.id()).offset / TILESIZE;
        int x = toTile(x1 - offset), y = toTile(y1 - offset);
        int rot = Mathf.mod(p.numi(u.p4()), 4);

        // reset state of last request when necessary
        BuildPlan plan = ai.plan;
        if(plan.x != x || plan.y != y || plan.block != block.id() || unit.plans.isEmpty()){
            plan.progress = 0;
            plan.initialized = false;
            plan.stuck = false;
        }

        Object conf = p.obj(u.p5());
        // plan.set(x, y, rot, block)
        plan.x = x;
        plan.y = y;
        plan.rotation = BuildPlan.planRotation(block, rot);
        plan.block = block.id();
        plan.breaking = false;
        // a building as the config isn't stored: Shard's plans keep content and plain values only
        plan.config = conf instanceof ContentDef content ? WorldHost.sim(content) : null;

        Builder.clearBuilding(unit);
        TileGrid tiles = world.tiles();
        if(tiles.inBounds(x, y)){
            int pos = tiles.pos(x, y);
            Building standing = world.buildings().at(pos);
            if(!(tiles.block(pos) == block.id() && standing != null && standing.rotation == rot)){
                unit.updateBuilding = true;
                Builder.addBuild(unit, plan, true);
            }
        }
    }

    /** {@code block.canBeBuilt()}: not hidden, not debug only. */
    private boolean canBeBuilt(BlockDef block){
        PlaceInfo.Visibility visibility = Construction.of(world).info(block.id()).visibility;
        return visibility != PlaceInfo.Visibility.HIDDEN && visibility != PlaceInfo.Visibility.DEBUG_ONLY;
    }

    private void deconstruct(Unit unit, LogicAi ai, float x1, float y1, boolean privileged){
        if(!((host.rules().logicUnitDeconstruct || privileged) && Builder.canBuild(unit))) return;
        BuildPlan plan = ai.plan;
        int x = toTile(x1), y = toTile(y1);
        // reset state of last request when necessary
        if(plan.x != x || plan.y != y || !plan.breaking || unit.plans.isEmpty()){
            plan.progress = 0;
            plan.initialized = false;
            plan.stuck = false;
        }
        plan.x = x;
        plan.y = y;
        plan.breaking = true;
        Builder.clearBuilding(unit);
        if(world.tiles().inBounds(x, y) && Build.validBreak(world, unit.team, x, y)){
            unit.updateBuilding = true;
            Builder.addBuild(unit, plan, true);
        }
    }

    private void getBlock(Processor p, UnitControl u, Unit unit, float x1, float y1){
        AiType type = world.unitTable().ai(unit.type());
        float range = Math.max(type.range, type.buildRange);
        TileGrid tiles = world.tiles();
        int tx = toTile(x1), ty = toTile(y1);
        if(!unit.within(x1, y1, range) || !tiles.inBounds(tx, ty)){
            p.setobj(u.p3(), null);
            p.setobj(u.p4(), null);
            p.setobj(u.p5(), null);
            return;
        }
        int pos = tiles.pos(tx, ty);
        ContentEpoch content = world.content();
        p.setobj(u.p3(), host.vm(p, content.block(tiles.block(pos))));
        p.setobj(u.p4(), world.buildings().at(pos));
        // the ore if there is one, else the floor
        p.setobj(u.p5(), host.vm(p, content.block(tiles.overlay(pos) == 0 ? tiles.floor(pos) : tiles.overlay(pos))));
    }

    private void itemDrop(Processor p, UnitControl u, Unit unit){
        Object to = p.obj(u.p1());
        boolean air = to instanceof BlockDef block && block.type() == ContentType.BLOCK && block.id() == 0;
        LogicUnits units = units();
        if(!air && !units.timeoutDone(unit, LogicAi.TRANSFER_DELAY)) return;

        if(air){
            // clear the item when dropping to @air; items are synced in snapshots, so nothing is sent
            unit.clearItem();
            return;
        }
        Building build = to instanceof Building b ? b : null;
        int dropped = Math.min(unit.stackAmount, p.numi(u.p2()));
        if(build != null && build.team == unit.team && build.isValid() && build.allowDeposit() && dropped > 0
            && unit.within(build.x(), build.y(), ITEM_TRANSFER_RANGE + build.block().size() * TILESIZE / 2f)){
            int item = unit.item();
            int accepted = build.acceptStack(item, dropped, unit.team);
            if(accepted > 0){
                ItemTransfers.transferItemTo(world, unit, item, accepted, unit.x, unit.y, build);
                units.updateTimeout(unit);
            }
        }
    }

    /** {@code payDrop} (LExecutor.java:405): a carrier with a payload drops its last one where it is. */
    private void payDrop(Unit unit){
        LogicUnits units = units();
        if(!units.timeoutDone(unit, LogicAi.TRANSFER_DELAY)) return;
        if(unit.payloads != null && Payloads.hasPayload(unit)){
            Payloads.payloadDropped(unit, unit.x, unit.y, new Cause.Ai(unit.team));
            units.updateTimeout(unit);
        }
    }

    /**
     * {@code payTake} (LExecutor.java:413): with {@code p1} true, the closest AI ground unit of its team within reach;
     * else the building under it, of its team: what it holds, or the building itself. The timeout restarts whether or
     * not anything was taken.
     */
    private void payTake(Processor p, UnitControl u, Unit unit){
        LogicUnits units = units();
        if(!units.timeoutDone(unit, LogicAi.TRANSFER_DELAY) || unit.payloads == null) return;
        if(p.bool(u.p1())){
            SpatialIndex index = SpatialIndex.of(world);
            Unit result = index == null ? null : index.closest(unit.team, unit.x, unit.y, unit.type().hitSize() * 2f,
                other -> other != unit && other.isAI() && other.isGrounded() && Payloads.canPickup(unit, other)
                    && other.within(unit.x, unit.y, other.hitSize + unit.hitSize * 1.2f));
            if(result != null) Payloads.pickedUnitPayload(unit, result);
        }else{
            Building build = buildWorld(unit.x, unit.y);
            // vanilla's TODO: copied from the player's pickup, without its interaction check
            if(build != null && build.team == unit.team){
                Payload current = build.getPayload();
                if(current != null && Payloads.canPickupPayload(unit, current)){
                    Payloads.pickedBuildPayload(unit, build, false, new Cause.Ai(unit.team));
                }else if(Payloads.visible(build) && build.canPickup() && Payloads.canPickup(unit, build)){
                    Payloads.pickedBuildPayload(unit, build, true, new Cause.Ai(unit.team));
                }
            }
        }
        units.updateTimeout(unit);
    }

    /** {@code payEnter} (LExecutor.java:441): the unit enters the building under it, if that lets it. */
    private void payEnter(Unit unit){
        Building build = buildWorld(unit.x, unit.y);
        if(build != null && unit.team == build.team && build.canControlSelect(unit)) Payloads.unitBuildingControlSelect(unit, build);
    }

    /** {@code world.buildWorld(x, y)}. */
    private Building buildWorld(float x, float y){
        int tx = toTile(x), ty = toTile(y);
        return world.tiles().inBounds(tx, ty) ? world.buildings().at(tx, ty) : null;
    }

    private void itemTake(Processor p, UnitControl u, Unit unit){
        LogicUnits units = units();
        if(!units.timeoutDone(unit, LogicAi.TRANSFER_DELAY)) return;
        Building build = p.obj(u.p1()) instanceof Building b ? b : null;
        int amount = p.numi(u.p3());
        if(build != null && build.team == unit.team && build.isValid() && build.items != null && p.obj(u.p2()) instanceof ItemDef item
            && item.type() == ContentType.ITEM && unit.within(build.x(), build.y(), ITEM_TRANSFER_RANGE + build.block().size() * TILESIZE / 2f)){
            int taken = Math.min(build.items.get(item.id()), Math.min(amount, unit.maxAccepted(item.id())));
            if(taken > 0){
                ItemTransfers.takeItems(world, build, item.id(), taken, unit);
                units.updateTimeout(unit);
            }
        }
    }

    //endregion
    //region ulocate

    /** UnitLocateI (LExecutor.java:233). */
    void locate(Processor p, UnitLocate l){
        if(!allowed(p)) return;
        Object unitObj = p.unit();
        LogicAi ai = checkLogicAi(p, unitObj, true);
        if(!(unitObj instanceof Unit unit) || ai == null){
            p.setbool(l.outFound(), false);
            return;
        }
        ai.controlTimer = LogicAi.CONTROL_TIMEOUT;
        int processor = processorId(p), generation = p.generation(), index = p.hostInstruction();
        LogicAi.Cached cache = ai.cached(processor, generation, index);

        if(ai.checkTargetTimer(processor, generation, index)){
            TileGrid tiles = world.tiles();
            int res = -1;
            boolean build = false;
            switch(l.locate()){
                case ore -> {
                    if(p.obj(l.ore()) instanceof ItemDef item && item.type() == ContentType.ITEM){
                        res = OreIndex.of(world).findClosestOre(unit.x, unit.y, item.id());
                    }
                }
                case building -> {
                    Buildings buildings = world.buildings();
                    com.xpdustry.shard.sim.building.BlockFlag flag = com.xpdustry.shard.sim.building.BlockFlag.named(l.flag().name());
                    Building b = flag == null ? null
                        : closest(unit.x, unit.y, p.bool(l.enemy()) ? buildings.enemy(unit.team, flag) : buildings.flagged(unit.team, flag));
                    res = b == null ? -1 : b.tile();
                    build = true;
                }
                case spawn -> res = closestSpawn(unit);
                case damaged -> {
                    Building b = closest(unit.x, unit.y, world.buildings().damaged(unit.team));
                    res = b == null ? -1 : b.tile();
                    build = true;
                }
            }

            Building at = res == -1 ? null : world.buildings().at(res);
            if(res != -1 && (!build || at != null)){
                cache.any = true;
                // set result if found
                p.setnum(l.outX(), cache.x = (build ? at.x() : tiles.x(res) * TILESIZE) / TILESIZE);
                p.setnum(l.outY(), cache.y = (build ? at.y() : tiles.y(res) * TILESIZE) / TILESIZE);
                p.setnum(l.outFound(), 1);
            }else{
                cache.any = false;
                p.setnum(l.outFound(), 0);
            }

            if(at != null && (unit.within(at.x(), at.y(), Math.max(world.unitTable().ai(unit.type()).range, BUILDING_RANGE)) || at.team == p.team())){
                cache.build = at;
                p.setobj(l.outBuild(), at);
            }else{
                // vanilla quirk: the cached building stays as it was
                p.setobj(l.outBuild(), null);
            }
        }else{
            p.setobj(l.outBuild(), cache.build);
            p.setbool(l.outFound(), cache.any);
            p.setnum(l.outX(), cache.x);
            p.setnum(l.outY(), cache.y);
        }
    }

    /** {@code Geometry.findClosest(x, y, list)}: the first of the nearest, by squared distance. */
    private static Building closest(float x, float y, List<Building> list){
        Building closest = null;
        float cdist = 0f;
        for(Building building : list){
            float dst = Mathf.dst2(building.x(), building.y(), x, y);
            if(closest == null || dst < cdist){
                closest = building;
                cdist = dst;
            }
        }
        return closest;
    }

    /** {@code Geometry.findClosest(unit.x, unit.y, spawner.getSpawns())}: a spawn tile's position, or -1. */
    private int closestSpawn(Unit unit){
        WaveSpawner spawner = WaveSpawner.of(world);
        if(spawner == null) return -1;
        IntSeq spawns = spawner.spawns(world);
        int width = world.tiles().width(), closest = -1;
        float cdist = 0f;
        for(int i = 0; i < spawns.size; i++){
            int pos = spawns.get(i);
            float dst = Mathf.dst2(pos % width * TILESIZE, pos / width * TILESIZE, unit.x, unit.y);
            if(closest == -1 || dst < cdist){
                closest = pos;
                cdist = dst;
            }
        }
        return closest;
    }

    //endregion
    //region radar

    /**
     * RadarI (LExecutor.java:755): the best unit in range of a ranged building or a controllable unit, by the sort key.
     * A building searches again every 30 frames or when it changes; a unit once per AI targeting window.
     */
    void radar(Processor p, Radar r){
        Object base = p.obj(r.radar());
        int sortDir = p.bool(r.sortOrder()) ? 1 : -1;
        LogicAi ai = null;
        float range = base instanceof Building building ? rangeOf(building) : base instanceof Unit unit ? world.unitTable().ai(unit.type()).range : Float.NaN;
        int team = base instanceof Building building ? building.team : base instanceof Unit unit ? unit.team : -1;

        // a building or a controllable unit
        if(Float.isNaN(range) || !(p.privileged || team == p.team())
            || !((base instanceof Building building && (!world.tiles().table().privileged(building.block().id()) || p.privileged))
            || (ai = checkLogicAi(p, base, true)) != null)){
            p.setobj(r.output(), null);
            return;
        }

        int index = p.hostInstruction();
        List<Object> state = instructionState(p, index);
        Entity targeted;
        // buildings search every 30 frames, units once per window of their AI
        if((base instanceof Building && (timerGet(state, 30f) || state.get(2) != base))
            || (ai != null && ai.checkTargetTimer(processorId(p), p.generation(), index))){
            boolean enemies = r.target1() == RadarTarget.enemy || r.target2() == RadarTarget.enemy || r.target3() == RadarTarget.enemy;
            boolean allies = r.target1() == RadarTarget.ally || r.target2() == RadarTarget.ally || r.target3() == RadarTarget.ally;
            float x = base instanceof Building b ? b.x() : ((Unit)base).x, y = base instanceof Building b ? b.y() : ((Unit)base).y;
            var search = new Search(base, x, y, team, range, sortDir, r);
            LogicUnits units = units();
            if(enemies){
                for(int t = 0; t < TeamUnits.TEAMS; t++) if(t != team && units.hasUnits(t)) search.find(units.tree(t), t);
            }else if(!allies){
                for(int t = 0; t < TeamUnits.TEAMS; t++) if(units.hasUnits(t)) search.find(units.tree(t), t);
            }else if(units.hasUnits(team)){
                search.find(units.tree(team), team);
            }
            if(ai != null) ai.cached(processorId(p), p.generation(), index).found = search.best;
            state.set(1, search.best);
            state.set(2, base);
            targeted = search.best;
        }else if(ai != null){
            targeted = ai.cached(processorId(p), p.generation(), index).found;
        }else{
            targeted = (Entity)state.get(1);
        }
        p.setobj(r.output(), targeted);
    }

    /** RadarI's own state: {@code timer}'s time, {@code lastTarget}, {@code lastSourceBuild}. */
    @SuppressWarnings("unchecked")
    private static List<Object> instructionState(Processor p, int index){
        if(p.instructionState(index) instanceof List<?> list) return (List<Object>)list;
        var made = new ArrayList<Object>(Arrays.asList(0d, null, null));
        p.instructionState(index, made);
        return made;
    }

    /** {@code timer.get(time)}: an {@code Interval} over the world's {@code Time.time}. */
    private boolean timerGet(List<Object> state, float time){
        float now = world.timers().time(), last = ((Double)state.get(0)).floatValue();
        boolean got = now - last >= time || now < last;
        if(got) state.set(0, (double)now);
        return got;
    }

    /** {@code Ranged.range()} of a building, or NaN for a building that isn't {@code Ranged}. */
    static float rangeOf(Building building){
        if(building instanceof TurretBuild turret) return turret.range();
        if(building instanceof LogicBlock logic) return logic.range();
        // ForceBuild's realRadius(); point defence, tractor beams and build towers are BaseTurretBuild's block range
        if(building instanceof ForceProjector || building instanceof PointDefenseTurret || building instanceof TractorBeamTurret
            || building instanceof BuildTurret) return building.range();
        BlockDef block = building.block();
        if(!block.baked()) return Float.NaN;
        if(block.isA(MEND_PROJECTOR) || block.isA(OVERDRIVE_PROJECTOR) || block.isA(REPAIR_TOWER)) return block.fields().f("range");
        if(block.isA(REPAIR_TURRET)) return block.fields().f("repairRadius");
        return Float.NaN;
    }

    /** One RadarI search: vanilla's static {@code best} and {@code bestValue}. */
    private final class Search{
        final Object source;
        final float x, y, range;
        final int team, sortDir;
        final Radar radar;
        Unit best;
        float bestValue;

        Search(Object source, float x, float y, int team, float range, int sortDir, Radar radar){
            this.source = source;
            this.x = x;
            this.y = y;
            this.team = team;
            this.range = range;
            this.sortDir = sortDir;
            this.radar = radar;
        }

        /** {@code find(r, range, sortDir, team)}: {@code Units.nearby(team, x, y, range, ...)} over the team's tree. */
        void find(UnitTree tree, int unitTeam){
            tree.intersect(x - range, y - range, range * 2f, range * 2f, u -> {
                if(!u.within(x, y, range + u.hitSize / 2f)) return;
                // vanilla quirk: targetable is asked for the team searched, the unit's own
                if(!u.within(x, y, range) || !u.targetable(unitTeam) || source == u) return;
                if(!matches(radar.target1(), u) || !matches(radar.target2(), u) || !matches(radar.target3(), u)) return;
                float val = sort(u) * sortDir;
                if(val > bestValue || best == null){
                    bestValue = val;
                    best = u;
                }
            });
        }

        /** {@code RadarTarget.func}. */
        boolean matches(RadarTarget target, Unit other){
            return switch(target){
                case any -> true;
                case enemy -> team != other.team && other.team != VanillaIds.DERELICT;
                case ally -> team == other.team;
                case player -> other.isPlayer();
                case attacker -> !other.statuses.disarmed && !(other.params().canBoost() && other.isFlying());
                case flying -> other.isFlying();
                case boss -> other.statuses.hasEffect(boss());
                case ground -> other.isGrounded();
            };
        }

        /** {@code RadarSort.func}. */
        float sort(Unit other){
            return switch(radar.sort()){
                case distance -> -Mathf.dst2(x, y, other.x, other.y);
                case health -> other.health;
                case shield -> other.shield;
                case armor -> other.armor;
                case maxHealth -> other.maxHealth;
            };
        }
    }

    private int boss(){
        return world.content().id(ContentType.STATUS, "boss");
    }

    //endregion
    //region query

    /** QueryI (LExecutor.java:1468): units or buildings in a rectangle or circle, into {@code @queries}. */
    void query(Processor p, Query q){
        if(!p.program().hasQueries()) return;
        @SuppressWarnings("unchecked")
        List<Object> results = p.queries() instanceof List<?> list ? (List<Object>)list : null;
        if(results == null){
            results = new ArrayList<>();
            p.queries(results);
        }
        float x = p.numf(q.x()) * TILESIZE, y = p.numf(q.y()) * TILESIZE, w = p.numf(q.width()) * TILESIZE, h = p.numf(q.height()) * TILESIZE;
        float radius = w, circleX = x, circleY = y;
        boolean circle = q.shape() == QueryShape.circle;
        if(circle){
            x -= radius;
            y -= radius;
            w = radius * 2f;
            h = radius * 2f;
        }
        results.clear();
        int team = WorldHost.team(p, q.team());
        // vanilla quirk: bullets are never found (they are pooled)
        if(q.type() == QueryType.bullet) return;

        List<Object> found = results;
        switch(q.type()){
            case unit -> {
                LogicUnits units = units();
                for(int t = team < 0 ? 0 : team; t < (team < 0 ? TeamUnits.TEAMS : team + 1); t++){
                    if(team < 0 && !units.hasUnits(t)) continue;
                    units.tree(t).intersect(x, y, w, h, found::add);
                }
            }
            case building -> {
                Buildings buildings = world.buildings();
                for(int t = team < 0 ? 0 : team; t < (team < 0 ? TeamUnits.TEAMS : team + 1); t++){
                    BuildingTree tree = buildings.tree(t);
                    if(tree != null) tree.intersect(x, y, w, h, found::add);
                }
            }
            default -> {}
        }

        if(circle){
            // Seq.removeAll on an unordered seq: the last entry takes the removed one's place
            for(int i = 0; i < results.size(); i++){
                Object value = results.get(i);
                boolean out = value instanceof Unit u ? !u.within(circleX, circleY, radius + u.hitSize / 2f)
                    : !((Building)value).within(circleX, circleY, radius + ((Building)value).hitSize() / 2f);
                if(out){
                    results.set(i, results.getLast());
                    results.removeLast();
                    i--;
                }
            }
        }
    }

    //endregion
}
