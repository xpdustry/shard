package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RtsAI} (RtsAI.java) for one team, which {@code rules.teams[team].rtsAi} turns on: every 2 s it
 * groups the team's idle commanded units into squads (units of the same "mobilized" flag within 60 world units of
 * each other, by the team's unit quadtree), and sends each squad to defend a building that bullets hit since the last
 * round, or to attack the enemy building it rates best: generators, factories, cores, batteries and drills (cores
 * only, for naval squads), up to 15 of them in a random order, each weighed by the time the squad needs to destroy
 * the turrets and units on the way against the time they need to destroy it. Every 7 s, with {@code aiCoreSpawn}, it
 * also makes a core unit while the team has fewer than its cores.
 *
 * <p>The orders go through each unit's {@link CommandAi}, which then runs them on the control pathfinder.
 *
 * <p>State: the two timers vanilla's {@code Interval} keeps, and the buildings hit since the last round, in the order
 * they were first hit. Vanilla keeps those in an {@code ObjectSet}, which iterates in identity-hash order and so
 * differs from one JVM to the next; the order only decides between buildings at the same distance (and between cores,
 * which all rate the same). The rest of vanilla's fields are scratch, static there and per call here.
 */
public final class RtsAi implements Checkpointable{
    static final float SQUAD_RADIUS = 60f;
    static final int TIMER_UPDATE = 0, TIMER_SPAWN = 1, MAX_TARGETS_CHECKED = 15;
    /** In order of priority. */
    static final BlockFlag[] FLAGS = {BlockFlag.generator, BlockFlag.factory, BlockFlag.core, BlockFlag.battery, BlockFlag.drill};

    int team;
    /** {@code timer}'s times, by id. Vanilla's {@code Interval} has 10; it uses two. */
    final float[] timers = new float[2];
    /** {@code damagedSet}: buildings of this team that bullets hit since the last round, in the order first hit. */
    final ArrayList<Building> damagedSet = new ArrayList<>();

    /** For a checkpoint to fill. */
    public RtsAi(){
    }

    /** {@code new RtsAI(data)}: the update timer starts up to 2 s early. */
    RtsAi(World world, int team){
        this.team = team;
        timers[TIMER_UPDATE] = world.timers().time() - Mathf.random(world.rand(), 60f * 2f);
    }

    public int team(){
        return team;
    }

    /** {@code timer.getTimes()}, for tests. */
    public float[] timers(){
        return timers.clone();
    }

    /** The buildings hit since the last round, for tests. */
    public List<Building> damaged(){
        return List.copyOf(damagedSet);
    }

    /** The {@code BuildDamageEvent} listener: a bullet hit one of the team's buildings. */
    void buildDamaged(Building build){
        if(!damagedSet.contains(build)) damagedSet.add(build);
    }

    /** {@code Interval.get(id, time)}. */
    private static boolean timer(float[] times, World world, int id, float time){
        float now = world.timers().time();
        boolean got = now - times[id] >= time || now < times[id];
        if(got) times[id] = now;
        return got;
    }

    /** {@code update()}. */
    void update(World world){
        if(timer(timers, world, TIMER_UPDATE, 60f * 2f)){
            assignSquads(world);
            checkBuilding(world);
        }
    }

    /** {@code checkBuilding()}: core units, one at a time, at a random core. */
    private void checkBuilding(World world){
        Teams teams = Teams.of(world);
        TeamData data = teams.get(team);
        if(rules(world).teams.get(team).aiCoreSpawn && timer(timers, world, TIMER_SPAWN, 60 * 7f) && data.hasCore()){
            UnitDef type = coreUnit(world, data.core());
            int coreUnits = TeamUnits.of(world).count(team, type);
            // create AI core unit(s) at random cores
            if(coreUnits < data.cores().size()){
                spawnCoreUnit(world, type, team, data.cores());
            }
        }
    }

    /** {@code block.unitType.create(team)}, set at a random core, added; {@code Fx.spawn} draws nothing on a server. */
    static void spawnCoreUnit(World world, UnitDef type, int team, List<Core> cores){
        Unit unit = Unit.create(world, type, team);
        Core core = cores.get(seqRandom(world.rand(), cores.size()));
        unit.set(core.x(), core.y());
        unit.add();
    }

    /**
     * {@code Seq.random()}'s index for a list of {@code size} (size > 0): Arc's {@code Rand.random(0, size - 1)}, which
     * returns 0 without a draw for a list of one.
     */
    static int seqRandom(Rand rand, int size){
        return size - 1 <= 0 ? 0 : Mathf.random(rand, 0, size - 1);
    }

    static UnitDef coreUnit(World world, Core core){
        return world.content().unit(core.block().fields().s("unitType"));
    }

    static Rules rules(World world){
        return UnitCalls.rules(world);
    }

    /** One round's scratch: vanilla's statics. */
    private static final class Round{
        final IntSet used = new IntSet(), assignedTargets = new IntSet(), invalidTarget = new IntSet();
        final ArrayList<Building> damaged = new ArrayList<>();
        final ArrayList<Unit> squad = new ArrayList<>(), stack = new ArrayList<>();
    }

    /** {@code assignSquads()}. */
    private void assignSquads(World world){
        var round = new Round();
        round.damaged.addAll(damagedSet);
        damagedSet.clear();
        boolean didDefend = false;
        LogicUnits trees = LogicUnits.of(world);
        UnitSeq units = TeamUnits.of(world).units(team);
        for(int i = 0; i < units.size(); i++){
            Unit unit = units.get(i);
            if(round.used.add(unit.id()) && unit.controller instanceof CommandAi cai && !cai.hasCommand() && !cai.isAttacking()){
                round.squad.clear();
                round.squad.add(unit);
                round.stack.clear();
                round.stack.add(unit);
                float rad = SQUAD_RADIUS + unit.hitSize * 1.5f;
                while(!round.stack.isEmpty()){
                    Unit next = round.stack.removeLast();
                    trees.intersect(team, next.x - rad / 2f, next.y - rad / 2f, rad, rad, u -> {
                        if(u.controller instanceof CommandAi ai && !ai.hasCommand() && ((u.flag == 0) == (unit.flag == 0)) && round.used.add(u.id())){
                            round.squad.add(u);
                            round.stack.add(u);
                        }
                    });
                }
                if(handleSquad(world, round, round.squad, !didDefend)){
                    didDefend = true;
                }
            }
        }
    }

    /** {@code handleSquad(units, noDefenders)}: whether the squad went to defend. */
    private boolean handleSquad(World world, Round round, List<Unit> units, boolean noDefenders){
        if(units.isEmpty()) return false;
        Unit first = units.getFirst();
        boolean naval = first.params().locomotion() == Locomotion.NAVAL;
        float health = 0f, dps = 0f;
        float ax = 0f, ay = 0f;
        boolean targetAir = true, targetGround = true;
        for(Unit unit : units){
            AiType type = world.unitTable().ai(unit.type());
            if(!type.targetAir) targetAir = false;
            if(!type.targetGround) targetGround = false;
            ax += unit.x;
            ay += unit.y;
            health += unit.health;
            dps += dpsEstimate(unit.type());
        }
        ax /= units.size();
        ay /= units.size();

        Building defend = null;
        boolean defendingCore = false;
        TeamRule teamRule = rules(world).teams.get(team);

        // there is something to defend, see if it's worth the time
        if(!round.damaged.isEmpty() && !naval){
            // damaged.min(...): the last of the smallest; cores first, immediately
            Building best = null;
            float min = Float.MAX_VALUE;
            for(Building b : round.damaged){
                float value = b instanceof Core ? -999999f : b.dst(ax, ay);
                if(value <= min){
                    best = b;
                    min = value;
                }
            }
            // defend when close, or this is the only squad defending
            if(best != null && (best instanceof Core || (units.size() >= teamRule.rtsMinSquad || (units.size() > 0 && first.flag != 0))
                || best.within(ax, ay, 1000f))){
                defend = best;
                if(best instanceof Core) defendingCore = true;
            }
        }

        boolean tair = targetAir, tground = targetGround;
        // find aggressor, or else, the thing being attacked
        Vec2 defendPos = null;
        Entity defendTarget = null;
        if(defend != null){
            float checkRange = 350f;
            Unit aggressor = world.part(com.xpdustry.shard.sim.spatial.SpatialIndex.class)
                .closestEnemy(team, defend.x(), defend.y(), checkRange, u -> Targets.checkTarget(u, tair, tground));
            if(aggressor != null){
                defendPos = new Vec2(aggressor.x, aggressor.y);
                defendTarget = aggressor;
            }else{
                float mindst = Float.MAX_VALUE;
                Building build = null;
                // find closest turret to attack
                for(Building turret : world.buildings().enemy(team, BlockFlag.turret)){
                    if(turret.within(defend.x(), defend.y(), turret.range())){
                        float dst = dst2(turret, defend.x(), defend.y());
                        if(dst < mindst){
                            mindst = dst;
                            build = turret;
                        }
                    }
                }
                if(build != null) defendTarget = build;
            }
        }

        boolean anyDefend = defendPos != null || defendTarget != null;

        round.invalidTarget.clear();
        for(Unit unit : units){
            if(unit.controller instanceof CommandAi ai){
                IntSeq unreachable = ai.unreachableBuildings;
                for(int i = 0; i < unreachable.size; i++) round.invalidTarget.add(unreachable.get(i));
            }
        }

        Building build = anyDefend ? null
            : findTarget(world, round, ax, ay, units.size(), dps, health, first.flag == 0, first.isFlying(), naval);

        if(build != null || anyDefend){
            ControlPathfinder paths = ControlPathfinder.of(world);
            for(Unit unit : units){
                if(unit.controller instanceof CommandAi ai && !ai.hasCommand()){
                    if(defendPos != null && !pathImpassable(paths, unit, toTile(defendPos.x), toTile(defendPos.y))){
                        ai.commandPosition(defendPos, true);
                    }else{
                        // TODO stopAtTarget parameter could be false, could be tweaked (vanilla's)
                        ai.commandTarget(defendTarget == null ? build : defendTarget, defendTarget != null);
                    }
                    // assign a flag, so it will be "mobilized" more easily later
                    if(!defendingCore) unit.flag = 1;
                }
            }
        }
        return anyDefend;
    }

    /** {@code findTarget(x, y, total, dps, health, checkWeight, air, naval)}. */
    private Building findTarget(World world, Round round, float x, float y, int total, float dps, float health, boolean checkWeight,
                                boolean air, boolean naval){
        TeamRule teamRule = rules(world).teams.get(team);
        if(total < teamRule.rtsMinSquad) return null;
        Buildings buildings = world.buildings();
        var targets = new ArrayList<Building>();
        if(naval){
            // naval units can only target enemy cores, because those are assumed to always be reachable
            targets.addAll(buildings.enemy(team, BlockFlag.core));
        }else{
            for(BlockFlag flag : FLAGS) targets.addAll(buildings.enemy(team, flag));
        }
        targets.removeIf(b -> round.assignedTargets.contains(b.id()) || round.invalidTarget.contains(b.pos()));
        if(targets.isEmpty()) return null;

        // only check a maximum number of targets: Seq.shuffle, then truncate
        Rand rand = world.rand();
        for(int i = targets.size() - 1; i >= 0; i--){
            int ii = Mathf.random(rand, i);
            Building temp = targets.get(i);
            targets.set(i, targets.get(ii));
            targets.set(ii, temp);
        }
        while(targets.size() > MAX_TARGETS_CHECKED) targets.removeLast();

        // weights.put(target, ...): by index here; a building listed twice weighs the same both times
        var weights = new float[targets.size()];
        for(int i = 0; i < targets.size(); i++){
            Building target = targets.get(i);
            weights[i] = estimateStats(world, x, y, target.x(), target.y(), dps, health, air);
        }

        // targets.min(comps(weight, then distance)): the first of the smallest
        int result = -1;
        for(int i = 0; i < targets.size(); i++){
            if(result == -1 || compare(targets.get(result), weights[result], targets.get(i), weights[i], x, y) > 0) result = i;
        }

        float weight = weights[result];
        if(checkWeight && (weight < teamRule.rtsMinWeight && total < teamRule.rtsMaxSquad) && total < Units.getCap(world, team)){
            return null;
        }
        Building chosen = targets.get(result);
        round.assignedTargets.add(chosen.id());
        return chosen;
    }

    private static int compare(Building a, float weightA, Building b, float weightB, float x, float y){
        // weight is most important? (vanilla's question)
        int value = Float.compare((1f - weightA) + a.dst(x, y) / 10000f, (1f - weightB) + b.dst(x, y) / 10000f);
        // then distance
        return value != 0 ? value : Float.compare(dst2(a, x, y), dst2(b, x, y));
    }

    /** {@code estimateStats(fromX, fromY, x, y, selfDps, selfHealth, air)}: the squad's advantage on the way to (x, y). */
    float estimateStats(World world, float fromX, float fromY, float x, float y, float selfDps, float selfHealth, boolean air){
        float[] health = {0f}, dps = {0f};
        float extraRadius = 50f;
        for(Building turret : world.buildings().enemy(team, BlockFlag.turret)){
            if(turret instanceof TurretBuild t && ((t.type().targetAir && air) || (t.type().targetGround && !air))
                && distanceSegmentPoint(fromX, fromY, x, y, t.x(), t.y()) <= t.range() + extraRadius){
                health[0] += t.health;
                dps[0] += t.estimateDps();
            }
        }

        // Tmp.r1.set(fromX, fromY, x - fromX, y - fromY).normalize().grow(140f * 2f)
        float rx = fromX, ry = fromY, rw = x - fromX, rh = y - fromY;
        if(rw < 0){
            rx += rw;
            rw = -rw;
        }
        if(rh < 0){
            ry += rh;
            rh = -rh;
        }
        float grow = 140f * 2f;
        rx -= grow / 2f;
        ry -= grow / 2f;
        rw += grow;
        rh += grow;

        // add on extra radius, assume unit range is below that...?
        LogicUnits trees = LogicUnits.of(world);
        TeamUnits teamUnits = TeamUnits.of(world);
        for(int other = 0; other < TeamUnits.TEAMS; other++){
            // Units.nearbyEnemies: every present team but this one, in id order; a team without units has an empty tree
            if(other == team || teamUnits.units(other).isEmpty()) continue;
            trees.intersect(other, rx, ry, rw, rh, u -> {
                if(distanceSegmentPoint(fromX, fromY, x, y, u.x, u.y) <= world.unitTable().ai(u.type()).range + extraRadius){
                    health[0] += u.health;
                    dps[0] += dpsEstimate(u.type());
                }
            });
        }

        float hp = health[0], dp = dps[0];
        float timeDestroyOther = Mathf.zero(selfDps, 0.001f) ? Float.POSITIVE_INFINITY : hp / selfDps;
        float timeDestroySelf = Mathf.zero(dp) ? Float.POSITIVE_INFINITY : selfHealth / dp;
        // other can never be destroyed | other destroys self instantly
        if(Float.isInfinite(timeDestroyOther) || Mathf.zero(timeDestroySelf)) return 0f;
        // self can never be destroyed | self destroys other instantly
        if(Float.isInfinite(timeDestroySelf) || Mathf.zero(timeDestroyOther)) return 100000f;
        return timeDestroySelf / timeDestroyOther;
    }

    static float dpsEstimate(UnitDef type){
        return type.fields().f("dpsEstimate");
    }

    /** {@code Position.dst2(x, y)}. */
    static float dst2(Building b, float x, float y){
        float dx = b.x() - x, dy = b.y() - y;
        return dx * dx + dy * dy;
    }

    /** {@code World.toTile(coord)}. */
    static int toTile(float coord){
        return Math.round(coord / 8f);
    }

    /** {@code unit.isPathImpassable(x, y)}, as {@link CommandAi} asks it: on the control pathfinder's costs. */
    static boolean pathImpassable(ControlPathfinder paths, Unit unit, int x, int y){
        return paths != null && paths.active() && !unit.params().flying() && paths.pathImpassable(unit, x, y);
    }

    /** Arc's {@code Intersector.distanceSegmentPoint}: from (px, py) to the nearest point of the segment. */
    static float distanceSegmentPoint(float startX, float startY, float endX, float endY, float pointX, float pointY){
        float xDiff = endX - startX, yDiff = endY - startY;
        float length2 = xDiff * xDiff + yDiff * yDiff;
        float nx, ny;
        if(length2 == 0){
            nx = startX;
            ny = startY;
        }else{
            float t = ((pointX - startX) * (endX - startX) + (pointY - startY) * (endY - startY)) / length2;
            if(t < 0){
                nx = startX;
                ny = startY;
            }else if(t > 1){
                nx = endX;
                ny = endY;
            }else{
                nx = startX + t * (endX - startX);
                ny = startY + t * (endY - startY);
            }
        }
        return Mathf.dst(nx, ny, pointX, pointY);
    }

    // ---------------------------------------------------------------- state

    RtsAi copy(CopyContext context){
        var out = new RtsAi();
        out.team = team;
        System.arraycopy(timers, 0, out.timers, 0, timers.length);
        for(Building b : damagedSet) out.damagedSet.add(context.map(b));
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(team).f(timers[0]).f(timers[1]).i(damagedSet.size());
        for(Building b : damagedSet) hasher.i(b.id());
    }

    @Override
    public void write(CheckpointOut out){
        out.i("team", team).floats("timers", timers).refs("damagedSet", damagedSet.toArray(new Building[0]), damagedSet.size());
    }

    @Override
    public void read(CheckpointIn in){
        team = in.i("team");
        in.floats("timers", timers);
        Building[] damaged = in.refs("damagedSet", Building.class);
        if(damaged == null) throw new CheckpointException("an RTS AI without its damaged buildings");
        damagedSet.clear();
        damagedSet.addAll(Arrays.asList(damaged));
    }
}
