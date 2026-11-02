package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code state.teams} (Teams.java): each team's {@link TeamData}, the active teams, and the queries over their
 * cores. Cores register themselves on every proximity update and unregister when their tile changes
 * ({@link Core#onProximityUpdate()}, {@link Core#onRemoved()}), so the lists are vanilla's history: registration
 * order, kept through forks and checkpoints. The active list starts with crux, as vanilla's constructor puts it, and
 * {@link #getActive} drops teams that stopped being active, as vanilla does whenever it is read.
 *
 * <p>It is also the world's {@link PlayerSpawns}: a dead player respawns at {@link #bestCore}; and the spawner's
 * {@link WaveCores}: cores in registration order, {@code isActive}, and each core's {@code commandPos}.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: every team's cores, last core and enemies, and the active list.
 *
 * <p>Owner: WP23.
 */
@Register
public final class Teams implements WorldPart, Checkpointable, PlayerSpawns, WaveCores{
    private final TeamData[] map = new TeamData[Team.COUNT];
    /** {@code active}: team ids, in vanilla's order. */
    private final IntSeq active = new IntSeq();
    /**
     * Whether a game is being played on the world ({@code state.isGame()} once {@code logic.play()} ran,
     * {@code Play.play}): only then can it end ({@code CheckGameState}). Worlds built for a test never started one, as
     * the oracle's worlds turn {@code canGameOver} off.
     */
    private boolean playing;
    /**
     * Destroyed cores whose {@code CoreChangeEvent} check vanilla posted to the next frame ({@code Core.app.post}),
     * in order: {@link DeadTeams} runs them.
     */
    final List<Core> coreChanges = new ArrayList<>();
    /**
     * {@code NetServer.pvpAutoPaused}: whether the world was paused by {@link PvpAutoPause}, which then may resume it.
     * Vanilla quirk: it starts true.
     */
    boolean pvpAutoPaused = true;
    /**
     * The server's {@code Config.autoPause} for this world: whether it pauses while nobody is in it
     * ({@link EmptyAutoPause}). Set by the host from {@code game.auto-pause} ({@link #autoPause(boolean)}).
     */
    boolean autoPause;
    /**
     * {@code ServerControl.autoPaused}: whether the world was paused by {@link EmptyAutoPause}, which then may resume
     * it. Apart from {@link #pvpAutoPaused}, as vanilla's two flags are.
     */
    boolean autoPaused;
    /** The teams whose cores aren't empty, as bits: {@link #updateStats} visits these, not all 256 teams. */
    @Derived("from each team's cores")
    private final long[] cored = new long[(Team.COUNT + 63) >>> 6];

    public Teams(){
        for(int team = 0; team < map.length; team++) map[team] = new TeamData(team);
        active.add(Team.CRUX);
    }

    private Teams(Teams source, CopyContext context){
        for(int team = 0; team < map.length; team++) map[team] = source.map[team].copy(context);
        active.addAll(source.active);
        playing = source.playing;
        pvpAutoPaused = source.pvpAutoPaused;
        autoPause = source.autoPause;
        autoPaused = source.autoPaused;
        for(Core core : source.coreChanges) coreChanges.add(context.map(core));
        for(TeamData data : map) cored(data);
    }

    /** The world's teams, or null in a world built without them. */
    public static Teams of(World world){
        return world.find(Teams.class);
    }

    /** {@code get(team)}: never null. */
    public TeamData get(int team){
        return map[team & 0xff];
    }

    /** {@code cores(team)}: the live cores in registration order. Do not modify. */
    public List<Core> cores(int team){
        return get(team).cores();
    }

    @Override
    public List<Building> cores(World world, int team){
        return Collections.unmodifiableList(get(team).cores());
    }

    @Override
    public Vec2 commandPos(Building core){
        return core instanceof Core c ? c.commandPos : null;
    }

    /** {@code team.core()}: the first core, or null. */
    public Core core(int team){
        return get(team).core();
    }

    /** {@code playerCores()}: the default team's cores. */
    public List<Core> playerCores(World world){
        return cores(UnitCalls.rules(world).defaultTeam);
    }

    /** {@code isActive(team)}: the wave team while waves are on, or a team with a core or building. */
    @Override
    public boolean isActive(World world, int team){
        return get(team).active(world);
    }

    /** Whether a game was started on the world ({@link #playing}). */
    public boolean playing(){
        return playing;
    }

    /** Marks the world's game as started: {@code logic.play()}. */
    public void play(){
        playing = true;
    }

    /**
     * {@code Events.fire(new CoreChangeEvent(core))} from a core's {@code onDestroyed}: {@code Logic}'s listener posts
     * its dead-team check to the next frame ({@link DeadTeams}).
     */
    public void coreChanged(Core core){
        coreChanges.add(core);
    }

    /** {@code Events.fire(new CoreChangeEvent(core))}: publishes {@link CoreChangeEvent} for observers. */
    public static void coreChangeEvent(Core core, CoreChangeEvent.Change change){
        World world = core.world();
        world.publish(new CoreChangeEvent(world.ticks(), core.pos(), core.block().id(), core.team, change));
    }

    /** {@code NetServer.pvpAutoPaused} ({@link PvpAutoPause}). */
    public boolean pvpAutoPaused(){
        return pvpAutoPaused;
    }

    /** {@code Config.autoPause} for this world ({@link EmptyAutoPause}). */
    public boolean autoPause(){
        return autoPause;
    }

    /** Sets {@code Config.autoPause} for this world: from the next {@link EmptyAutoPause} check on. */
    public void autoPause(boolean autoPause){
        this.autoPause = autoPause;
    }

    /** {@code ServerControl.autoPaused} ({@link EmptyAutoPause}). */
    public boolean autoPaused(){
        return autoPaused;
    }

    /** The console's {@code pause} clears {@code autoPaused} (ServerControl.java:688), so the pause made by hand stays. */
    public void clearAutoPaused(){
        autoPaused = false;
    }

    /** {@code canInteract(team, other)}. */
    public static boolean canInteract(int team, int other){
        return team == other || other == Team.DERELICT;
    }

    /** {@code getActive()}: drops the teams that stopped being active, then the active teams in order. */
    public IntSeq getActive(World world){
        return activeView(world).copy();
    }

    /**
     * {@link #getActive}, without the copy: the live list, for a loop that changes no team's cores or activity. Vanilla
     * hands out its own list too ({@code Teams.getActive}); the copy cost every world a list per tick ({@code CoreFill}).
     */
    public IntSeq activeView(World world){
        for(int i = 0; i < active.size; i++){
            if(!get(active.get(i)).active(world)) active.removeIndex(i--);
        }
        return active;
    }

    /** {@code active}, as vanilla's fields read it: the active teams in order, without dropping the inactive ones. */
    public IntSeq activeTeams(){
        return active.copy();
    }

    /** {@code updateActive(team)}: adds an active team that isn't listed yet. */
    public void updateActive(World world, int team){
        if(get(team).active(world) && !active.contains(team)){
            active.add(team);
            updateEnemies(world);
        }
    }

    /** {@code registerCore(core)}: under its team, once; the team becomes active. */
    public void registerCore(Core core){
        World world = core.world();
        TeamData data = get(core.team);
        if(!data.cores.contains(core)) data.cores.add(core);
        cored(data);
        if(data.active(world) && !active.contains(data.team)){
            active.add(data.team);
            updateEnemies(world);
        }
    }

    /** {@code unregisterCore(core)}: off its team's list; a team no longer active leaves the active list. */
    public void unregisterCore(Core core){
        World world = core.world();
        TeamData data = get(core.team);
        data.cores.remove(core);
        cored(data);
        if(!data.active(world)){
            active.removeValue(data.team);
            updateEnemies(world);
        }
    }

    /** Teams.java:254: the wave team joins while waves are on, then every active team's enemies are the others. */
    private void updateEnemies(World world){
        Rules rules = UnitCalls.rules(world);
        if(rules.waves && !active.contains(rules.waveTeam)) active.add(rules.waveTeam);
        for(int i = 0; i < active.size; i++){
            var enemies = new IntSeq();
            for(int j = 0; j < active.size; j++){
                if(active.get(j) != active.get(i)) enemies.add(active.get(j));
            }
            get(active.get(i)).coreEnemies = Arrays.copyOf(enemies.items, enemies.size);
        }
    }

    /** {@code updateTeamStats()}, the cores' part: each team's last core is its first. */
    void updateStats(){
        for(int word = 0; word < cored.length; word++){
            for(long bits = cored[word]; bits != 0; bits &= bits - 1){
                TeamData data = map[word << 6 | Long.numberOfTrailingZeros(bits)];
                if(!data.cores.isEmpty()) data.lastCore = data.cores.getFirst();
            }
        }
    }

    /** Keeps {@link #cored} in step with {@code data}'s cores. */
    private void cored(TeamData data){
        long bit = 1L << data.team;
        if(data.cores.isEmpty()) cored[data.team >>> 6] &= ~bit;
        else cored[data.team >>> 6] |= bit;
    }

    // queries

    /** {@code closestCore(x, y, team)}: the nearest of the team's cores, the first on ties. */
    public Core closestCore(float x, float y, int team){
        Core closest = null;
        float closestDst = 0f;
        for(Core core : get(team).cores){
            float dst = dst2(core, x, y);
            if(closest == null || dst < closestDst){
                closest = core;
                closestDst = dst;
            }
        }
        return closest;
    }

    /** {@code closestEnemyCore(x, y, team)}: the nearest core of the team's enemies. */
    public Core closestEnemyCore(float x, float y, int team){
        Core closest = null;
        float closestDst = Float.MAX_VALUE;
        for(int enemy : get(team).coreEnemies){
            for(Core core : get(enemy).cores){
                float dst = dst2(core, x, y);
                if(closestDst > dst){
                    closest = core;
                    closestDst = dst;
                }
            }
        }
        return closest;
    }

    /** {@code anyEnemyCoresWithin(team, x, y, radius)}: another active team protecting its cores has one within it. */
    public boolean anyEnemyCoresWithin(World world, int team, float x, float y, float radius){
        Rules rules = UnitCalls.rules(world);
        for(int i = 0; i < active.size; i++){
            int other = active.get(i);
            if(team == other || !rules.teams.get(other).protectCores) continue;
            for(Core core : get(other).cores){
                if(core.within(x, y, radius)) return true;
            }
        }
        return false;
    }

    /** {@code anyEnemyCoresWithinBuildRadius(team, x, y)}: as {@link #anyEnemyCoresWithin}, at each core's build radius. */
    public boolean anyEnemyCoresWithinBuildRadius(World world, int team, float x, float y){
        Rules rules = UnitCalls.rules(world);
        for(int i = 0; i < active.size; i++){
            int other = active.get(i);
            if(team == other || !rules.teams.get(other).protectCores) continue;
            for(Core core : get(other).cores){
                if(core.within(x, y, rules.buildRadius(core.team) + Footprint.TILE_SIZE)) return true;
            }
        }
        return false;
    }

    /**
     * {@code player.bestCore()} (PlayerComp.java:121): the largest of the team's cores whose unit type the rules'
     * environment supports (any, when there is one core), the nearest among equals, the first on ties.
     */
    public Core bestCore(Player player){
        World world = player.world();
        List<Core> cores = get(player.team).cores;
        int env = UnitCalls.rules(world).env;
        Core best = null;
        for(Core core : cores){
            if(!(cores.size() == 1 || world.unitTable().params(core.unitType()).supportsEnv(env))) continue;
            if(best == null || compare(best, core, player) > 0) best = core;
        }
        return best;
    }

    /** {@code Structs.comps(comparingInt(c -> -c.block.size), comparingFloat(c -> c.dst2(x, y)))}. */
    private static int compare(Core a, Core b, Player player){
        int bySize = Integer.compare(-a.block().size(), -b.block().size());
        return bySize != 0 ? bySize : Float.compare(dst2(a, player.x, player.y), dst2(b, player.x, player.y));
    }

    /** Arc's {@code Position.dst2(x, y)} from a building's centre. */
    private static float dst2(Building building, float x, float y){
        float dx = building.x() - x, dy = building.y() - y;
        return dx * dx + dy * dy;
    }

    // PlayerSpawns

    @Override
    public boolean hasCore(Player player){
        return bestCore(player) != null;
    }

    @Override
    public void requestSpawn(Player player){
        Core core = bestCore(player);
        if(core != null) core.requestSpawn(player);
    }

    // state

    @Override
    public WorldPart copy(CopyContext context){
        return new Teams(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.ints(active.items, 0, active.size);
        // only a started game enters the hash, which worlds without one keep as it was
        if(playing) hasher.bool(true);
        // only a cleared auto-pause flag enters the hash, which worlds that never paused keep as they were
        if(!pvpAutoPaused) hasher.bool(false);
        // only a world with the server's auto-pause on enters the hash, which others keep as they were
        if(autoPause || autoPaused) hasher.i(0x4155_5450).bool(autoPause).bool(autoPaused);
        // only a posted core change enters the hash, which worlds without one keep as they were
        if(!coreChanges.isEmpty()){
            hasher.i(coreChanges.size());
            for(Core core : coreChanges) hasher.i(core.id());
        }
        for(TeamData data : map){
            if(!data.any()) continue;
            hasher.i(data.team).i(data.cores.size());
            for(Core core : data.cores) hasher.i(core.id());
            hasher.i(data.lastCore == null ? -1 : data.lastCore.id()).ints(data.coreEnemies);
            // a team's rebuild plans enter the hash only once it has some, which worlds without any keep as they were
            if(!data.plans.isEmpty()){
                hasher.i(data.plans.size());
                for(BlockPlan plan : data.plans) plan.hash(hasher);
            }
        }
    }

    /** The active list, then every team's data. */
    @Override
    public void write(CheckpointOut out){
        out.ints("active", active.items, active.size).list("map", Arrays.asList(map)).bool("playing", playing)
            .refs("coreChanges", coreChanges.toArray(Core[]::new), coreChanges.size()).bool("pvpAutoPaused", pvpAutoPaused)
            .bool("autoPause", autoPause).bool("autoPaused", autoPaused);
    }

    @Override
    public void read(CheckpointIn in){
        int[] stored = in.ints("active");
        List<TeamData> data = in.list("map", TeamData.class);
        if(stored == null || data == null || data.size() != map.length) throw new CheckpointException("the team data is inconsistent");
        active.clear();
        active.addAll(stored, 0, stored.length);
        playing = in.bool("playing");
        Core[] changes = in.refs("coreChanges", Core.class);
        if(changes == null || Arrays.asList(changes).contains(null)) throw new CheckpointException("a posted core change has no core");
        coreChanges.clear();
        coreChanges.addAll(Arrays.asList(changes));
        pvpAutoPaused = in.bool("pvpAutoPaused");
        autoPause = in.bool("autoPause");
        autoPaused = in.bool("autoPaused");
        for(int team = 0; team < map.length; team++){
            TeamData read = data.get(team);
            if(read == null || read.team != team) throw new CheckpointException("team " + team + "'s data is missing");
            map[team] = read;
            cored(read);
        }
    }
}
