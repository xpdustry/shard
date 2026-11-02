package com.xpdustry.shard.compat.v160.objectives;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.storage.Core;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.objectives.ObjectiveEvent;
import com.xpdustry.shard.sim.objectives.ObjectiveState;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.game.MapObjectives.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP74's map gate: each Erekir bundled map that ships objectives, loaded from its own save on both sides, played
 * through its objectives for {@value #FRAMES} frames with a vanilla save and load half way, frame by frame. A scripted
 * player acts on each objective as it comes up, the same on both sides: core items, items through blocks, finished
 * constructions, units, enemy units killed, the blocks and cores to destroy removed, flags set. After every frame the
 * objectives (their progress included, as JSON), the flags and the markers are vanilla's, and so are the objective,
 * marker and flag Calls, byte for byte.
 *
 * <p>Pins, so that the gate is about objectives: the map's units are removed and the wave timer is off on both sides
 * (waves are W2.11's and W2.17's gates); the objective timers run 100 times faster ({@code objectiveTimerMultiplier}
 * 0.01) so that the maps' long timers end within the run; a finished construction keeps both randoms as they were (its
 * place sound's draw is timed by the wall clock, W2.15). The rest of each world isn't compared: other families' gates
 * cover it.
 */
class ObjectiveMapsOracleTest{
    static final int FRAMES = 1200, SAVE_AT = 600;
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, BuildingCodecs.builtIn());
    static final ObjectivesJson JSON = new RulesJson(Worlds.EPOCH).objectives();
    static final Set<Integer> CALLS = Set.of(Packets.CompleteObjective.ID, Packets.ClearObjectives.ID, Packets.SetObjectives.ID,
        Packets.CreateMarker.ID, Packets.RemoveMarker.ID, Packets.UpdateMarker.ID, Packets.UpdateMarkerText.ID, Packets.UpdateMarkerTexture.ID,
        Packets.SetFlag.ID);
    /** Shard's Calls are filtered as vanilla's are: the maps' world processors also send Calls of other families. */
    static final Set<String> NAMES = CALLS.stream().map(id -> Packets.TYPES.get(id).name()).collect(java.util.stream.Collectors.toSet());
    /**
     * Maps a server can't take past their first objective, on vanilla too: marsh's is researching the oxidation chamber,
     * and a server's content is unlocked only when it's always unlocked ({@code UnlockableContent.unlocked}).
     */
    static final Set<String> STUCK = Set.of("erekir/marsh");
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(ObjectiveEvent.class, LogicEvent.class);

    static List<String> maps(){
        return List.of("erekir/aegis", "erekir/basin", "erekir/crossroads", "erekir/lake", "erekir/marsh", "erekir/onset", "erekir/origin",
            "erekir/peaks", "erekir/siege", "erekir/split", "erekir/stronghold");
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    World shard;
    com.xpdustry.shard.sim.world.Observer observer;
    final Set<Integer> acted = new TreeSet<>();
    final Set<Integer> used = new HashSet<>();
    final TreeMap<String, Integer> actions = new TreeMap<>();

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void theMapsObjectivesProgressAsVanillas(String map){
        byte[] file = BundledMaps.read(map);
        Sim.empty(1, 1);
        load(file, true);
        assertFalse(state.rules.objectives.all.isEmpty(), map + " ships objectives");
        int total = state.rules.objectives.all.size;
        for(int frame = 1; frame <= FRAMES; frame++){
            if(frame == SAVE_AT){
                var out = new ByteArrayOutputStream();
                SaveIO.write(out, new SaveOptions());
                observer.close();
                load(out.toByteArray(), false);
            }
            play();
            int before = Vanilla.captured().size();
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            shard.tick();
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            var calls = CallFrames.shard(shard, observer.drain()).stream().filter(line -> NAMES.contains(line.split(" ")[2])).toList();
            CallFrames.same(CallFrames.vanilla(sent, CALLS), calls, map + " Calls in frame " + frame);
            same(map + " after frame " + frame);
        }
        int done = 0;
        for(var objective : shard.part(WorldRules.class).rules.objectives.all) if(objective.completed) done++;
        System.out.println(map + ": " + done + " of " + total + " objectives done in " + FRAMES + " frames, through a save; actions " + actions);
        if(STUCK.contains(map)) assertEquals(0, done, map + ": stuck at its first objective, as on vanilla");
        else assertTrue(done > 0, map + ": some objective completed");
    }

    /** Both sides load the save in {@code file} ({@code zlib}: a map file), unit-free and wave-free. */
    void load(byte[] file, boolean deflated){
        Mathf.rand.setSeed(0);
        try{
            SaveIO.load(deflated ? new InflaterInputStream(new ByteArrayInputStream(file)) : new ByteArrayInputStream(file), world.context);
        }catch(SaveIO.SaveException e){
            throw new AssertionError(e);
        }
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        byte[] raw = deflated ? inflate(file) : file;
        SaveFile save = READER.read(raw);
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        Time.setInternalTime(shard.timers().time());
        for(mindustry.gen.Unit unit : Groups.unit.copy(new Seq<>())) unit.remove();
        var units = new ArrayList<com.xpdustry.shard.sim.unit.Unit>();
        for(int i = 0; i < shard.units().size(); i++) units.add(shard.units().get(i));
        for(var unit : units) unit.remove();
        Rules rules = shard.part(WorldRules.class).rules;
        state.rules.waveTimer = rules.waveTimer = false;
        state.rules.objectiveTimerMultiplier = rules.objectiveTimerMultiplier = 0.01f;
        observer = shard.observe(4096, EVENTS, null);
        Vanilla.captured().clear();
        same("loaded");
    }

    static byte[] inflate(byte[] file){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(file))){
            return in.readAllBytes();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    void same(String at){
        Rules rules = shard.part(WorldRules.class).rules;
        assertEquals(JsonIO.write(state.rules.objectives), JSON.writeObjectives(rules.objectives), at + ": objectives");
        var flags = new TreeSet<String>();
        for(String flag : state.rules.objectiveFlags) flags.add(flag);
        assertEquals(flags, rules.objectiveFlags, at + ": flags");
        try{
            var out = new ByteArrayOutputStream();
            state.markers.write(new DataOutputStream(out));
            assertArrayEquals(out.toByteArray(), JSON.writeMarkers(ObjectiveState.of(shard).markers), at + ": markers");
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- the scripted player

    /** Acts once on each objective that has come up, in order, the same on both sides. */
    void play(){
        var all = state.rules.objectives.all;
        for(int i = 0; i < all.size; i++){
            MapObjective objective = all.get(i);
            if(!objective.qualified() || !acted.add(i)) continue;
            switch(objective){
                case ItemObjective o -> {
                    var core = state.rules.defaultTeam.core();
                    Core shardCore = Teams.of(shard).core(state.rules.defaultTeam.id);
                    if(core == null || shardCore == null) continue;
                    core.items.add(o.item, o.amount);
                    shardCore.items.add(o.item.id, o.amount);
                    act("item");
                }
                case CoreItemObjective o -> {
                    var core = state.rules.defaultTeam.core();
                    Core shardCore = Teams.of(shard).core(state.rules.defaultTeam.id);
                    if(core == null || shardCore == null) continue;
                    for(int n = 0; n < o.amount; n++){
                        core.handleItem(null, o.item);
                        shardCore.handleItem(null, o.item.id);
                    }
                    act("core item");
                }
                case BuildCountObjective o -> {
                    for(int n = state.stats.placedBlockCount.get(o.block, 0); n < o.count; n++){
                        Tile tile = freeSpot(o.block.size);
                        if(tile == null) break;
                        constructFinish(tile.x, tile.y, o.block, state.rules.defaultTeam);
                        act("build " + o.block.name);
                    }
                }
                case UnitCountObjective o -> {
                    var core = state.rules.defaultTeam.core();
                    if(core == null) continue;
                    for(int n = state.rules.defaultTeam.data().countType(o.unit); n < o.count; n++){
                        spawn(o.unit.name, state.rules.defaultTeam, core.x, core.y);
                        act("unit " + o.unit.name);
                    }
                }
                case DestroyUnitsObjective o -> {
                    for(int n = state.stats.enemyUnitsDestroyed; n < o.count; n++){
                        spawn("dagger", state.rules.waveTeam, 8f, 8f);
                        Vanilla.net(Vanilla.NetMode.server, () -> Groups.unit.find(u -> u.team == state.rules.waveTeam).kill());
                        var unit = shard.units().get(shard.units().size() - 1);
                        unit.kill();
                        act("enemy killed");
                    }
                }
                case DestroyBlockObjective o -> {
                    remove(o.pos.x, o.pos.y);
                    act("block destroyed");
                }
                case DestroyBlocksObjective o -> {
                    for(var pos : o.positions) remove(pos.x, pos.y);
                    act("blocks destroyed");
                }
                case DestroyCoreObjective o -> {
                    for(var core : state.rules.waveTeam.cores().copy()) remove(core.tile.x, core.tile.y);
                    act("cores destroyed");
                }
                case FlagObjective o -> {
                    state.rules.objectiveFlags.add(o.flag);
                    shard.part(WorldRules.class).rules.objectiveFlags.add(o.flag);
                    act("flag");
                }
                default -> {}
            }
        }
    }

    void act(String what){
        actions.merge(what, 1, Integer::sum);
    }

    /** The first spot, row by row, where a block of {@code size} has open, shallow floor and nothing built. */
    Tile freeSpot(int size){
        int offset = -(size - 1) / 2;
        for(int y = 2; y < world.height() - size - 2; y += size + 1){
            next:
            for(int x = 2; x < world.width() - size - 2; x += size + 1){
                for(int dx = 0; dx < size; dx++){
                    for(int dy = 0; dy < size; dy++){
                        Tile t = world.tile(x + dx, y + dy);
                        if(t == null || t.block() != Blocks.air || t.floor().isDeep() || t.floor().solid || used.contains(t.pos())) continue next;
                    }
                }
                for(int dx = 0; dx < size; dx++) for(int dy = 0; dy < size; dy++) used.add(world.tile(x + dx, y + dy).pos());
                return world.tile(x - offset, y - offset);
            }
        }
        return null;
    }

    void constructFinish(int x, int y, mindustry.world.Block block, Team team){
        long s0 = Mathf.rand.seed0, s1 = Mathf.rand.seed1;
        ConstructBlock.constructFinish(world.tile(x, y), block, null, (byte)0, team, null);
        Vanilla.runPosted();
        Mathf.rand.setState(s0, s1);
        long r0 = shard.rand().getState(0), r1 = shard.rand().getState(1);
        com.xpdustry.shard.sim.build.Build.constructFinish(shard, shard.tiles().pos(x, y), Worlds.EPOCH.block(block.name), null, 0, team.id, null);
        shard.rand().setState(r0, r1);
    }

    void spawn(String type, Team team, float x, float y){
        var v = content.unit(type).create(team);
        v.controller(new NoAI());
        v.set(x, y);
        v.add();
        var s = com.xpdustry.shard.sim.unit.Unit.create(shard, Worlds.EPOCH.unit(type), team.id);
        s.controller(new com.xpdustry.shard.sim.unit.NoAi());
        s.set(x, y);
        s.add();
    }

    void remove(int x, int y){
        Tile tile = world.tile(x, y);
        if(tile == null) return;
        tile.setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, Worlds.EPOCH.block("air"), 0, 0, Cause.SERVER);
    }
}
