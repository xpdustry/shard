package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code CoreBlock.CoreBuild} (CoreBlock.java): every core, Serpulo's and Erekir's. A core registers with its
 * team's {@link Teams} data on every proximity update (in a world without team data, it stands alone), and a team's cores and the {@link Storage} linked to them hold
 * one item module ({@link SharedItems}), whose capacity, {@link #storageCapacity}, is the sum of theirs. It takes any
 * item: past that capacity, or always for a non-buildable item when its block incinerates those, the item burns
 * ({@code coreIncinerates} decides whether it is offered at all). It spawns its players with its block's
 * {@code unitType} ({@link #requestSpawn}), and adds {@code unitCapModifier} to its team's unit cap through the
 * building index ({@link TeamUnits#capModifier}).
 *
 * <p>Calls: {@link #playerSpawn} publishes {@link CoreEvent.PlayerSpawn}; a captured core's posted {@code setNet}
 * publishes {@link CoreEvent.SetTile}. {@code CoreChangeEvent} from {@code created}, {@code changeTeam} and
 * {@code onDestroyed} publishes {@link CoreChangeEvent}; only a destruction's is also posted to
 * {@link Teams#coreChanged}, as vanilla's one server listener only acts on a core no longer added ({@code DeadTeams}).
 *
 * <p>Not ported, with why: drawing, landing and launching (clients only; the server never begins a launch); the
 * campaign's sector item counts and stats ({@code state.stats.coreItemCount} feeds a game-over screen the server doesn't
 * show); {@code block.configurable = coreBuildAndConfig}, the client's configuration UI; placement
 * rules and core upgrades ({@code canPlaceOn}, {@code canBreak}, {@code placeBegan}), which construction (WP30) calls;
 * and {@code changeTeam} from outside, which needs a building base hook (see {@link #changeTeam}).
 */
@Kind("core-block")
public final class Core extends Building implements Checkpointable{
    /** {@code CoreBuild.storageCapacity}: what the shared module may hold of each item. */
    public int storageCapacity;
    /** Set by linked storage before it hands over an item, so the core doesn't burn it with an effect twice. */
    public boolean noEffect;
    /** The last other team that damaged this core: who captures it ({@link #lastDamage()}). */
    int lastDamage = Team.DERELICT;
    public float iframes = -1f;
    public float thrusterTime;
    /** The RTS command position, or null. Saved. */
    public Vec2 commandPos;
    /** The team module this core shares, or null while it shares nothing. */
    SharedItems shared;
    @Derived("the block's unitType, allowSpawn and incinerateNonBuildable, read on first use")
    private int unitType = -2;
    @Derived("read with unitType")
    private boolean allowSpawn, incinerateNonBuildable;

    /** {@code lastDamage}: the last other team that damaged this core, which captures it under {@code coreCapture}. */
    public int lastDamage(){
        return lastDamage;
    }

    // parameters

    /** The block's {@code unitType}: what its players spawn as. */
    public UnitDef unitType(){
        params();
        return world().content().unit(unitType);
    }

    /** {@code allowSpawn}: whether players may respawn here. */
    public boolean allowSpawn(){
        params();
        return allowSpawn;
    }

    private void params(){
        if(unitType != -2) return;
        BlockDef block = block();
        ContentEpoch content = world().content();
        if(block.baked()){
            unitType = content.id(ContentType.UNIT, block.fields().s("unitType"));
            allowSpawn = block.fields().b("allowSpawn");
            incinerateNonBuildable = block.fields().b("incinerateNonBuildable");
        }else{
            unitType = content.id(ContentType.UNIT, "alpha");
            allowSpawn = true;
        }
        if(unitType < 0) throw new IllegalStateException(block.name() + "'s unit type is not in the content");
    }

    private boolean incinerates(int item){
        params();
        return incinerateNonBuildable && !world().content().item(item).buildable();
    }

    // update and damage

    @Override
    public void updateTile(){
        iframes -= 1f;
        thrusterTime -= 1f / 90f;
    }

    @Override
    public void damage(int sourceTeam, float damage){
        if(iframes > 0) return;
        if(sourceTeam >= 0 && sourceTeam != team) lastDamage = sourceTeam;
        super.damage(sourceTeam, damage);
    }

    @Override
    public void onDestroyed(){
        if(rules().coreCapture){
            // just an explosion, no fire, so it can't be recaptured at once
            BuildingEffects.of(world()).explode(this, 0f, 0f, 0f, Footprint.TILE_SIZE * block().size() / 2f, rules().damageExplosions);
        }else{
            super.onDestroyed();
        }
        shockwaveDust(world(), x(), y(), 40f + block().size() * Footprint.TILE_SIZE, 0.5f);
        // CoreChangeEvent: Logic posts its dead-team check (DeadTeams)
        Teams teams = Teams.of(world());
        if(teams != null) teams.coreChanged(this);
        Teams.coreChangeEvent(this, CoreChangeEvent.Change.DESTROYED);
    }

    /** {@code created()} (CoreBlock.java:589) fires {@code CoreChangeEvent}; a payload made off the map hasn't yet. */
    @Override
    public void created(){
        super.created();
        if(!isPayload()) Teams.coreChangeEvent(this, CoreChangeEvent.Change.CREATED);
    }

    /** A placed core payload: vanilla's {@code init} runs {@code created()} again. */
    @Override
    public void recreated(){
        super.recreated();
        Teams.coreChangeEvent(this, CoreChangeEvent.Change.CREATED);
    }

    @Override
    public void afterDestroyed(){
        super.afterDestroyed();
        if(!rules().coreCapture) return;
        TileGrid tiles = world().tiles();
        int x = tiles.x(tile()), y = tiles.y(tile());
        Building next = world().buildings().setBlock(x, y, block(), lastDamage, 0, world().buildings().cause());
        // invincible for a while, so it can't be recaptured at once
        if(next instanceof Core core) core.iframes = core.captureInvincibility();
        // net.server(): "delay so clients don't destroy it afterwards"
        world().timers().run(0f, new SetNet(tile(), block().id(), lastDamage));
    }

    private float captureInvincibility(){
        return block().baked() ? block().fields().f("captureInvicibility") : 60f * 15f;
    }

    /**
     * {@code tile.setNet(block, team, 0)} posted by a captured core: {@code Call.setTile}, which sets the block again
     * (a new core, whose {@code iframes} are back to -1: vanilla quirk), then is sent.
     */
    public record SetNet(int pos, int block, int team) implements Timers.Task{
        @Override
        public void run(World world){
            TileGrid tiles = world.tiles();
            world.buildings().setBlock(tiles.x(pos), tiles.y(pos), world.content().block(block), team, 0, Cause.SERVER);
            world.publish(new CoreEvent.SetTile(world.ticks(), pos, block, team, 0));
        }
    }

    /**
     * {@code Effect.shockwaveDust(x, y, rad, density)}: one wave of dust every 3.5 ticks, whose chances and colours
     * draw from the world random on a server, though nothing is shown there.
     */
    static void shockwaveDust(World world, float x, float y, float rad, float density){
        float spacing = 12f;
        int waves = Math.max(1, Mathf.ceil(rad / spacing));
        for(int i = 0; i < waves; i++) world.timers().run(i * 3.5f, new DustWave(x, y, 1 + spacing * i, i, density));
    }

    /** One wave of {@link #shockwaveDust}. */
    public record DustWave(float x, float y, float radius, int wave, float density) implements Timers.Task{
        @Override
        public void run(World world){
            Rand rand = world.rand();
            TileGrid tiles = world.tiles();
            int rays = Mathf.ceil(radius * Mathf.PI * 2f / 6f * density);
            for(int r = 0; r < rays; r++){
                if(Mathf.chance(rand, 0.7f - wave * 0.02f)){
                    float angle = r * 360f / (float)rays;
                    float ox = Angles.trnsx(angle, radius), oy = Angles.trnsy(angle, radius);
                    int tx = Math.round((x + ox) / Footprint.TILE_SIZE), ty = Math.round((y + oy) / Footprint.TILE_SIZE);
                    if(tiles.inBounds(tx, ty)){
                        // Fx.podLandDust's rotation and colour
                        Mathf.range(rand, 30f);
                        Mathf.range(rand, 0.15f);
                    }
                }
            }
        }
    }

    // items

    /** {@code StorageBuild.overwrote}, inherited: a core built over buildings takes their items, capped at its own capacity. */
    @Override
    public void overwrote(List<Building> previous){
        Storage.overwrote(this, null, previous);
    }

    @Override
    public boolean canUnload(){
        return info().unloadable && rules().allowCoreUnloaders;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return rules().coreIncinerates || items.get(item) < getMaximumAccepted(item);
    }

    @Override
    public int getMaximumAccepted(int item){
        return rules().coreIncinerates ? Integer.MAX_VALUE / 2 : storageCapacity;
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        int realAmount = incinerates(item) ? 0 : Math.min(amount, storageCapacity - items.get(item));
        super.handleStack(item, realAmount, sourceTeam);
    }

    /** CoreBlock.java:886, a server's branch: an item past the capacity, or a non-buildable one it incinerates, burns. */
    @Override
    public void handleItem(Building source, int item){
        // state.stats.coreItemCount, which core-item objectives read (CoreBlock.java:892)
        ObjectiveState.coreItem(world(), team, item);
        if(items.get(item) >= storageCapacity || incinerates(item)){
            if(!noEffect) Storage.incinerateEffect(this, source);
            noEffect = false;
        }else{
            super.handleItem(source, item);
        }
    }

    // linking

    /**
     * CoreBlock.java:757: takes the team's shared module, registers with the team, links adjacent storage, and sets
     * every core of the team to the capacity they make together. While the map loads the items are not capped.
     */
    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        Teams teams = Teams.of(world());
        List<Core> cores = teams == null ? List.of() : teams.cores(team);
        for(int i = 0; i < cores.size(); i++){
            Core other = cores.get(i);
            if(other.tile() != tile()) share(other);
        }
        if(teams != null) teams.registerCore(this);
        storageCapacity = info().itemCapacity;
        for(int i = 0; i < proximitySize(); i++){
            if(owns(proximity(i))) storageCapacity += proximity(i).info().itemCapacity;
        }
        for(int i = 0; i < proximitySize(); i++){
            if(owns(proximity(i))) ((Storage)proximity(i)).link(this);
        }
        for(int i = 0; i < cores.size(); i++){
            Core other = cores.get(i);
            if(other.tile() == tile()) continue;
            storageCapacity += other.info().itemCapacity;
            for(int j = 0; j < other.proximitySize(); j++){
                if(owns(other, other.proximity(j))) storageCapacity += other.proximity(j).info().itemCapacity;
            }
        }
        if(!world().buildings().loading()){
            for(int item = 0; item < items.length(); item++) items.set(item, Math.min(items.get(item), storageCapacity));
        }
        for(int i = 0; i < cores.size(); i++) cores.get(i).storageCapacity = storageCapacity;
    }

    /** {@code this.items = other.items}. */
    private void share(Core other){
        shared = SharedItems.of(other);
        items = shared.module;
    }

    /** {@code owns(tile)}: storage that links to this core. */
    public boolean owns(Building tile){
        return owns(this, tile);
    }

    /** {@code owns(core, tile)}: merging storage linked to {@code core}, or to nothing. */
    public static boolean owns(Core core, Building tile){
        return tile instanceof Storage storage && storage.coreMerge() && (storage.linkedCore == core || storage.linkedCore == null);
    }

    /**
     * CoreBlock.java:855: before the tile changes, the storage sharing this core's module gets its share of the items
     * back, by capacity, and the team's other cores update without it.
     */
    @Override
    public void onRemoved(){
        int totalCapacity = 0;
        for(int i = 0; i < proximitySize(); i++){
            Building e = proximity(i);
            if(e.items != null && e.items == items) totalCapacity += e.info().itemCapacity;
        }
        for(int i = 0; i < proximitySize(); i++){
            Building e = proximity(i);
            if(!(owns(e) && e.items == items)) continue;
            var storage = (Storage)e;
            int capacity = storage.info().itemCapacity;
            var own = new ItemModule(world().content().items());
            for(int item = 0; item < own.length(); item++){
                own.set(item, (int)Math.min(capacity, items.get(item) * (float)capacity / totalCapacity));
            }
            storage.unlink(own);
        }
        Teams teams = Teams.of(world());
        if(teams == null) return;
        teams.unregisterCore(this);
        List<Core> cores = teams.cores(team);
        for(int i = 0; i < cores.size(); i++) cores.get(i).onProximityUpdate();
    }

    /**
     * {@code CoreBuild.changeTeam(next)}: leaves its team as if removed, changes team ({@link Buildings#changeTeam}),
     * then joins the new one. Vanilla overrides {@code changeTeam} itself, so every team change of a core does this;
     * the building base has no such hook yet, so only changes made here do.
     */
    public void changeTeam(int next, Cause cause){
        if(team == next) return;
        onRemoved();
        world().buildings().changeTeam(this, next, cause);
        onProximityUpdate();
        Teams.coreChangeEvent(this, CoreChangeEvent.Change.TEAM);
    }

    /** {@code configured}: with {@code coreBuildAndConfig}, a team id moves the core (and its configuring player). */
    @Override
    public void configured(Unit builder, Object value){
        super.configured(builder, value);
        if(!rules().coreBuildAndConfig || !(value instanceof Integer id)) return;
        int next = Team.get(id).id();
        if(builder != null && builder.isPlayer()){
            builder.team = next;
            builder.player().team(next);
        }
        changeTeam(next, world().buildings().cause());
    }

    // spawning

    /** {@code canControlSelect(unit)} (CoreBlock.java:623): a player's unit may select its core. */
    @Override
    public boolean canControlSelect(Unit unit){
        return unit.isPlayer();
    }

    /**
     * {@code onControlSelect(unit)} (CoreBlock.java:628): the player leaves the unit and respawns here at once, its
     * death timer past the delay.
     */
    @Override
    public void onControlSelect(Unit unit){
        if(!unit.isPlayer() || !allowSpawn()) return;
        Player player = unit.player();
        player.clearUnit();
        player.deathTimer = Player.DEATH_DELAY + 1f;
        requestSpawn(player);
    }

    /**
     * {@code requestSpawn(player)}: nothing where the rules' environment doesn't support the unit type or the block
     * doesn't allow spawning, else {@link #playerSpawn}.
     */
    public void requestSpawn(Player player){
        if(!world().unitTable().params(unitType()).supportsEnv(rules().env) || !allowSpawn()) return;
        playerSpawn(this, player);
    }

    /**
     * {@code Call.playerSpawn(tile, player)} (CoreBlock.java:95) on a server: the player moves to the core and takes a
     * new unit of the block's type, created with its weapons reloaded, facing up, pushed up and marked as spawned by
     * the core, then added; then the call is published. The unit takes the player as its controller before the player
     * takes the unit, so the player doesn't keep the unit's command.
     */
    public static void playerSpawn(Core core, Player player){
        if(player == null || core == null || !core.isValid()) return;
        World world = core.world();
        UnitDef spawnType = core.unitType();
        player.x = core.x();
        player.y = core.y();
        Unit unit = Unit.create(world, spawnType, core.team);
        List<WeaponDef> weapons = spawnType.weapons();
        for(WeaponMount mount : unit.mounts) mount.reload = weapons.get(mount.weapon).reload();
        unit.set(core.x(), core.y());
        unit.rotation = 90f;
        unit.impulse(0f, 3f);
        unit.spawnedByCore = true;
        unit.controller(new PlayerControl(player));
        player.unit(unit);
        unit.add();
        world.publish(new CoreEvent.PlayerSpawn(world.ticks(), core.tile(), player.id(), unit.id()));
    }

    // state

    @Override
    public void write(CheckpointOut out){
        out.i("storageCapacity", storageCapacity).bool("noEffect", noEffect).i("lastDamage", lastDamage).f("iframes", iframes)
            .f("thrusterTime", thrusterTime).obj("commandPos", commandPos).ref("shared", shared);
    }

    @Override
    public void read(CheckpointIn in){
        storageCapacity = in.i("storageCapacity");
        noEffect = in.bool("noEffect");
        lastDamage = in.i("lastDamage");
        iframes = in.f("iframes");
        thrusterTime = in.f("thrusterTime");
        commandPos = in.obj("commandPos", Vec2.class);
        shared = in.ref("shared", SharedItems.class);
    }

    @Override
    public void restored(){
        if(shared != null) items = shared.module;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Core)into;
        out.storageCapacity = storageCapacity;
        out.noEffect = noEffect;
        out.lastDamage = lastDamage;
        out.iframes = iframes;
        out.thrusterTime = thrusterTime;
        out.commandPos = commandPos == null ? null : commandPos.cpy();
        out.shared = context.map(shared);
        if(out.shared != null) out.items = out.shared.module;
        out.unitType = unitType;
        out.allowSpawn = allowSpawn;
        out.incinerateNonBuildable = incinerateNonBuildable;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(storageCapacity).bool(noEffect).i(lastDamage).f(iframes).f(thrusterTime).bool(commandPos != null);
        if(commandPos != null) hasher.f(commandPos.x).f(commandPos.y);
        hasher.bool(shared != null);
    }
}
