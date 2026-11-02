package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code WaveSpawner} (WP47): the ground spawn points, where each wave's units appear, the shockwave that
 * clears each drop zone, and the push that keeps other teams' units out of them ({@link DropZones}).
 *
 * <p>Spawn points are the tiles whose overlay is {@code spawn}, in an unordered {@code Seq}: tile order at load
 * ({@link #reset}), appended when an overlay becomes a spawn, swap-removed when it stops being one. Vanilla tracks
 * overlay changes with {@code TileOverlayChangeEvent} as they happen; Shard reads the tick's tile change list in
 * {@code WAVES}, so a spawn placed mid-tick joins before that tick's wave but after the {@code UNITS} phase of the
 * tick before. A world that never had {@link #reset} called scans its tiles the first time it needs the list.
 *
 * <p>A wave ({@link #spawnEnemies}): a shockwave at every ground spawn point, then each spawn group in rule order, on
 * every point it may use. Ground units land within two tiles of a ground point (a random direction each); flying
 * units come from the map edge behind each point, or from the point itself with {@code airUseSpawns}. In attack mode
 * with {@code wavesSpawnAtCores}, the wave team's cores are points too: flyers on the core, ground units just outside
 * it toward the first player core, past solid tiles. Each unit is made as {@code SpawnGroup.createUnit}: created at
 * its point facing the map centre, added, given the spawn effects (30 ticks unmoving, 60 invincible), then the group's
 * effect, items, shield and payloads.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the spawn list in its order, whether it was scanned, and the spawning flag
 * (held for 121 ticks after a wave by a timer).
 */
@Register
public final class WaveSpawner implements WorldPart, Checkpointable, DropZones{
    private static final float TILESIZE = 8f, CORE_MARGIN = TILESIZE * 2f, MAX_STEPS = 30;
    /** {@code WaveSpawner.margin}: how far past the map edge flyers start. */
    private static final float MARGIN = 0f;
    /** {@code Time.run(121f, () -> spawning = false)}. */
    static final float SPAWNING_TIME = 121f;

    /** Spawn tile positions ({@code y * width + x}), unordered as vanilla's {@code Seq<>(false)}. */
    private IntSeq spawns = new IntSeq(false, 16);
    private boolean scanned;
    private boolean spawning;

    /** The world's spawner, or null in a world built without it. */
    public static WaveSpawner of(World world){
        return world.find(WaveSpawner.class);
    }

    // region spawn points

    /**
     * {@code reset()} on {@code WorldLoadEvent}: forgets the spawning flag and lists every spawn tile in tile order.
     * Map loaders call it after {@code endLoad}.
     */
    public void reset(World world){
        spawning = false;
        spawns.clear();
        int spawn = spawnBlock(world);
        TileGrid tiles = world.tiles();
        if(spawn != -1){
            for(int pos = 0; pos < tiles.size(); pos++){
                if(tiles.overlay(pos) == spawn) spawns.add(pos);
            }
        }
        scanned = true;
    }

    /** {@code getSpawns()}: the spawn tile positions, in the spawner's order. Don't modify them. */
    public IntSeq spawns(World world){
        if(!scanned){
            // a world that was never reset: its tiles are what a load would have seen
            boolean wasSpawning = spawning;
            reset(world);
            spawning = wasSpawning;
        }
        return spawns;
    }

    /** {@code countSpawns()}. */
    public int countSpawns(World world){
        return spawns(world).size;
    }

    /** {@code TileOverlayChangeEvent}: the tick's overlay changes, from and to {@code spawn}. */
    void tileChanges(World world){
        TileGrid tiles = world.tiles();
        if(tiles.changeCount() == 0) return;
        int spawn = spawnBlock(world);
        if(spawn == -1) return;
        IntSeq list = spawns(world);
        tiles.changes((pos, before) -> {
            boolean was = TileState.overlay(before) == spawn, is = tiles.overlay(pos) == spawn;
            if(was && !is){
                list.removeValue(pos);
            }else if(!was && is && !list.contains(pos)){
                list.add(pos);
            }
        });
    }

    private static int spawnBlock(World world){
        return world.content().id(ContentType.BLOCK, "spawn");
    }

    /** {@code state.hasSpawns()}: waves are on, with spawn groups, or with wave team cores in attack mode. */
    public static boolean hasSpawns(World world){
        Rules rules = UnitCalls.rules(world);
        return rules.waves && (!rules.spawns.isEmpty() || (rules.attackMode && !WaveCores.of(world).cores(world, rules.waveTeam).isEmpty()));
    }

    /** {@code isSpawning()}: a wave spawned less than 121 ticks ago. */
    public boolean isSpawning(){
        return spawning;
    }

    // endregion

    // region waves

    /** {@code spawnEnemies()}: the shockwaves, then every group's units for the current wave. */
    public void spawnEnemies(World world){
        spawning = true;
        Rules rules = UnitCalls.rules(world);
        WaveState state = WaveState.of(world);
        int wave = (state == null ? 1 : state.wave) - 1;

        eachGroundSpawn(world, -1, (x, y, shockwave) -> {
            if(shockwave) doShockwave(world, x, y);
        });

        Rand rand = world.rand();
        for(SpawnGroup group : rules.spawns){
            if(group.type < 0 || group.type >= world.content().units()) continue;
            int spawned = group.getSpawned(wave);
            if(spawned == 0) continue;
            // the campaign's difficulty multiplier doesn't apply: Shard hosts no campaign
            UnitDef type = world.content().unit(group.type);
            if(type.flying()){
                float spread = MARGIN / 1.5f;
                eachFlyerSpawn(world, group.spawn, (x, y) -> {
                    for(int i = 0; i < spawned; i++){
                        // the x offset is drawn first: arguments evaluate left to right
                        float ox = Mathf.range(rand, spread);
                        float oy = Mathf.range(rand, spread);
                        spawnUnit(world, group, x + ox, y + oy);
                    }
                });
            }else{
                float spread = TILESIZE * 2;
                eachGroundSpawn(world, group.spawn, (x, y, shockwave) -> {
                    for(int i = 0; i < spawned; i++){
                        var offset = new Vec2().setToRandomDirection(rand).scl(spread);
                        spawnUnit(world, group, x + offset.x, y + offset.y);
                    }
                });
            }
        }

        world.timers().run(SPAWNING_TIME, new SpawningDone());
    }

    /**
     * {@code spawnUnit(group, x, y)}: one of {@code group}'s units for the current wave, on the group's team or the wave
     * team, facing the map centre. World processors' {@code spawnwave} uses it too.
     */
    public Unit spawnUnit(World world, SpawnGroup group, float x, float y){
        Rules rules = UnitCalls.rules(world);
        WaveState state = WaveState.of(world);
        int team = group.team == -1 ? rules.waveTeam : group.team;
        float rotation = Angles.angle(x, y, world.tiles().width() / 2f * TILESIZE, world.tiles().height() / 2f * TILESIZE);
        return createUnit(world, group, team, x, y, rotation, (state == null ? 1 : state.wave) - 1);
    }

    /** {@code SpawnGroup.createUnit(team, x, y, rotation, wave, spawnEffect)}. */
    static Unit createUnit(World world, SpawnGroup group, int team, float x, float y, float rotation, int wave){
        UnitDef type = world.content().unit(group.type);
        Unit unit = Unit.spawn(world, type, team, x, y, rotation);
        spawnEffect(unit);

        StatusTable statuses = StatusTable.of(world);
        if(statuses.exists(group.effect)) unit.statuses.apply(unit, group.effect, 999999f);

        if(group.items != null){
            // ItemsComp.addItem(item, amount)
            int item = group.items.item(), amount = group.items.amount();
            unit.stackAmount = unit.stackItem == item ? unit.stackAmount + amount : amount;
            unit.stackItem = item;
            unit.stackAmount = Mathf.clamp(unit.stackAmount, 0, unit.params().itemCapacity());
        }

        unit.shield = group.getShield(wave);

        if(group.payloads != null && unit.payloads != null){
            for(int payload : group.payloads){
                if(payload < 0 || payload >= world.content().units()) continue;
                Unit carried = Unit.create(world, world.content().unit(payload), unit.team);
                // PayloadComp.pickup(unit): a unit that was never added isn't counted or removed
                unit.payloads.add(new Payload.UnitPayload(carried));
                // Sounds.payloadPickup.at(self(), Mathf.random(0.9f, 1.1f)): the pitch draws even headless
                Mathf.random(world.rand(), 0.9f, 1.1f);
            }
        }
        return unit;
    }

    /**
     * {@code spawnEffect(unit)}: unmoving for 30 ticks, invincible for 60, {@code unloaded()}, then
     * {@code Call.spawnEffect}. World processors' {@code spawn} uses it too.
     */
    public static void spawnEffect(Unit unit){
        World world = unit.world();
        StatusTable statuses = StatusTable.of(world);
        unit.statuses.apply(unit, statuses.id("unmoving"), 30f);
        unit.statuses.apply(unit, statuses.id("invincible"), 60f);
        // a legged unit's legs reset, which draws from the random
        unit.unloaded();
        // the call's local handler only plays effects and posts a 30-tick timer for another
        world.publish(new WaveEvent.UnitSpawned(unit.id(), unit.x, unit.y, unit.rotation, unit.type().id()));
    }

    /**
     * {@code doShockwave(x, y)}: {@code Damage.damage(waveTeam, x, y, dropZoneRadius, 99999999, true)}, which kills
     * every enemy unit in the drop zone and destroys every enemy building in it.
     */
    static void doShockwave(World world, float x, float y){
        Rules rules = UnitCalls.rules(world);
        com.xpdustry.shard.sim.combat.Damage.damage(world, rules.waveTeam, x, y, rules.dropZoneRadius, 99999999f, true);
    }

    // endregion

    // region spawn point iteration

    /** {@code eachGroundSpawn(cons)}: every ground point, as tile coordinates. */
    public void eachGroundSpawn(World world, Geometry.IntPair cons){
        eachGroundSpawn(world, -1, (x, y, shockwave) -> cons.get(Math.round(x / TILESIZE), Math.round(y / TILESIZE)));
    }

    /** {@code getFirstSpawn()}: the last ground point visited, as a tile position, or -1. Vanilla quirk: the last, not the first. */
    public int firstSpawn(World world){
        int[] last = {-1};
        eachGroundSpawn(world, (x, y) -> last[0] = world.tiles().inBounds(x, y) ? world.tiles().pos(x, y) : -1);
        return last[0];
    }

    /** {@code countGroundSpawns()}. */
    public int countGroundSpawns(World world){
        int[] count = {0};
        eachGroundSpawn(world, (x, y) -> count[0]++);
        return count[0];
    }

    /** {@code countFlyerSpawns()}. */
    public int countFlyerSpawns(World world){
        int[] count = {0};
        eachFlyerSpawn(world, -1, (x, y) -> count[0]++);
        return count[0];
    }

    /** Where a spawn point is, and whether a shockwave clears it (ground spawn tiles only). */
    interface SpawnConsumer{
        void accept(float x, float y, boolean shockwave);
    }

    /** Where a flying spawn point is. */
    interface FlyerConsumer{
        void accept(float x, float y);
    }

    /**
     * {@code eachGroundSpawn(filterPos, cons)}: the spawn tiles when the world has spawns, then, in attack mode with
     * {@code wavesSpawnAtCores}, each wave team core while a player core exists: its command position, or the first
     * spot toward the first player core with no solid tile within 3 tiles, at most 30 steps of 1.1 tiles out.
     */
    void eachGroundSpawn(World world, int filterPos, SpawnConsumer cons){
        Rules rules = UnitCalls.rules(world);
        TileGrid tiles = world.tiles();
        if(hasSpawns(world)){
            IntSeq list = spawns(world);
            for(int i = 0; i < list.size; i++){
                int pos = list.items[i];
                int tx = tiles.x(pos), ty = tiles.y(pos);
                if(filterPos != -1 && filterPos != Point2.pack(tx, ty)) continue;
                cons.accept(tx * TILESIZE, ty * TILESIZE, true);
            }
        }

        WaveCores teams = WaveCores.of(world);
        if(!rules.wavesSpawnAtCores || !rules.attackMode || !teams.isActive(world, rules.waveTeam)) return;
        List<Building> playerCores = teams.cores(world, rules.defaultTeam);
        if(playerCores.isEmpty()) return;
        Building firstCore = playerCores.getFirst();
        for(Building core : List.copyOf(teams.cores(world, rules.waveTeam))){
            if(filterPos != -1 && filterPos != core.pos()) continue;
            Vec2 command = teams.commandPos(core);
            if(command != null){
                cons.accept(command.x, command.y, false);
                continue;
            }
            var offset = new Vec2(firstCore.x(), firstCore.y()).sub(core.x(), core.y())
                .limit(CORE_MARGIN + core.block().size() * TILESIZE / 2f * Mathf.sqrt2);
            boolean valid = false;
            int steps = 0;
            // keep moving out until nothing solid is near, or the steps run out
            while(steps++ < MAX_STEPS){
                int tx = Math.round((core.x() + offset.x) / TILESIZE), ty = Math.round((core.y() + offset.y) / TILESIZE);
                boolean[] any = {false};
                Geometry.circle(tx, ty, tiles.width(), tiles.height(), 3, (cx, cy) -> {
                    if(tiles.solid(tiles.pos(cx, cy))) any[0] = true;
                });
                if(!any[0]){
                    valid = true;
                    break;
                }
                offset.setLength(offset.len() + TILESIZE * 1.1f);
            }
            if(valid) cons.accept(core.x() + offset.x, core.y() + offset.y, false);
        }
    }

    /**
     * {@code eachFlyerSpawn(filterPos, cons)}: for each spawn tile, the map edge behind it seen from the centre (or the
     * tile itself with {@code airUseSpawns}); then, in attack mode with {@code wavesSpawnAtCores}, each wave team core.
     */
    void eachFlyerSpawn(World world, int filterPos, FlyerConsumer cons){
        Rules rules = UnitCalls.rules(world);
        TileGrid tiles = world.tiles();
        int width = tiles.width(), height = tiles.height();
        IntSeq list = spawns(world);
        for(int i = 0; i < list.size; i++){
            int pos = list.items[i];
            int tx = tiles.x(pos), ty = tiles.y(pos);
            if(filterPos != -1 && filterPos != Point2.pack(tx, ty)) continue;
            if(!rules.airUseSpawns){
                float angle = Angles.angle(width / 2f, height / 2f, tx, ty);
                float trns = Math.max(width, height) * Mathf.sqrt2 * TILESIZE;
                float x = Mathf.clamp(width * TILESIZE / 2f + Angles.trnsx(angle, trns), -MARGIN, width * TILESIZE + MARGIN);
                float y = Mathf.clamp(height * TILESIZE / 2f + Angles.trnsy(angle, trns), -MARGIN, height * TILESIZE + MARGIN);
                cons.accept(x, y);
            }else{
                cons.accept(tx * TILESIZE, ty * TILESIZE);
            }
        }

        WaveCores teams = WaveCores.of(world);
        if(rules.wavesSpawnAtCores && rules.attackMode && teams.isActive(world, rules.waveTeam)){
            for(Building core : List.copyOf(teams.cores(world, rules.waveTeam))){
                if(filterPos != -1 && filterPos != core.pos()) continue;
                cons.accept(core.x(), core.y());
            }
        }
    }

    // endregion

    /**
     * {@code UnitComp.update}'s drop zones: a unit of another team than the wave team, while the world has spawns, is
     * pushed away from each spawn tile it is within {@code dropZoneRadius + hitSize / 2 + 1} of, harder the closer it
     * is.
     */
    @Override
    public void push(Unit unit){
        World world = unit.world();
        if(!hasSpawns(world)) return;
        Rules rules = UnitCalls.rules(world);
        float relativeSize = rules.dropZoneRadius + unit.hitSize / 2f + 1f;
        TileGrid tiles = world.tiles();
        IntSeq list = spawns(world);
        for(int i = 0; i < list.size; i++){
            int pos = list.items[i];
            float sx = tiles.x(pos) * TILESIZE, sy = tiles.y(pos) * TILESIZE;
            if(unit.within(sx, sy, relativeSize)){
                var push = new Vec2(unit.x, unit.y).sub(sx, sy).setLength(0.1f + 1f - unit.dst(sx, sy) / relativeSize).scl(0.45f);
                unit.velAddNet(push.x, push.y);
            }
        }
    }

    /** {@code Time.run(121f, () -> spawning = false)}. */
    public record SpawningDone() implements Timers.Task{
        @Override
        public void run(World world){
            WaveSpawner spawner = of(world);
            if(spawner != null) spawner.spawning = false;
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new WaveSpawner();
        out.spawns = spawns.copy();
        out.scanned = scanned;
        out.spawning = spawning;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.ints(spawns.items, 0, spawns.size).bool(scanned).bool(spawning);
    }

    @Override
    public void write(CheckpointOut out){
        out.intSeq("spawns", spawns).bool("scanned", scanned).bool("spawning", spawning);
    }

    @Override
    public void read(CheckpointIn in){
        IntSeq stored = in.intSeq("spawns");
        if(stored == null) throw new CheckpointException("the spawn points are missing");
        spawns = new IntSeq(false, Math.max(16, stored.size));
        spawns.addAll(stored);
        scanned = in.bool("scanned");
        spawning = in.bool("spawning");
    }
}
