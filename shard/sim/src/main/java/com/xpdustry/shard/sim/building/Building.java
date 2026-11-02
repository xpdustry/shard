package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The base of every building (vanilla {@code BuildingComp}, ARCHITECTURE.md §3 "Objects"). It holds the common state:
 * the typed {@link BlockDef} from the world's pinned content, team, origin tile, rotation, health, efficiency,
 * timeScale, enabled, the item, liquid and power modules (allocated only for blocks that have them), and implements
 * vanilla's simulation surface over them: consumers and efficiency ({@link #updateConsumption()}), item and liquid
 * movement ({@link #dump}, {@link #offload}, {@link #moveForward}, {@link #dumpLiquid}, {@link #moveLiquid}), damage
 * and death ({@link #damage(float)}, {@link #killed()}), config dispatch ({@link #configured}) and logic control.
 *
 * <p>Each behaviour kind is one subclass, registered with {@link Kind}: {@code @Kind("conveyor") public final class
 * Conveyor extends Building}. Kinds compose helpers rather than inherit from each other, read their parameters from
 * their definition, override {@link #updateTile()} and vanilla's other hooks, and copy and hash their own fields by
 * overriding {@link #copyState(Entity, CopyContext)} and {@link #hashState(Hasher)}. The package
 * {@code com.xpdustry.shard.sim.blocks} has the porting guide.
 *
 * <p>Lifecycle, as vanilla: {@link Buildings#create} binds a building to its tiles and, for blocks with
 * {@code update}, {@link #add()}s it to the world's update list, then runs {@code checkAllowUpdate} and
 * {@link #created()}. {@link #sleep()} leaves that list after 60 idle ticks and {@link #noSleep()} rejoins it at the
 * end, so a sleeping building is not {@link #isAdded()}; {@link #isValid()} is what says it still stands. Death goes
 * through {@link Buildings#kill}, which publishes {@link BuildingEvent.Destroyed} where vanilla sends
 * {@code buildDestroyed}.
 *
 * <p>Proximity, as vanilla: the same-team buildings touching this one's edges, kept by {@link Buildings}'
 * placement primitives with vanilla's {@link #onProximityAdded()}, {@link #onProximityUpdate()},
 * {@link #onProximityRemoved()} and {@link #onRemoved()} callbacks. Its order is history: rebuilt in edge order, then
 * neighbours placed later append. Vanilla rebuilds it in the iteration order of an identity-hashed set, which no
 * two JVMs agree on; Shard uses first-seen edge order (see {@link Buildings}).
 *
 * <p>Ids are vanilla's {@code int} content ids: items and liquids by id, -1 for none (vanilla's {@code null}).
 *
 * <p>Owner: WP20.
 */
@Registry
public abstract class Building extends Entity implements UnitContact{
    /** Vanilla's {@code timeToSleep}. */
    public static final float TIME_TO_SLEEP = 60f;
    /** Vanilla's {@code recentDamageTime}. */
    public static final float RECENT_DAMAGE_TIME = 60f * 5f;
    private static final Building[] NO_BUILDINGS = {};
    private static final float[] NO_TIMERS = {};
    /**
     * The coordinates of vanilla's {@code emptyTile} ({@code new Tile(Short.MAX_VALUE - 20, Short.MAX_VALUE - 20)},
     * Vars.java:388): where a building off the map ({@link #isPayload()}) is, as far as {@link #tileX()} knows.
     */
    public static final int EMPTY_TILE = Short.MAX_VALUE - 20;

    static final ActiveList.Slot<Building> UPDATE_SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(Building building){
            return building.updateIndex;
        }

        @Override
        public void set(Building building, int position){
            building.updateIndex = position;
        }
    };

    /** The slot accessor for {@link World#builds()}. */
    public static ActiveList.Slot<Building> updateSlot(){
        return UPDATE_SLOT;
    }

    private BlockDef block;
    private int tile = -1;
    private int index = -1;
    private int updateIndex = -1;
    private boolean sleeping;
    private float sleepTime;
    private Building[] proximity = NO_BUILDINGS;
    private int proximitySize;
    /**
     * Vanilla's {@code x} and {@code y} once {@code set(x, y)} moved the building off its tile: a payload's position
     * ({@link #set}). NaN while the building stands where its tile says.
     */
    private float payloadX = Float.NaN, payloadY = Float.NaN;

    public int team;
    public int rotation;
    public float health, maxHealth;
    public boolean dead;
    /** Base efficiency: the minimum of the required consumers'. */
    public float efficiency;
    /** The same over the optional consumers. */
    public float optionalEfficiency;
    /** What {@link #efficiency} would be if {@link #shouldConsume()} held. */
    public float potentialEfficiency;
    /** Whether any consumer other than power has efficiency above zero: what power consumption reads. */
    public boolean shouldConsumePower;
    public float timeScale = 1f, timeScaleDuration;
    public boolean enabled = true;
    /** The processor that last disabled this building through {@code control enabled}, or null. */
    public Building lastDisabler;
    /** Vanilla's {@code visibleFlags}: one bit per team that has seen this building in fog. Saved, not simulated. */
    public long visibleFlags;
    /** The proximity index the next dump starts at. */
    public int cdump;
    public float dumpAccum;
    public float lastDamageTime = -RECENT_DAMAGE_TIME;
    public float healSuppressionTime = -1f;
    public float lastHealTime = -120f * 10f;
    /** The indexer's: whether this building was damaged when it last heard of its health. */
    public boolean wasDamaged;
    /** Vanilla's {@code Interval} times, {@code block.timers} of them, in {@link Timers#time()}. */
    public float[] timer = NO_TIMERS;
    public ItemModule items;
    public LiquidModule liquids;
    public PowerModule power;

    /**
     * Vanilla's construction and {@code create(block, team)}: binds a new building to its world, block and team, and
     * allocates its modules. Draws are vanilla's, in its order: the building's entity id, its field initializers'
     * ({@link #constructed()}), then its power graph updater's id (PowerModule.java:15, PowerGraph.java:31); the new
     * graph takes the building at once (BuildingComp.java:155) and its updater joins the group when the building is
     * {@link #add()}ed.
     */
    final void create(World world, BlockDef block, int team, int tile, int index, int rotation){
        created(world);
        this.block = block;
        BlockInfo info = world.buildings().info(block);
        this.team = team;
        this.tile = tile;
        this.index = index;
        this.rotation = rotation;
        health = maxHealth = block.health();
        timer = info.timers == 0 ? NO_TIMERS : new float[info.timers];
        constructed();
        if(block.hasItems()) items = new ItemModule(world.content().items());
        if(block.hasLiquids()) liquids = new LiquidModule(world.content().liquids());
        if(block.hasPower()){
            power = new PowerModule();
            power.graph = PowerGraph.create(world);
            power.graph.add(this);
        }
    }

    /**
     * Vanilla's field initializers and constructor body, once the building has its id and block: what they draw from
     * the world's generator and entity ids. The base makes {@link #drawFields}' draws and drops the values; a kind
     * ported with such fields overrides this and keeps them, drawing in vanilla's field order.
     */
    protected void constructed(){
        drawFields(world(), block, true);
    }

    /** {@code UnitType.create}'s draws: {@code resupplyTime} (UnitComp.java:66), then an AI's two timers (AIController.java:40). */
    private static final int BLOCK_UNIT_DRAWS = 3;

    /**
     * What constructing a building of {@code block} draws between its own id and its modules. A turret or build turret
     * makes its block unit (Turret.java:297, BuildTurret.java:93): an entity id, then three generator values. A kind with
     * a drawn field takes one value: a mend or overdrive projector's {@code charge} (MendProjector.java:85,
     * OverdriveProjector.java:84), a shockwave tower's {@code reloadCounter} (ShockwaveTower.java:67), a landing pad's
     * {@code priority} (LandingPad.java:139), an auto door's staggered toggle timer (AutoDoor.java:55) or a repair
     * tower's {@code refresh} (RepairTower.java:51). Without {@code values}, only the ids.
     */
    public static void drawFields(World world, BlockDef block, boolean values){
        if(ownsBlockUnit(block)){
            world.ids().next();
            if(values) for(int i = 0; i < BLOCK_UNIT_DRAWS; i++) world.rand().nextLong();
        }
        if(values && world.buildings().info(block).drawsField) world.rand().nextLong();
    }

    /**
     * Every draw of vanilla's {@code block.newBuilding().create(block, team)}, for a building Shard doesn't make: one
     * inside a payload it keeps as bytes. Its {@code readAll} may draw more. Without {@code values}, only the ids.
     */
    public static void drawConstruction(World world, BlockDef block, boolean values){
        world.ids().next();
        drawFields(world, block, values);
        if(block.hasPower()) world.ids().next();
    }

    /**
     * Whether constructing a building of {@code block} constructs a block unit: turrets and build turrets. Content's
     * {@link BlockDef#ownsBlockUnit()} misses the second.
     */
    public static boolean ownsBlockUnit(BlockDef block){
        return block.ownsBlockUnit() || block.baked() && block.isA(KindKey.of("build-turret"));
    }

    /** Called by {@link Buildings} once the tiles no longer point here. */
    final void unbound(){
        index = -1;
    }

    /**
     * {@code Building.init(tile, team, shouldAdd, rotation)} for an initialized building (BuildingComp.java:112): a
     * payload taking a tile again. A powered one starts a new graph, whose updater draws its id; then it forgets its
     * proximity and takes the tile, its registry index and the rotation, standing where the tile is.
     */
    final void rebound(int tile, int index, int rotation){
        if(power != null){
            power.init = false;
            // reinit power graph
            PowerGraph.create(world()).add(this);
        }
        proximityClear();
        this.rotation = rotation;
        this.tile = tile;
        this.index = index;
        payloadX = payloadY = Float.NaN;
    }

    public final BlockDef block(){
        return block;
    }

    /** What the base reads from the block's definition, shared by the world's buildings of that block. */
    public final BlockInfo info(){
        return world().buildings().info(block);
    }

    /** The origin tile, {@code y * width + x}. */
    public final int tile(){
        return tile;
    }

    /** The origin's x; {@link #EMPTY_TILE} for a building on vanilla's {@code emptyTile} ({@link #isPayload()}). */
    public final int tileX(){
        return tile == -1 ? EMPTY_TILE : world().tiles().x(tile);
    }

    public final int tileY(){
        return tile == -1 ? EMPTY_TILE : world().tiles().y(tile);
    }

    /** {@code pos()}: the origin as {@link Point2#pack}, how vanilla refers to a building on the wire and in saves. */
    public final int pos(){
        return Point2.pack(tileX(), tileY());
    }

    /** World x of the centre ({@code tile.drawx()}), or where a payload holder last put it ({@link #set}). */
    public final float x(){
        return !Float.isNaN(payloadX) ? payloadX : tileX() * Footprint.TILE_SIZE + Footprint.worldOffset(block.size());
    }

    /** World y of the centre. */
    public final float y(){
        return !Float.isNaN(payloadY) ? payloadY : tileY() * Footprint.TILE_SIZE + Footprint.worldOffset(block.size());
    }

    /**
     * {@code set(x, y)}: moves the building's position, which only a payload holder does (a unit or a payload block
     * carrying it, {@code Payload.set}). Placing it on a tile again puts it back where the tile is.
     */
    public final void set(float x, float y){
        payloadX = x;
        payloadY = y;
    }

    /** This building's index in the world's building registry, or -1 once removed. */
    public final int index(){
        return index;
    }

    /** The world's rules. */
    public final Rules rules(){
        return world().buildings().rules();
    }

    /** {@code cheating()}: the team's {@code cheat} rule. Vanilla quirk: reading it creates the team's rules entry. */
    public final boolean cheating(){
        return rules().teams.get(team).cheat;
    }

    /** Vanilla's {@code isValid}: still the building on its tile, and not dead. */
    public final boolean isValid(){
        return tile != -1 && world().buildings().at(tile) == this && !dead;
    }

    /**
     * {@code isPayload()} (BuildingComp.java:1387): whether this building's tile is vanilla's {@code emptyTile}, which
     * a block payload's read ({@code Payload.read}) and a payload block's update ({@code BuildPayload.update}) give it.
     * A building a unit picked up keeps its old tile until then, as in vanilla.
     */
    public boolean isPayload(){
        return tile == -1;
    }

    /** {@code build.tile = emptyTile} (BuildPayload.java:20, :62): the building is off the map. */
    public final void toEmptyTile(){
        tile = -1;
    }

    /** Vanilla's {@code update()}, called by the update list in vanilla order. Δ is always 1. */
    public final void update(){
        if((timeScaleDuration -= 1f) <= 0f){
            timeScale = 1f;
        }
        updateConsumption();
        if(enabled || !block.noUpdateDisabled()){
            updateTile();
        }
    }

    /** The kind's behaviour, once per tick while this building is in the update list. */
    public void updateTile(){
    }

    /** Vanilla's {@code created()}: once, after the building is on its tiles and in the update list. */
    public void created(){
    }

    // consumers

    /** BuildingComp.java:1950. A kind that simulates nothing (a passthrough) overrides it to do nothing. */
    public void updateConsumption(){
        Consumers consumers = info().consumers;
        // everything is valid when cheating
        if(!consumers.any() || cheating()){
            potentialEfficiency = enabled && productionValid() ? 1f : 0f;
            efficiency = optionalEfficiency = shouldConsume() ? potentialEfficiency : 0f;
            shouldConsumePower = true;
            updateEfficiencyMultiplier();
            return;
        }
        // disabled: nothing works
        if(!enabled){
            potentialEfficiency = efficiency = optionalEfficiency = 0f;
            shouldConsumePower = false;
            return;
        }
        boolean update = shouldConsume() && productionValid();
        float minEfficiency = 1f;
        efficiency = optionalEfficiency = 1f;
        shouldConsumePower = true;
        for(Consume cons : consumers.nonOptional()){
            float result = cons.efficiency(this);
            if(cons != consumers.power() && result <= 0.0000001f) shouldConsumePower = false;
            minEfficiency = Math.min(minEfficiency, result);
        }
        for(Consume cons : consumers.optional()){
            optionalEfficiency = Math.min(optionalEfficiency, cons.efficiency(this));
        }
        efficiency = minEfficiency;
        optionalEfficiency = Math.min(optionalEfficiency, minEfficiency);
        potentialEfficiency = efficiency;
        if(!update) efficiency = optionalEfficiency = 0f;
        updateEfficiencyMultiplier();
        if(update && efficiency > 0){
            for(Consume cons : consumers.update()) cons.update(this);
        }
    }

    /** {@code updateEfficiencyMultiplier()}: applies {@link #efficiencyScale()} after the consumers are read. */
    public void updateEfficiencyMultiplier(){
        float scale = efficiencyScale();
        efficiency *= scale;
        optionalEfficiency *= scale;
    }

    /** {@code efficiencyScale()}: a kind's own efficiency multiplier. */
    public float efficiencyScale(){
        return 1f;
    }

    /** {@code consume()}: triggers every consumer, taking what one cycle needs. */
    public void consume(){
        for(Consume cons : info().consumers.all()) cons.trigger(this);
    }

    public boolean canConsume(){
        return potentialEfficiency > 0;
    }

    /** Whether the building is active and should be consuming. */
    public boolean shouldConsume(){
        return enabled;
    }

    public boolean productionValid(){
        return true;
    }

    /** Whether a trigger consumer is burning (an item): item consumers count as satisfied meanwhile. */
    public boolean consumeTriggerValid(){
        return false;
    }

    /** {@code delta()}: Δ (1) scaled by {@link #timeScale}. */
    public float delta(){
        return timeScale;
    }

    /** {@code edelta()}: {@link #efficiency} times {@link #delta()}. */
    public float edelta(){
        return efficiency * delta();
    }

    /** {@code getProgressIncrease(baseTime)}. */
    public float getProgressIncrease(float baseTime){
        return 1f / baseTime * edelta();
    }

    /** A consumer's dynamic multiplier hook ({@code Consume.multiplier}), for kinds that set one; 1 by default. */
    public float consumeMultiplier(Consume consume){
        return 1f;
    }

    /** What a {@code ConsumeItemDynamic} takes now ({@link ConsumeItems#hook} names the kind's lambda). */
    public Stacks consumedItems(){
        throw unported("dynamic item consumption");
    }

    /** What a {@code ConsumeLiquidsDynamic} takes per tick now. */
    public Stacks consumedLiquids(){
        throw unported("dynamic liquid consumption");
    }

    /** What a {@code ConsumePayloadDynamic} takes now. */
    public List<PayloadStock.Stack> consumedPayloads(){
        throw unported("dynamic payload consumption");
    }

    /** The payloads payload consumers read ({@code getPayloads()}). */
    public PayloadStock payloads(){
        throw unported("payload stock");
    }

    /** {@code ConsumePowerCondition}'s condition. */
    public boolean consumesPowerNow(){
        throw unported("conditional power use");
    }

    /** {@code ConsumePowerDynamic}'s usage per tick. */
    public float powerUse(){
        throw unported("dynamic power use");
    }

    /** {@code getPowerProduction()}: power produced per tick, which the graph sums. */
    public float getPowerProduction(){
        return 0f;
    }

    /** {@code conductsTo(other)}: whether power flows from this building to an adjacent {@code other}. */
    public boolean conductsTo(Building other){
        return !info().insulated;
    }

    /** {@code isInsulated()}: whether it stops laser autolinks and beams. */
    public boolean isInsulated(){
        return info().insulated;
    }

    /**
     * {@code placed()} (BuildingComp.java:1361): a player or unit finished building this one (construction calls it
     * after the tile is set), never on a map load. A powered block that joins graphs links itself to the power nodes
     * in range ({@link PowerNode#nodeLinks}), each node taking it as a laser link through {@link #configureAny}.
     */
    public void placed(){
        BlockInfo info = info();
        if((info.consumesPower || info.outputsPower) && block.hasPower() && info.connectedPower){
            PowerNode.nodeLinks(world(), tile, block, team, other -> {
                if(!other.power.links.contains(pos())) other.configureAny(pos());
            });
        }
    }

    /**
     * {@code overwrote(previous)}: construction finished this building over {@code previous}, the same team's
     * buildings the site covered (removed by now, in footprint order), so it may take over what they held. Kinds that
     * keep items or charge across a replacement override it.
     */
    public void overwrote(List<Building> previous){
    }

    // payloads (BuildingComp.java:702-850, 989-1000, 1845-1863, 2013)

    /** {@code canPickup()}: whether a payload unit may lift this building; the block's {@code allowedInPayloads}. */
    public boolean canPickup(){
        return block.allowedInPayloads();
    }

    /** {@code pickedUp()}: called right before a payload unit lifts this building off its tile. */
    public void pickedUp(){
    }

    /**
     * {@code afterPickedUp()}: called right after the building left its tile. A powered one gets a graph of its own,
     * whose updater draws an entity id and is never added (the carrier's graph takes the building each update), drops
     * its laser links, and an unbuffered consumer loses its power.
     */
    public void afterPickedUp(){
        if(power != null){
            // TODO in vanilla: "can lead to ghost graphs?"
            power.graph = PowerGraph.create(world());
            power.links.clear();
            ConsumePower consPower = info().consumers.power();
            if(consPower != null && !consPower.buffered) power.status = 0f;
        }
    }

    /** {@code dropped()}: called once a payload holder placed this building on a tile again ({@code BuildPayload.place}). */
    public void dropped(){
    }

    /**
     * Vanilla's {@code created()} when {@code Building.init} runs on a building that is already initialized: one a
     * payload holder places on a tile again. Shard's {@link #created()} also stands for field initializers (arrays a kind
     * allocates), which a placed payload keeps, so this is a hook of its own: a kind whose vanilla {@code created()} has
     * a body (the separator's seed, the target dummy's team) runs it here too.
     */
    public void recreated(){
    }

    /** {@code updatePayload(unitHolder, buildingHolder)}: one update while carried; either holder may be null. */
    public void updatePayload(Unit unitHolder, Building buildingHolder){
        update();
    }

    /** {@code payloadCheck(conveyorRotation)}: whether this building faces a payload conveyor pointing at it. */
    public boolean payloadCheck(int conveyorRotation){
        return block.rotate() && (rotation + 2) % 4 == conveyorRotation;
    }

    /** {@code acceptPayload(source, payload)}. */
    public boolean acceptPayload(Building source, Payload payload){
        return false;
    }

    /** {@code handlePayload(source, payload)}. */
    public void handlePayload(Building source, Payload payload){
    }

    /** {@code getPayload()}: the payload this building holds, or null. */
    public Payload getPayload(){
        return null;
    }

    /** {@code takePayload()}: removes and returns the payload this building holds, or null. */
    public Payload takePayload(){
        return null;
    }

    /** {@code movePayload(todump)}: gives the payload to the same-team building in front, if it takes it. */
    public boolean movePayload(Payload todump){
        int trns = block.size() / 2 + 1;
        Building next = nearby(Geometry.d4x(rotation) * trns, Geometry.d4y(rotation) * trns);
        if(next != null && next.team == team && next.acceptPayload(this, todump)){
            next.handlePayload(this, todump);
            return true;
        }
        return false;
    }

    /** {@code dumpPayload(todump)}: gives the payload to the next neighbour that takes it, as {@link #dump} does items. */
    public boolean dumpPayload(Payload todump){
        if(proximitySize == 0) return false;
        int dump = cdump;
        for(int i = 0; i < proximitySize; i++){
            Building other = proximity[(i + dump) % proximitySize];
            if(other.acceptPayload(this, todump)){
                other.handlePayload(this, todump);
                incrementDump(proximitySize);
                return true;
            }
            incrementDump(proximitySize);
        }
        return false;
    }

    /** {@code canControlSelect(unit)}: whether a player's unit may enter this building. */
    public boolean canControlSelect(Unit unit){
        return false;
    }

    /** {@code onControlSelect(unit)}: a player's unit entered this building. */
    public void onControlSelect(Unit unit){
    }

    private UnsupportedOperationException unported(String what){
        return new UnsupportedOperationException("kind " + world().kinds().key(block) + " (" + block.name() + ") has no " + what);
    }

    // status

    /**
     * {@code status()} (BuildingComp.java:638), at vanilla's {@code state.tick}, the game's own tick count, which its
     * owner passes in: the {@code active} blink follows it.
     */
    public BlockStatus status(double stateTick){
        if(!enabled) return BlockStatus.logicDisable;
        if(!shouldConsume()) return BlockStatus.noOutput;
        if(efficiency <= 0 || !productionValid()) return BlockStatus.noInput;
        return ((stateTick / 30f) % 1f) < efficiency ? BlockStatus.active : BlockStatus.noInput;
    }

    /** {@code allowUpdate()} (BuildingComp.java:628). */
    public boolean allowUpdate(){
        return world().buildings().allowUpdate(this);
    }

    /** {@code checkAllowUpdate()}: disables a building that may not update; it never enables one. */
    public final void checkAllowUpdate(){
        if(!allowUpdate()) enabled = false;
    }

    // items

    /** {@code acceptItem}: by default, items a consumer takes, up to {@link #getMaximumAccepted}. */
    public boolean acceptItem(Building source, int item){
        return info().consumesItem(item) && items.get(item) < getMaximumAccepted(item);
    }

    public void handleItem(Building source, int item){
        items.add(item, 1);
    }

    public int getMaximumAccepted(int item){
        return info().itemCapacity;
    }

    /** {@code acceptStack}: how many of {@code amount} this takes from a unit or building of {@code sourceTeam} (-1: none). */
    public int acceptStack(int item, int amount, int sourceTeam){
        if(acceptItem(this, item) && block.hasItems() && (sourceTeam == -1 || sourceTeam == team)){
            return Math.min(getMaximumAccepted(item) - items.get(item), amount);
        }
        return 0;
    }

    /** {@code removeStack}: removes up to {@code amount}, returning how many. */
    public int removeStack(int item, int amount){
        if(items == null) return 0;
        amount = Math.min(amount, items.get(item));
        noSleep();
        items.remove(item, amount);
        return amount;
    }

    public void handleStack(int item, int amount, int sourceTeam){
        noSleep();
        items.add(item, amount);
    }

    /** {@code canDump}: whether dumping may give {@code item} to {@code to}. */
    public boolean canDump(Building to, int item){
        return true;
    }

    /** {@code dump()}: gives any one item to the next neighbour that takes it. */
    public boolean dump(){
        return dump(-1);
    }

    /** {@code dump(item)} (BuildingComp.java:1077): {@code item} -1 dumps any. */
    public boolean dump(int item){
        if(!block.hasItems() || items.total() == 0 || proximitySize == 0 || (item != -1 && !items.has(item))) return false;
        int dump = cdump;
        if(item == -1){
            for(int i = 0; i < proximitySize; i++){
                Building other = proximity[(i + dump) % proximitySize];
                for(int ii = 0; ii < items.length(); ii++){
                    if(!items.has(ii)) continue;
                    if(other.acceptItem(this, ii) && canDump(other, ii)){
                        other.handleItem(this, ii);
                        items.remove(ii, 1);
                        incrementDump(proximitySize);
                        return true;
                    }
                }
                incrementDump(proximitySize);
            }
        }else{
            for(int i = 0; i < proximitySize; i++){
                Building other = proximity[(i + dump) % proximitySize];
                if(other.acceptItem(this, item) && canDump(other, item)){
                    other.handleItem(this, item);
                    items.remove(item, 1);
                    incrementDump(proximitySize);
                    return true;
                }
                incrementDump(proximitySize);
            }
        }
        return false;
    }

    /** {@code dumpAccumulate(item)}: dumps once per accumulated tick of {@link #delta()}; may dump several times. */
    public boolean dumpAccumulate(int item){
        boolean res = false;
        dumpAccum += delta();
        while(dumpAccum >= 1f){
            res |= dump(item);
            dumpAccum -= 1f;
        }
        return res;
    }

    public void incrementDump(int prox){
        // possible if transferring an item changed a block
        if(prox != 0) cdump = (cdump + 1) % prox;
    }

    /** {@code offload(item)}: gives the item to a neighbour, or keeps it. */
    public void offload(int item){
        produced(item, 1);
        int dump = cdump;
        for(int i = 0; i < proximitySize; i++){
            incrementDump(proximitySize);
            Building other = proximity[(i + dump) % proximitySize];
            if(other.acceptItem(this, item) && canDump(other, item)){
                other.handleItem(this, item);
                return;
            }
        }
        handleItem(this, item);
    }

    /** {@code put(item)}: gives the item to a neighbour without touching this inventory. */
    public boolean put(int item){
        int dump = cdump;
        for(int i = 0; i < proximitySize; i++){
            incrementDump(proximitySize);
            Building other = proximity[(i + dump) % proximitySize];
            if(other.acceptItem(this, item) && canDump(other, item)){
                other.handleItem(this, item);
                return true;
            }
        }
        return false;
    }

    /** {@code produced(item, amount)}: campaign production statistics only, which a server doesn't keep. */
    public void produced(int item, int amount){
    }

    /** {@code moveForward(item)}: gives the item to the same-team building in front. */
    public boolean moveForward(int item){
        Building other = front();
        if(other != null && other.team == team && other.acceptItem(this, item)){
            other.handleItem(this, item);
            return true;
        }
        return false;
    }

    public boolean canWithdraw(){
        return true;
    }

    public boolean canUnload(){
        return info().unloadable;
    }

    public boolean canResupply(){
        return info().allowResupply;
    }

    /** Called when an unloader takes an item. */
    public void itemTaken(int item){
    }

    public boolean allowDeposit(){
        return info().alwaysAllowDeposit || !rules().onlyDepositCore;
    }

    /** {@code explosionItemCap()}. */
    public int explosionItemCap(){
        return info().itemCapacity;
    }

    // liquids

    public boolean acceptLiquid(Building source, int liquid){
        return block.hasLiquids() && info().consumesLiquid(liquid);
    }

    public void handleLiquid(Building source, int liquid, float amount){
        liquids.add(liquid, amount);
    }

    public void dumpLiquid(int liquid){
        dumpLiquid(liquid, 2f);
    }

    public void dumpLiquid(int liquid, float scaling){
        dumpLiquid(liquid, scaling, -1);
    }

    /** {@code dumpLiquid} (BuildingComp.java:891): {@code outputDir} relative to rotation, or -1 for any. */
    public void dumpLiquid(int liquid, float scaling, int outputDir){
        int dump = cdump;
        if(liquids.get(liquid) <= 0.0001f) return;
        for(int i = 0; i < proximitySize; i++){
            incrementDump(proximitySize);
            Building other = proximity[(i + dump) % proximitySize];
            if(outputDir != -1 && (outputDir + rotation) % 4 != relativeTo(other)) continue;
            other = other.getLiquidDestination(this, liquid);
            if(other != null && other.block.hasLiquids() && canDumpLiquid(other, liquid) && other.liquids != null){
                float ofract = other.liquids.get(liquid) / other.info().liquidCapacity;
                float fract = liquids.get(liquid) / info().liquidCapacity;
                if(ofract < fract) transferLiquid(other, (fract - ofract) * info().liquidCapacity / scaling, liquid);
            }
        }
    }

    public boolean canDumpLiquid(Building to, int liquid){
        return true;
    }

    public void transferLiquid(Building next, float amount, int liquid){
        float flow = Math.min(next.info().liquidCapacity - next.liquids.get(liquid), amount);
        if(next.acceptLiquid(this, liquid)){
            next.handleLiquid(this, liquid, flow);
            liquids.remove(liquid, flow);
        }
    }

    /** {@code moveLiquidForward(leaks, liquid)}: into the building in front, or leaked as a puddle. */
    public float moveLiquidForward(boolean leaks, int liquid){
        TileGrid tiles = world().tiles();
        int nx = tileX() + Geometry.d4x(rotation), ny = tileY() + Geometry.d4y(rotation);
        if(!tiles.inBounds(nx, ny)) return 0;
        Building next = world().buildings().at(nx, ny);
        if(next != null) return moveLiquid(next, liquid);
        int pos = tiles.pos(nx, ny);
        if(leaks && !tiles.table().solid(tiles.block(pos)) && !world().content().block(tiles.block(pos)).hasLiquids()){
            float leakAmount = liquids.get(liquid) / 1.5f;
            BuildingEffects.of(world()).puddle(world(), pos, tile, liquid, leakAmount, true, true);
            liquids.remove(liquid, leakAmount);
        }
        return 0;
    }

    /** {@code moveLiquid(next, liquid)} (BuildingComp.java:943): returns the amount moved. */
    public float moveLiquid(Building next, int liquid){
        if(next == null) return 0;
        next = next.getLiquidDestination(this, liquid);
        if(next.team == team && next.block.hasLiquids() && liquids.get(liquid) > 0f){
            float ofract = next.liquids.get(liquid) / next.info().liquidCapacity;
            float fract = liquids.get(liquid) / info().liquidCapacity * info().liquidPressure;
            float flow = Math.min(Mathf.clamp(fract - ofract) * info().liquidCapacity, liquids.get(liquid));
            flow = Math.min(flow, next.info().liquidCapacity - next.liquids.get(liquid));
            if(flow > 0f && ofract <= fract && next.acceptLiquid(this, liquid)){
                next.handleLiquid(this, liquid, flow);
                liquids.remove(liquid, flow);
                return flow;
            }else if(!next.info().consumesLiquid(liquid) && next.liquids.currentAmount() / next.info().liquidCapacity > 0.1f && fract > 0.1f){
                // vanilla quirk: reacts with next's current liquid, which is wrong for multi-liquid blocks
                LiquidDef other = world().content().liquid(next.liquids.current()), self = world().content().liquid(liquid);
                if(other.blockReactive() && self.blockReactive()){
                    if((other.flammability() > 0.3f && self.temperature() > 0.7f) || (self.flammability() > 0.3f && other.temperature() > 0.7f)){
                        damageContinuous(1);
                        next.damageContinuous(1);
                        // Fx.fire's chance draws from the random even on a server
                        Mathf.chanceDelta(world().rand(), 0.1);
                    }else if((self.temperature() > 0.7f && other.temperature() < 0.55f) || (other.temperature() > 0.7f && self.temperature() < 0.55f)){
                        liquids.remove(liquid, Math.min(liquids.get(liquid), 0.7f));
                        Mathf.chanceDelta(world().rand(), 0.2f);
                    }
                }
            }
        }
        return 0;
    }

    public Building getLiquidDestination(Building from, int liquid){
        return this;
    }

    // geometry

    /** {@code nearby(dx, dy)}: the building at an offset from the origin tile, or null. */
    public final Building nearby(int dx, int dy){
        return world().buildings().at(tileX() + dx, tileY() + dy);
    }

    /** {@code nearby(rotation)}: the building next to the origin tile in a direction, or null. */
    public final Building nearby(int rotation){
        return rotation < 0 || rotation > 3 ? null : nearby(Geometry.d4x(rotation), Geometry.d4y(rotation));
    }

    /** {@code relativeTo(build)}: the direction of {@code build}'s centre from this one's, or -1. */
    public final int relativeTo(Building build){
        float x = x(), y = y(), bx = build.x(), by = build.y();
        if(Math.abs(x - bx) > Math.abs(y - by)){
            if(x <= bx - 1) return 0;
            if(x >= bx + 1) return 2;
        }else{
            if(y <= by - 1) return 1;
            if(y >= by + 1) return 3;
        }
        return -1;
    }

    /** {@code relativeTo(cx, cy)} ({@code Tile.absoluteRelativeTo}): the direction of tile (cx, cy), or -1. */
    public final int relativeTo(int cx, int cy){
        int x = tileX(), y = tileY();
        if(block.size() % 2 == 1){
            if(Math.abs(x - cx) > Math.abs(y - cy)){
                if(x <= cx - 1) return 0;
                if(x >= cx + 1) return 2;
            }else{
                if(y <= cy - 1) return 1;
                if(y >= cy + 1) return 3;
            }
        }else{
            if(Math.abs(x - cx + 0.5f) > Math.abs(y - cy + 0.5f)){
                if(x + 0.5f <= cx - 1) return 0;
                if(x + 0.5f >= cx + 1) return 2;
            }else{
                if(y + 0.5f <= cy - 1) return 1;
                if(y + 0.5f >= cy + 1) return 3;
            }
        }
        return -1;
    }

    /** Multiblock front: the building past this one's edge in its rotation. */
    public final Building front(){
        return side(rotation);
    }

    public final Building back(){
        return side(rotation + 2);
    }

    public final Building left(){
        return side(rotation + 1);
    }

    public final Building right(){
        return side(rotation + 3);
    }

    private Building side(int direction){
        int trns = block.size() / 2 + 1;
        return nearby(Geometry.d4x(direction) * trns, Geometry.d4y(direction) * trns);
    }

    /**
     * {@code hitSize()} (BuildingComp.java:2054): {@code tile.block().size * tilesize}, the block on its tile now. Vanilla
     * quirk: a building off its tile (destroyed, removed, or a payload's {@code emptyTile}, air) measures what stands
     * there, so a dead 2x2 drill is 8 wide to a {@code CommandAI} still holding it as its attack target.
     */
    public final float hitSize(){
        if(tile == -1) return Footprint.TILE_SIZE;
        if(world().buildings().at(tile) == this) return block.size() * Footprint.TILE_SIZE;
        return world().content().block(world().tiles().block(tile)).size() * Footprint.TILE_SIZE;
    }

    /** Distance from the centre to (x, y). */
    public final float dst(float x, float y){
        return Mathf.dst(x(), y(), x, y);
    }

    /** {@code within(x, y, dst)}. */
    public final boolean within(float x, float y, float dst){
        return Mathf.within(x(), y(), x, y, dst);
    }

    /**
     * {@code fogRadius()} (BuildingComp.java:580): the tiles around it its team sees in fog, the block's unless a kind
     * changes it. A kind whose radius changes calls {@code FogControl.forceUpdate}.
     */
    public float fogRadius(){
        return block().fogRadius();
    }

    /** {@code range()} of vanilla's {@code Ranged} buildings: the block's {@code range} unless a kind changes it. */
    public float range(){
        return info().range;
    }

    // timers

    /** {@code timer(index, time)}: whether {@code time} ticks passed since it last fired, restarting it if so. */
    public final boolean timer(int index, float time){
        if(Float.isInfinite(time)) return false;
        if(index >= timer.length) throw new IllegalStateException("Out of bounds! Max timer size is " + timer.length + "!");
        boolean got = timerCheck(index, time);
        if(got) timer[index] = world().timers().time();
        return got;
    }

    /** {@code timer.check(index, time)}. */
    public final boolean timerCheck(int index, float time){
        float now = world().timers().time();
        return now - timer[index] >= time || now < timer[index];
    }

    /** {@code timer.reset(index, time)}. */
    public final void timerReset(int index, float time){
        timer[index] = world().timers().time() - time;
    }

    // boosts and healing

    /** {@code applyBoost}: raises {@link #timeScale} to {@code intensity}, never lowering it. */
    public void applyBoost(float intensity, float duration){
        if(!info().canOverdrive) return;
        // a lower intensity does not refresh the duration
        if(intensity >= timeScale - 0.001f) timeScaleDuration = Math.max(timeScaleDuration, duration);
        timeScale = Math.max(timeScale, intensity);
    }

    /** {@code applySlowdown}: lowers {@link #timeScale} to {@code intensity}, never raising it. */
    public void applySlowdown(float intensity, float duration){
        if(intensity <= timeScale + 0.001f) timeScaleDuration = Math.max(timeScaleDuration, duration);
        timeScale = Math.min(timeScale, intensity);
    }

    public void applyHealSuppression(float amount){
        healSuppressionTime = Math.max(healSuppressionTime, world().timers().time() + amount);
    }

    public boolean isHealSuppressed(){
        return info().suppressable && world().timers().time() <= healSuppressionTime;
    }

    /** {@code checkSuppression()}: whether healing is suppressed; the particles it spawns draw from the random. */
    public boolean checkSuppression(){
        if(isHealSuppressed()){
            if(Mathf.chanceDelta(world().rand(), 0.03)){
                float range = block.size() * Footprint.TILE_SIZE / 2f - 1f;
                Mathf.range(world().rand(), range);
                Mathf.range(world().rand(), range);
            }
            return true;
        }
        return false;
    }

    public void recentlyHealed(){
        lastHealTime = world().timers().time();
    }

    public boolean wasRecentlyHealed(float duration){
        return lastHealTime + duration >= world().timers().time();
    }

    public boolean wasRecentlyDamaged(){
        return lastDamageTime + RECENT_DAMAGE_TIME >= world().timers().time();
    }

    // health

    /** {@code damaged()}. */
    public final boolean damaged(){
        return health < maxHealth - 0.001f;
    }

    /** {@code healthf()}. */
    public final float healthf(){
        return health / maxHealth;
    }

    /** {@code handleDamage(amount)}: how much of a hit this kind takes. */
    public float handleDamage(float amount){
        return amount;
    }

    /**
     * {@code damage(amount)} (BuildingComp.java:2066): scaled by the team's block health, then destroyed at zero
     * through {@link Buildings#kill}, which is vanilla's {@code Call.buildDestroyed}.
     */
    public void damage(float damage){
        if(dead) return;
        float dm = rules().blockHealth(team);
        lastDamageTime = world().timers().time();
        if(Mathf.zero(dm)){
            damage = health + 1;
        }else{
            damage /= dm;
        }
        health -= handleDamage(damage);
        healthChanged();
        if(health <= 0) world().buildings().kill(this);
    }

    /** {@code damage(source, amount)}: splash damage from a team. */
    public void damage(int sourceTeam, float damage){
        damage(damage);
    }

    /** {@code damageContinuous(amount)}: per-tick damage, Δ = 1. */
    public final void damageContinuous(float amount){
        damage(amount);
    }

    /** {@code damagePierce(amount)}: armor is applied by callers, so the same as {@link #damage(float)}. */
    public final void damagePierce(float amount){
        damage(amount);
    }

    /** {@code Damage.applyArmor} with the block's armor, for hits that do not pierce it. */
    public final float applyArmor(float damage, float armorMultiplier){
        return Math.max(damage - info().armor * armorMultiplier, damage * 0.1f);
    }

    /** {@code heal()}: back to full health, alive. */
    public void heal(){
        dead = false;
        health = maxHealth;
        healthChanged();
    }

    /** {@code heal(amount)}. */
    public void heal(float amount){
        health += amount;
        clampHealth();
        healthChanged();
    }

    public final void healFract(float amount){
        heal(amount * maxHealth);
    }

    public final void clampHealth(){
        health = Math.min(health, maxHealth);
        if(Float.isNaN(health)) health = 0f;
    }

    /** {@code healthChanged()}: the indexer's damaged list follows, and the sync layer hears of it. */
    public void healthChanged(){
        world().buildings().healthChanged(this);
    }

    /** {@code kill()}: {@code Call.buildDestroyed}, through {@link Buildings#kill}. */
    public final void kill(){
        world().buildings().kill(this);
    }

    /**
     * {@code killed()} (BuildingComp.java:2250), which {@link Buildings#kill} calls: dead, destroyed, off its tile, out
     * of the update list, then {@link #afterDestroyed()}.
     */
    void killed(){
        dead = true;
        // BlockDestroyEvent: rebuild plans (construction) hear of it while the tile is intact
        world().buildings().destroying(this);
        onDestroyed();
        world().buildings().remove(this, world().buildings().cause());
        remove();
        afterDestroyed();
    }

    /**
     * {@code onDestroyed()} (BuildingComp.java:1490): the explosion its items, liquids and stored power make, while
     * the tile is still intact. The explosion itself is {@link BuildingEffects#explode}.
     */
    public void onDestroyed(){
        BlockInfo info = info();
        ContentEpoch content = world().content();
        float explosiveness = info.baseExplosiveness;
        float flammability = 0f;
        float power = 0f;
        if(block.hasItems()){
            for(int i = 0; i < content.items(); i++){
                ItemDef item = content.item(i);
                int amount = Math.min(items.get(i), explosionItemCap());
                explosiveness += item.explosiveness() * amount;
                flammability += item.flammability() * amount;
                power += item.charge() * Mathf.pow(amount, 1.1f) * 150f;
            }
        }
        if(block.hasLiquids()){
            flammability += liquids.sum((liquid, amount) -> content.liquid(liquid).flammability() * amount / 2f);
            explosiveness += liquids.sum((liquid, amount) -> content.liquid(liquid).explosiveness() * amount / 2f);
        }
        ConsumePower consPower = info.consumers.power();
        if(consPower != null && consPower.buffered) power += this.power.status * consPower.capacity;
        BuildingEffects effects = BuildingEffects.of(world());
        if(block.hasLiquids() && rules().damageExplosions){
            liquids.each((liquid, amount) -> effects.splashLiquid(this, liquid, amount));
        }
        effects.explode(this, flammability * info.flammabilityScale, explosiveness * 3.5f * info.explosivenessScale, power,
            Footprint.TILE_SIZE * block.size() / 2f, rules().damageExplosions);
    }

    /** {@code afterDestroyed()}: once the tile is gone; the block's destroy bullet is {@link BuildingEffects#afterDestroyed}. */
    public void afterDestroyed(){
        BuildingEffects.of(world()).afterDestroyed(this);
    }

    // config

    /**
     * The kind's config handlers, vanilla's {@code Block.config(Class, …)} and {@code configClear}: a kind returns one
     * static {@link Configs} from here.
     */
    protected Configs<?> configs(){
        return Configs.none();
    }

    /** {@code config()}: the value that rebuilds this building's configuration, or null. */
    public Object config(){
        return null;
    }

    /**
     * {@code configured(builder, value)} (BuildingComp.java:1419): runs the kind's handler for the value's type; a
     * building value with no handler copies that building's config. Use {@link Buildings#configure}, which also
     * publishes the event, for configs that vanilla sends as {@code tileConfig}.
     */
    public void configured(Unit builder, Object value){
        if(configs().dispatch(this, value)) return;
        if(value instanceof Building build){
            Object conf = build.config();
            if(conf != null && !(conf instanceof Building)) configured(builder, conf);
        }
    }

    /**
     * Shard's own bound on a client's config, where vanilla has none: null to apply {@code value}, or why this
     * building refuses it whole, before any handler runs. The input that asked then rolls its client back and forwards
     * nothing. A processor bounds what its compressed code and links expand to ({@code LogicPart.admitConfig}).
     */
    public String admitConfig(Object value){
        return null;
    }

    /**
     * {@code getCommandPosition()}: a commandable block's rally point (a unit factory's or reconstructor's), or null.
     * The building is commandable when its block's {@code commandable} is set.
     */
    public Vec2 getCommandPosition(){
        return null;
    }

    /** {@code onCommand(target)}: {@code commandBuilding} gave this commandable building a rally point. */
    public void onCommand(Vec2 target){
    }

    /** {@code configureAny(value)}: a config from the server, vanilla's {@code Call.tileConfig(null, this, value)}. */
    public final void configureAny(Object value){
        world().buildings().configure(this, null, value, world().buildings().cause());
    }

    // logic

    /**
     * {@code control(type, p1, …)} with a number (BuildingComp.java:2158): {@code enabled} sets {@link #enabled}. The
     * processor's own part of the instruction (LExecutor.java:579) wakes the building or records itself as
     * {@link #lastDisabler} first.
     */
    public void control(LAccess type, double p1, double p2, double p3, double p4){
        if(type == LAccess.enabled) enabled = !Mathf.zero((float)p1);
    }

    /**
     * {@code control(type, p1, …)} with an object (BuildingComp.java:2165): {@code config} runs
     * {@code configured(null, value)} on a logic-configurable block, unless the value is a processor. Vanilla sends no
     * Call for it, so no event either.
     */
    public void control(LAccess type, Object p1, double p2, double p3, double p4){
        if(type == LAccess.config && info().logicConfigurable && !(p1 instanceof Building b && b.block.isA(KindKey.of("logic-block")))){
            configured(null, p1);
        }
    }

    /** {@code Senseable.noSensed}: what {@link #senseObject} returns for a property that is a number. */
    public static final Object NO_SENSED = new Object(){
        @Override
        public String toString(){
            return "noSensed";
        }
    };

    private static final KindKey[] RANGED = {KindKey.of("base-turret"), KindKey.of("mend-projector"),
        KindKey.of("overdrive-projector"), KindKey.of("logic-block"), KindKey.of("repair-tower")};

    /**
     * {@code sense(LAccess)} (BuildingComp.java:2100): a number, or NaN for a property this building doesn't have,
     * which the processor reads as null. A kind with properties of its own handles them and calls {@code super}. The
     * power graph's numbers are its last tick's, per second in and out; the block unit's are 0 until WP60.
     */
    public double sense(LAccess sensor){
        BlockInfo info = info();
        ConsumePower power = info.consumers.power();
        return switch(sensor){
            case x -> x() / Footprint.TILE_SIZE;
            case y -> y() / Footprint.TILE_SIZE;
            case color -> TeamColors.color(team);
            case dead -> !isValid() ? 1 : 0;
            case solid -> block.solid() || checkSolid() ? 1 : 0;
            case team -> team;
            case health -> health;
            case maxHealth -> maxHealth;
            case efficiency -> efficiency;
            case timescale -> timeScale;
            case range -> ranged() ? range() / Footprint.TILE_SIZE : 0;
            case rotation -> rotation;
            case totalItems -> items == null ? 0 : items.total();
            case totalLiquids -> liquids == null ? 0 : liquids.currentAmount();
            case totalPower -> this.power == null || power == null ? 0 : this.power.status * (power.buffered ? power.capacity : 1f);
            case itemCapacity -> block.hasItems() ? info.itemCapacity : 0;
            case liquidCapacity -> block.hasLiquids() ? info.liquidCapacity : 0;
            case powerCapacity -> power != null ? power.capacity : 0f;
            case powerNetIn -> this.power == null || this.power.graph == null ? 0 : this.power.graph.getLastScaledPowerIn() * 60;
            case powerNetOut -> this.power == null || this.power.graph == null ? 0 : this.power.graph.getLastScaledPowerOut() * 60;
            case powerNetStored -> this.power == null || this.power.graph == null ? 0 : this.power.graph.getLastPowerStored();
            case powerNetCapacity -> this.power == null || this.power.graph == null ? 0 : this.power.graph.getLastCapacity();
            case armor -> info.armor;
            case enabled -> enabled ? 1 : 0;
            case controlled, cameraX, cameraY, cameraWidth, cameraHeight -> 0;
            // getPayloads() (payload stocks) comes with the kinds that keep one (WP80)
            case payloadCount -> getPayload() != null ? 1 : 0;
            case size -> block.size();
            default -> Float.NaN;
        };
    }

    /** Vanilla's {@code Ranged} buildings: turrets, menders, overdrive projectors, processors, repair towers. */
    private boolean ranged(){
        if(!block.baked()) return false;
        for(KindKey kind : RANGED) if(block.isA(kind)) return true;
        return false;
    }

    /**
     * {@code senseObject(LAccess)}: an object in {@code :sim}'s terms ({@link BlockDef}, {@link ContentRef}, a
     * building, a unit, a config value), or {@link #NO_SENSED} when the property is a number.
     */
    public Object senseObject(LAccess sensor){
        return switch(sensor){
            case type -> block;
            case firstItem -> items == null || items.first() == -1 ? null : ContentRef.item(items.first());
            case config -> configs().sensesContent() ? config() : null;
            case payloadType -> getPayload() == null ? null : getPayload().content();
            default -> NO_SENSED;
        };
    }

    /** {@code sense(Content)}: how much of an item or liquid this building holds; NaN for other content. */
    public double sense(ContentType type, int id){
        if(type == ContentType.ITEM && items != null) return items.get(id);
        if(type == ContentType.LIQUID && liquids != null) return liquids.get(id);
        return Float.NaN;
    }

    /**
     * {@code setProp(LAccess, double)} (BuildingComp.java:2174), a world processor's {@code setprop}: health (0 kills it
     * with {@code Call.buildDestroyed}), team, and a battery's stored power.
     */
    public void setProp(LAccess prop, double value){
        switch(prop){
            case health -> {
                health = (float)Mathf.clamp(value, 0, maxHealth);
                if(health <= 0f){
                    if(!dead) world().buildings().kill(this);
                }else{
                    healthChanged();
                }
            }
            case team -> {
                int next = ((byte)(int)value) & 0xff;
                if(team != next) world().buildings().changeTeam(this, next, world().buildings().cause());
            }
            case totalPower -> {
                ConsumePower power = info().consumers.power();
                if(this.power != null && power != null && power.buffered){
                    this.power.status = Mathf.clamp((float)(value / power.capacity));
                }
            }
            default -> {}
        }
    }

    /** {@code setProp(UnlockableContent, double)}: sets an item or liquid amount, as far as the building takes it. */
    public void setProp(ContentType type, int id, double value){
        if(type == ContentType.ITEM && items != null){
            int amount = (int)value;
            if(items.get(id) != amount){
                if(items.get(id) < amount){
                    handleStack(id, acceptStack(id, amount - items.get(id), -1), -1);
                }else if(amount >= 0){
                    removeStack(id, items.get(id) - amount);
                }
            }
        }else if(type == ContentType.LIQUID && liquids != null){
            float amount = Mathf.clamp((float)value, 0f, info().liquidCapacity);
            // decreasing the amount is always allowed
            if(amount < liquids.get(id) || (acceptLiquid(this, id) && (liquids.current() == id || liquids.currentAmount() <= 0.1f || info().consumesLiquid(id)))){
                liquids.set(id, amount);
            }
        }
    }

    // loading

    /**
     * Vanilla's {@code afterReadAll()}: once a save or map has read every building and entity, before its first tick.
     * A kind that defers part of its read until then (a processor's code, a memory cell's references) does it here.
     */
    public void afterReadAll(){
    }

    // proximity

    /** How many buildings are in this one's proximity. */
    public final int proximitySize(){
        return proximitySize;
    }

    /** Proximity entry {@code i}, {@code 0 <= i < proximitySize()}. */
    public final Building proximity(int i){
        if(i >= proximitySize) throw new IndexOutOfBoundsException(i + " of " + proximitySize);
        return proximity[i];
    }

    /** The proximity in order, as a new list. */
    public final List<Building> proximityList(){
        return List.of(Arrays.copyOf(proximity, proximitySize));
    }

    final void proximityClear(){
        Arrays.fill(proximity, 0, proximitySize, null);
        proximitySize = 0;
    }

    final void proximityAdd(Building other){
        if(proximitySize == proximity.length) proximity = Arrays.copyOf(proximity, Math.max(6, proximitySize * 2));
        proximity[proximitySize++] = other;
    }

    /** {@code Seq.addUnique}: appends unless already present. */
    final void proximityAddUnique(Building other){
        for(int i = 0; i < proximitySize; i++){
            if(proximity[i] == other) return;
        }
        proximityAdd(other);
    }

    /** {@code Seq.remove(value, true)} on an ordered seq: the first occurrence, later entries shift down. */
    final void proximityRemove(Building other){
        for(int i = 0; i < proximitySize; i++){
            if(proximity[i] == other){
                System.arraycopy(proximity, i + 1, proximity, i, proximitySize - i - 1);
                proximity[--proximitySize] = null;
                return;
            }
        }
    }

    /** Vanilla's {@code onProximityUpdate}: something adjacent was placed or removed, this one included. */
    public void onProximityUpdate(){
        noSleep();
    }

    /**
     * Vanilla's {@code onProximityAdded}: this building's proximity was rebuilt; a powered one merges graphs with what
     * it connects to now ({@code updatePowerGraph}). Overrides call {@code super} first.
     */
    public void onProximityAdded(){
        if(power != null) PowerGraph.updatePowerGraph(this);
    }

    /**
     * Vanilla's {@code onProximityRemoved}: called shortly before this building leaves its tiles; a powered one splits
     * its graph around itself and drops its laser links ({@code powerGraphRemoved}). Overrides call {@code super}.
     */
    public void onProximityRemoved(){
        if(power != null) PowerGraph.powerGraphRemoved(this);
    }

    /**
     * The end of vanilla's {@code removeFromProximity}, after the neighbours' {@code onProximityUpdate}: where an
     * override's code after {@code super.removeFromProximity()} runs (a processor re-enabling its links).
     */
    public void afterProximityRemoved(){
    }

    /** Vanilla's {@code onRemoved}: this building's tile is about to change. */
    public void onRemoved(){
    }

    /**
     * Vanilla's {@code checkSolid}: solid to units beyond {@code block.solid} (a closed door). A kind whose answer
     * changes calls {@link Buildings#solidityChanged(Building)}, since the grid keeps it as a bitset.
     */
    public boolean checkSolid(){
        return false;
    }

    /** Vanilla's {@code sleep()}: call when idle. After {@link #TIME_TO_SLEEP} ticks the building leaves the update list. */
    public final void sleep(){
        sleepTime += 1f;
        if(!sleeping && sleepTime >= TIME_TO_SLEEP){
            remove();
            sleeping = true;
        }
    }

    /** Vanilla's {@code noSleep()}: call when busy. Wakes a sleeping building, appending it to the update list. */
    public final void noSleep(){
        sleepTime = 0f;
        if(sleeping){
            add();
            sleeping = false;
        }
    }

    public final boolean sleeping(){
        return sleeping;
    }

    /** Joins the world's update list, at the end; a powered building's graph updater joins its group too. */
    @Override
    public final void add(){
        if(isAdded()) return;
        world().builds().add(this);
        if(power != null && power.graph != null) power.graph.checkAdd();
        added(true);
    }

    /** Leaves the world's update list; the last building there takes this one's place. */
    @Override
    public final void remove(){
        if(!isAdded()) return;
        world().builds().remove(this);
        added(false);
    }

    @Override
    public final Copyable blank(){
        return world().kinds().instantiate(block);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Building)into;
        out.block = block;
        out.tile = tile;
        out.index = index;
        out.updateIndex = updateIndex;
        out.sleeping = sleeping;
        out.sleepTime = sleepTime;
        out.proximity = proximity.length == 0 ? NO_BUILDINGS : new Building[proximity.length];
        for(int i = 0; i < proximitySize; i++) out.proximity[i] = context.map(proximity[i]);
        out.proximitySize = proximitySize;
        out.team = team;
        out.rotation = rotation;
        out.health = health;
        out.maxHealth = maxHealth;
        out.dead = dead;
        out.efficiency = efficiency;
        out.optionalEfficiency = optionalEfficiency;
        out.potentialEfficiency = potentialEfficiency;
        out.shouldConsumePower = shouldConsumePower;
        out.timeScale = timeScale;
        out.timeScaleDuration = timeScaleDuration;
        out.enabled = enabled;
        out.lastDisabler = lastDisabler == null ? null : context.map(lastDisabler);
        out.visibleFlags = visibleFlags;
        out.cdump = cdump;
        out.dumpAccum = dumpAccum;
        out.lastDamageTime = lastDamageTime;
        out.healSuppressionTime = healSuppressionTime;
        out.lastHealTime = lastHealTime;
        out.wasDamaged = wasDamaged;
        out.timer = timer.length == 0 ? NO_TIMERS : timer.clone();
        out.items = items == null ? null : items.copy();
        out.liquids = liquids == null ? null : liquids.copy();
        out.power = power == null ? null : power.copy(context);
        out.payloadX = payloadX;
        out.payloadY = payloadY;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(block.id()).i(tile).i(index).i(updateIndex).bool(sleeping).f(sleepTime);
        hasher.i(proximitySize);
        for(int i = 0; i < proximitySize; i++) hasher.i(proximity[i].id());
        hasher.i(team).i(rotation).f(health).f(maxHealth).bool(dead);
        hasher.f(efficiency).f(optionalEfficiency).f(potentialEfficiency).bool(shouldConsumePower);
        hasher.f(timeScale).f(timeScaleDuration).bool(enabled);
        hasher.i(lastDisabler == null ? -1 : lastDisabler.id()).l(visibleFlags).i(cdump).f(dumpAccum);
        hasher.f(lastDamageTime).f(healSuppressionTime).f(lastHealTime).bool(wasDamaged).floats(timer, 0, timer.length);
        hasher.bool(items != null);
        if(items != null) items.hash(hasher);
        hasher.bool(liquids != null);
        if(liquids != null) liquids.hash(hasher);
        hasher.bool(power != null);
        if(power != null) power.hash(hasher);
        // only payloads move off their tile
        if(!Float.isNaN(payloadX)) hasher.f(payloadX).f(payloadY);
    }

    @Override
    public String toString(){
        return "Building#" + id() + "[" + (world() == null ? tile : world().tiles().x(tile) + "," + world().tiles().y(tile))
            + "]:" + (block == null ? "unbound" : block.name());
    }
}
