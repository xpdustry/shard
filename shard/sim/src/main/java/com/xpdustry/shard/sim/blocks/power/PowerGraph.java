package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code PowerGraph} (PowerGraph.java) and its {@code PowerGraphUpdater} entity (PowerGraphUpdaterComp.java)
 * as one object: vanilla never makes a graph without its updater on a server, and the updater only holds the graph.
 * Its entity id is the updater's, drawn when the graph is made; {@link #add()} and {@link #remove()} are the updater
 * joining and leaving {@code Groups.powerGraph} ({@link PowerGraphs}), whose order is the order graphs update in the
 * {@code POWER} phase.
 *
 * <p>Members are kept as vanilla keeps them: four lists in append order (every member, producers, consumers,
 * batteries), which decide the float summation order of every solve. A graph never removes a member: a split makes
 * new graphs ({@link #remove(Building)}) and the old one leaves the group with stale lists, which its last members may
 * still point at.
 *
 * <p>The topology operations are vanilla's, step for step, because their side effects are gameplay: {@link #remove}
 * and {@link PowerNode}'s unlink {@link #update()} new graphs at once; {@link #addGraph} merges the smaller graph into
 * the larger; {@link #reflow} walks breadth first without closing its start. The building side
 * ({@code getPowerConnections}, {@code updatePowerGraph}, {@code powerGraphRemoved}, the team-change reflow) is the
 * static half of this class, which {@link Building} and {@link Buildings} call.
 *
 * <p>State, all {@link Persistence#AUTHORITATIVE}: the lists, the last solve's statistics, the diode energy delta and
 * the power balance window. {@code Time.delta} is 1, so vanilla's divisions by it are left out.
 *
 * <p>Owner: WP37.
 */
public final class PowerGraph extends Entity implements Checkpointable{
    /** {@code powerBalance}'s window, {@code new WindowedMean(60)}. */
    static final int BALANCE_WINDOW = 60;
    private static final Building[] NONE = {};

    static final ActiveList.Slot<PowerGraph> SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(PowerGraph graph){
            return graph.groupIndex;
        }

        @Override
        public void set(PowerGraph graph, int position){
            graph.groupIndex = position;
        }
    };

    @Derived("PowerGraphs.read: the position in Groups.powerGraph, or -1")
    int groupIndex = -1;

    private Building[] producers = NONE, consumers = NONE, batteries = NONE, all = NONE;
    private int producerCount, consumerCount, batteryCount, allCount;
    private float lastPowerProduced, lastPowerNeeded, lastPowerStored;
    private float lastScaledPowerIn, lastScaledPowerOut, lastCapacity;
    /** Diodes' transfers since the last solve, for the statistics ({@code energyDelta}). */
    private float energyDelta;
    /** {@code powerBalance}, Arc's {@code WindowedMean}: its values, how many were added, and the next slot. */
    private float[] balance = new float[BALANCE_WINDOW];
    private int balanceAdded, balanceLast;

    /** A blank graph, for copies and checkpoints. Use {@link #create(World)}. */
    public PowerGraph(){
    }

    /** {@code new PowerGraph()}: an empty graph whose updater has just drawn its entity id, not yet added. */
    public static PowerGraph create(World world){
        var graph = new PowerGraph();
        graph.created(world);
        return graph;
    }

    /**
     * {@code new PowerGraph(true)}: a graph without an updater, which draws no id and never joins the group: the one a
     * payload unit solves its carried buildings with ({@code PayloadComp.update}). Its id is -1.
     */
    public static PowerGraph createWithoutUpdater(World world){
        var graph = new PowerGraph();
        graph.created(world, -1);
        return graph;
    }

    /** Whether this graph has an updater: every graph but a payload unit's ({@link #createWithoutUpdater}). */
    public boolean hasUpdater(){
        return id() != -1;
    }

    // statistics

    public float getLastScaledPowerIn(){
        return lastScaledPowerIn;
    }

    public float getLastScaledPowerOut(){
        return lastScaledPowerOut;
    }

    public float getLastCapacity(){
        return lastCapacity;
    }

    public float getLastPowerNeeded(){
        return lastPowerNeeded;
    }

    public float getLastPowerProduced(){
        return lastPowerProduced;
    }

    public float getLastPowerStored(){
        return lastPowerStored;
    }

    /** What diodes moved in or out since the last solve. */
    public float getEnergyDelta(){
        return energyDelta;
    }

    /** {@code getPowerBalance()}: the window's {@code rawMean()}. */
    public float getPowerBalance(){
        if(balanceAdded >= BALANCE_WINDOW){
            float mean = 0;
            for(float value : balance) mean += value;
            return mean / BALANCE_WINDOW;
        }
        if(balanceAdded == 0) return 0;
        float sum = 0f;
        for(int i = 0; i < balanceLast; i++) sum += balance[i];
        return sum / balanceAdded;
    }

    public boolean hasPowerBalanceSamples(){
        return balanceAdded >= BALANCE_WINDOW;
    }

    /** {@code getSatisfaction()}: the last solve's production over demand, 0 without production. */
    public float getSatisfaction(){
        if(Mathf.zero(lastPowerProduced)) return 0f;
        if(Mathf.zero(lastPowerNeeded)) return 1f;
        return Mathf.clamp(lastPowerProduced / lastPowerNeeded);
    }

    // members

    /** How many buildings joined this graph ({@code all.size}). */
    public int size(){
        return allCount;
    }

    /** Member {@code i}, in join order. */
    public Building member(int i){
        Objects.checkIndex(i, allCount);
        return all[i];
    }

    public int producerCount(){
        return producerCount;
    }

    public Building producer(int i){
        Objects.checkIndex(i, producerCount);
        return producers[i];
    }

    public int consumerCount(){
        return consumerCount;
    }

    public Building consumer(int i){
        Objects.checkIndex(i, consumerCount);
        return consumers[i];
    }

    public int batteryCount(){
        return batteryCount;
    }

    public Building battery(int i){
        Objects.checkIndex(i, batteryCount);
        return batteries[i];
    }

    // the solve (PowerGraph.java:77-257)

    /** {@code transferPower(amount)}: a diode's charge or discharge, outside the solve. */
    public void transferPower(float amount){
        if(amount > 0){
            chargeBatteries(amount);
        }else{
            useBatteries(-amount);
        }
        energyDelta += amount;
    }

    public float getPowerProduced(){
        float powerProduced = 0f;
        for(int i = 0; i < producerCount; i++){
            Building producer = producers[i];
            powerProduced += producer.getPowerProduction() * producer.delta();
        }
        return powerProduced;
    }

    public float getPowerNeeded(){
        float powerNeeded = 0f;
        for(int i = 0; i < consumerCount; i++){
            Building consumer = consumers[i];
            ConsumePower consumePower = consumer.info().consumers.power();
            if(consumer.shouldConsumePower){
                powerNeeded += consumePower.requestedPower(consumer) * consumer.delta();
            }
        }
        return powerNeeded;
    }

    public float getBatteryStored(){
        float totalAccumulator = 0f;
        for(int i = 0; i < batteryCount; i++){
            Building battery = batteries[i];
            if(battery.enabled) totalAccumulator += battery.power.status * battery.info().consumers.power().capacity;
        }
        return totalAccumulator;
    }

    public float getBatteryCapacity(){
        float totalCapacity = 0f;
        for(int i = 0; i < batteryCount; i++){
            Building battery = batteries[i];
            if(battery.enabled) totalCapacity += (1f - battery.power.status) * battery.info().consumers.power().capacity;
        }
        return totalCapacity;
    }

    public float getTotalBatteryCapacity(){
        float totalCapacity = 0f;
        for(int i = 0; i < batteryCount; i++){
            Building battery = batteries[i];
            if(battery.enabled) totalCapacity += battery.info().consumers.power().capacity;
        }
        return totalCapacity;
    }

    public float useBatteries(float needed){
        float stored = getBatteryStored();
        if(Mathf.equal(stored, 0f)) return 0f;
        float used = Math.min(stored, needed);
        float consumedPowerPercentage = Math.min(1.0f, needed / stored);
        for(int i = 0; i < batteryCount; i++){
            Building battery = batteries[i];
            if(battery.enabled) battery.power.status *= (1f - consumedPowerPercentage);
        }
        return used;
    }

    public float chargeBatteries(float excess){
        float capacity = getBatteryCapacity();
        // how much of the missing in each battery % is charged
        float chargedPercent = Math.min(excess / capacity, 1f);
        if(Mathf.equal(capacity, 0f)) return 0f;
        for(int i = 0; i < batteryCount; i++){
            Building battery = batteries[i];
            if(battery.enabled && battery.info().consumers.power().capacity > 0f){
                battery.power.status += (1f - battery.power.status) * chargedPercent;
            }
        }
        return Math.min(excess, capacity);
    }

    public void distributePower(float needed, float produced, boolean charged){
        // distribute even if not needed: some might be requiring power but not using it, which updates consumers
        float coverage = Mathf.zero(needed) && Mathf.zero(produced) && !charged && Mathf.zero(lastPowerStored) ? 0f
            : Mathf.zero(needed) ? 1f : Math.min(1, produced / needed);
        for(int i = 0; i < consumerCount; i++){
            Building consumer = consumers[i];
            ConsumePower cons = consumer.info().consumers.power();
            if(cons.buffered){
                if(!Mathf.zero(cons.capacity)){
                    // an equal percentage of power to all buffers, by the graph's coverage
                    float maximumRate = cons.requestedPower(consumer) * coverage * consumer.delta();
                    consumer.power.status = Mathf.clamp(consumer.power.status + maximumRate / cons.capacity);
                }
            }else if(consumer.shouldConsumePower){
                consumer.power.status = coverage;
            }else{
                // invalid consumers get an estimate, if they were to activate
                consumer.power.status = Math.min(1, produced / (needed + cons.usage * consumer.delta()));
                if(Float.isNaN(consumer.power.status)) consumer.power.status = 0f;
            }
        }
    }

    /** {@code update()}: one solve, which the {@code POWER} phase runs for every added graph. */
    @Override
    public void update(){
        if(consumerCount > 0 && consumers[0].cheating()){
            // when cheating, just set status to 1
            for(int i = 0; i < consumerCount; i++) consumers[i].power.status = 1f;
            lastPowerNeeded = lastPowerProduced = 1f;
            return;
        }

        float powerNeeded = getPowerNeeded();
        float powerProduced = getPowerProduced();

        lastPowerNeeded = powerNeeded;
        lastPowerProduced = powerProduced;

        lastScaledPowerIn = powerProduced + energyDelta;
        lastScaledPowerOut = powerNeeded;
        lastCapacity = getTotalBatteryCapacity();
        lastPowerStored = getBatteryStored();

        addBalance(lastPowerProduced - lastPowerNeeded + energyDelta);
        energyDelta = 0f;

        if(!(consumerCount == 0 && producerCount == 0 && batteryCount == 0)){
            boolean charged = false;
            if(!Mathf.equal(powerNeeded, powerProduced)){
                if(powerNeeded > powerProduced){
                    float powerBatteryUsed = useBatteries(powerNeeded - powerProduced);
                    powerProduced += powerBatteryUsed;
                    lastPowerProduced += powerBatteryUsed;
                }else if(powerProduced > powerNeeded){
                    charged = true;
                    powerProduced -= chargeBatteries(powerProduced - powerNeeded);
                }
            }
            distributePower(powerNeeded, powerProduced, charged);
        }
    }

    /** {@code WindowedMean.add}. */
    private void addBalance(float value){
        if(balanceAdded < BALANCE_WINDOW) balanceAdded++;
        balance[balanceLast++] = value;
        if(balanceLast > BALANCE_WINDOW - 1) balanceLast = 0;
    }

    // topology (PowerGraph.java:259-377)

    /** {@code addGraph(graph)}: merges the smaller of the two into the larger, whose updater stays. */
    public void addGraph(PowerGraph graph){
        if(graph == this) return;
        // merge into the other graph instead
        if(graph.allCount > allCount){
            graph.addGraph(this);
            return;
        }
        // the other updater leaves, as its graph is merged
        graph.remove();
        for(int i = 0; i < graph.allCount; i++) add(graph.all[i]);
        checkAdd();
    }

    /**
     * {@code add(build)}: joins the building, unless it is already an initialized member, and files it by its block:
     * producer and consumer, battery, producer or consumer. Its old graph, which must be invalid, leaves the group.
     */
    public void add(Building build){
        if(build == null || build.power == null) return;
        PowerModule power = build.power;
        if(power.graph == this && power.init) return;
        if(power.graph != null && power.graph != this) power.graph.remove();
        power.graph = this;
        power.init = true;
        all = append(all, allCount++, build);
        BlockInfo info = build.info();
        ConsumePower consPower = info.consumers.power();
        if(info.outputsPower && info.consumesPower && !buffered(build, consPower)){
            producers = append(producers, producerCount++, build);
            consumers = append(consumers, consumerCount++, build);
        }else if(info.outputsPower && info.consumesPower){
            batteries = append(batteries, batteryCount++, build);
        }else if(info.outputsPower){
            producers = append(producers, producerCount++, build);
        }else if(info.consumesPower && consPower != null){
            consumers = append(consumers, consumerCount++, build);
        }
    }

    /** {@code block.consPower.buffered}, which vanilla reads without a null check. */
    private static boolean buffered(Building build, ConsumePower consPower){
        if(consPower == null){
            throw new IllegalStateException(build.block().name() + " outputs and consumes power without a power consumer");
        }
        return consPower.buffered;
    }

    /** {@code checkAdd()}: the updater joins the group, if it isn't in it. */
    public void checkAdd(){
        add();
    }

    /** {@code clear()}: forgets every member; nothing is left, so the updater leaves. */
    public void clear(){
        Arrays.fill(all, 0, allCount, null);
        Arrays.fill(producers, 0, producerCount, null);
        Arrays.fill(consumers, 0, consumerCount, null);
        Arrays.fill(batteries, 0, batteryCount, null);
        allCount = producerCount = consumerCount = batteryCount = 0;
        remove();
    }

    /**
     * {@code reflow(tile)}: joins everything connected to {@code tile}, breadth first. Vanilla quirk kept: the start is
     * never closed, so a neighbour's connections queue it once more.
     */
    public void reflow(Building tile){
        var queue = new ArrayDeque<Building>();
        var out = new ArrayList<Building>();
        var closed = new IntSet();
        queue.addLast(tile);
        while(!queue.isEmpty()){
            Building child = queue.removeFirst();
            add(child);
            checkAdd();
            for(Building next : connections(child, out)){
                if(closed.add(next.pos())) queue.addLast(next);
            }
        }
    }

    /**
     * {@code remove(tile)}: {@code tile} is about to leave. Each of its connections still in this graph starts a new
     * graph of everything it reaches without {@code tile}, which updates once so consumers cut off from producers lose
     * their power. This graph is left empty of an updater: its lists stay as they were.
     */
    public void remove(Building tile){
        for(Building other : connections(tile, new ArrayList<>())){
            // a graph was assigned to this one by an earlier branch: skip it
            if(other.power.graph != this) continue;
            PowerGraph graph = create(world());
            graph.checkAdd();
            graph.add(other);
            var queue = new ArrayDeque<Building>();
            var out = new ArrayList<Building>();
            queue.addLast(other);
            while(!queue.isEmpty()){
                Building child = queue.removeFirst();
                graph.add(child);
                for(Building next : connections(child, out)){
                    // not looping back to the removed tile, nor to what this branch already took
                    if(next != tile && next.power.graph != graph){
                        graph.add(next);
                        queue.addLast(next);
                    }
                }
            }
            graph.update();
        }
        remove();
    }

    private static Building[] append(Building[] items, int size, Building build){
        if(size == items.length) items = Arrays.copyOf(items, Math.max(16, (int)(size * 1.75f)));
        items[size] = build;
        return items;
    }

    // the building side (BuildingComp.java:1145-1205, 1796-1833)

    /**
     * {@code getPowerConnections(out)}: the same-team neighbours power flows to (both conduct, and not two plain
     * consumers unless one is conductive, and not already a laser link), then the laser links standing on a
     * same-team building, in link order. Fills and returns {@code out}.
     */
    public static List<Building> connections(Building building, List<Building> out){
        out.clear();
        PowerModule power = building.power;
        if(power == null) return out;
        BlockInfo info = building.info();
        for(int i = 0; i < building.proximitySize(); i++){
            Building other = building.proximity(i);
            if(other.power == null || other.team != building.team) continue;
            BlockInfo next = other.info();
            if(info.consumesPower && next.consumesPower && !info.outputsPower && !next.outputsPower && !info.conductivePower
                && !next.conductivePower) continue;
            if(building.conductsTo(other) && other.conductsTo(building) && !power.links.contains(other.pos())) out.add(other);
        }
        for(int i = 0; i < power.links.size; i++){
            Building link = at(building.world(), power.links.get(i));
            if(link != null && link.power != null && link.team == building.team) out.add(link);
        }
        return out;
    }

    /** {@code updatePowerGraph()}: merges this building's graph with every graph it connects to. */
    public static void updatePowerGraph(Building building){
        for(Building other : connections(building, new ArrayList<>())){
            if(other.power != null) other.power.graph.addGraph(building.power.graph);
        }
    }

    /**
     * {@code powerGraphRemoved()}: the building is leaving. Its graph splits around it, and every building it links to
     * forgets the link; its own links are cleared.
     */
    public static void powerGraphRemoved(Building building){
        PowerModule power = building.power;
        if(power == null) return;
        power.graph.remove(building);
        for(int i = 0; i < power.links.size; i++){
            Building other = at(building.world(), power.links.get(i));
            if(other != null && other.power != null) other.power.links.removeValue(building.pos());
        }
        power.links.clear();
    }

    /**
     * The power half of {@code changeTeam(next)} (BuildingComp.java:1810), once the team is set and before proximity
     * is rebuilt: the old-team neighbours and links still in the old graph reflow into new graphs, links to them are
     * cut, and the building reflows into a new graph and merges with what it connects to now.
     */
    public static void teamChanged(Building building){
        PowerModule power = building.power;
        if(power == null) return;
        PowerGraph oldGraph = power.graph;
        World world = building.world();
        for(int i = 0; i < building.proximitySize(); i++){
            Building other = building.proximity(i);
            if(other.team != building.team && other.power != null && other.power.graph == oldGraph) create(world).reflow(other);
        }
        for(int i = 0; i < power.links.size; i++){
            Building other = at(world, power.links.items[i]);
            // only links still in the old graph reflow; ones in a new graph were covered already
            if(other != null && other.team != building.team && other.power != null && other.power.graph == oldGraph){
                power.links.removeIndex(i);
                other.power.links.removeValue(building.pos());
                create(world).reflow(other);
                i--;
            }
        }
        create(world).reflow(building);
        updatePowerGraph(building);
    }

    /** {@code world.build(pos)} for a packed position: the building covering it, or null, also outside the map. */
    static Building at(World world, int pos){
        return world.buildings().at(Point2.x(pos), Point2.y(pos));
    }

    // entity

    /** The updater joins {@code Groups.powerGraph}; a graph without one has nothing to add. */
    @Override
    public void add(){
        if(isAdded() || !hasUpdater()) return;
        PowerGraphs graphs = PowerGraphs.of(world());
        if(graphs != null) graphs.list().add(this);
        added(true);
    }

    /** The updater leaves {@code Groups.powerGraph}; the last graph there takes its place. */
    @Override
    public void remove(){
        if(!isAdded()) return;
        PowerGraphs graphs = PowerGraphs.of(world());
        if(graphs != null) graphs.list().remove(this);
        added(false);
    }

    @Override
    public Copyable blank(){
        return new PowerGraph();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PowerGraph)into;
        out.groupIndex = groupIndex;
        out.producers = map(producers, producerCount, context);
        out.consumers = map(consumers, consumerCount, context);
        out.batteries = map(batteries, batteryCount, context);
        out.all = map(all, allCount, context);
        out.producerCount = producerCount;
        out.consumerCount = consumerCount;
        out.batteryCount = batteryCount;
        out.allCount = allCount;
        out.lastPowerProduced = lastPowerProduced;
        out.lastPowerNeeded = lastPowerNeeded;
        out.lastPowerStored = lastPowerStored;
        out.lastScaledPowerIn = lastScaledPowerIn;
        out.lastScaledPowerOut = lastScaledPowerOut;
        out.lastCapacity = lastCapacity;
        out.energyDelta = energyDelta;
        out.balance = balance.clone();
        out.balanceAdded = balanceAdded;
        out.balanceLast = balanceLast;
    }

    private static Building[] map(Building[] items, int size, CopyContext context){
        if(items.length == 0) return NONE;
        var out = new Building[items.length];
        for(int i = 0; i < size; i++) out[i] = context.map(items[i]);
        return out;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hashMembers(hasher, all, allCount);
        hashMembers(hasher, producers, producerCount);
        hashMembers(hasher, consumers, consumerCount);
        hashMembers(hasher, batteries, batteryCount);
        hasher.f(lastPowerProduced).f(lastPowerNeeded).f(lastPowerStored).f(lastScaledPowerIn).f(lastScaledPowerOut)
            .f(lastCapacity).f(energyDelta).floats(balance).i(balanceAdded).i(balanceLast);
    }

    private static void hashMembers(Hasher hasher, Building[] items, int size){
        hasher.i(size);
        for(int i = 0; i < size; i++) hasher.i(items[i].id());
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("producers", producers, producerCount).refs("consumers", consumers, consumerCount)
            .refs("batteries", batteries, batteryCount).refs("all", all, allCount)
            .i("producerCount", producerCount).i("consumerCount", consumerCount).i("batteryCount", batteryCount).i("allCount", allCount)
            .f("lastPowerProduced", lastPowerProduced).f("lastPowerNeeded", lastPowerNeeded).f("lastPowerStored", lastPowerStored)
            .f("lastScaledPowerIn", lastScaledPowerIn).f("lastScaledPowerOut", lastScaledPowerOut).f("lastCapacity", lastCapacity)
            .f("energyDelta", energyDelta).floats("balance", balance).i("balanceAdded", balanceAdded).i("balanceLast", balanceLast);
    }

    @Override
    public void read(CheckpointIn in){
        producers = members(in, "producers");
        consumers = members(in, "consumers");
        batteries = members(in, "batteries");
        all = members(in, "all");
        producerCount = count(in, "producerCount", producers);
        consumerCount = count(in, "consumerCount", consumers);
        batteryCount = count(in, "batteryCount", batteries);
        allCount = count(in, "allCount", all);
        lastPowerProduced = in.f("lastPowerProduced");
        lastPowerNeeded = in.f("lastPowerNeeded");
        lastPowerStored = in.f("lastPowerStored");
        lastScaledPowerIn = in.f("lastScaledPowerIn");
        lastScaledPowerOut = in.f("lastScaledPowerOut");
        lastCapacity = in.f("lastCapacity");
        energyDelta = in.f("energyDelta");
        balance = in.floats("balance");
        balanceAdded = in.i("balanceAdded");
        balanceLast = in.i("balanceLast");
        if(balance == null || balance.length != BALANCE_WINDOW) throw new CheckpointException(this + "'s power balance window is corrupt");
    }

    private Building[] members(CheckpointIn in, String name){
        Building[] stored = in.refs(name, Building.class);
        if(stored == null || Arrays.asList(stored).contains(null)) throw new CheckpointException(this + "'s " + name + " hold a missing building");
        return stored.length == 0 ? NONE : stored;
    }

    private int count(CheckpointIn in, String name, Building[] items){
        int count = in.i(name);
        if(count != items.length) throw new CheckpointException(this + "'s " + name + " is " + count + " for " + items.length + " members");
        return count;
    }

    @Override
    public String toString(){
        return "PowerGraph#" + id() + "[" + allCount + " members]";
    }
}
