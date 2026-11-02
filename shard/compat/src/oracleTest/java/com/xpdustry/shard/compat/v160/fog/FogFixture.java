package com.xpdustry.shard.compat.v160.fog;

import arc.math.*;
import arc.struct.Seq;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.defense.Radar;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fog.FogControl;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Packet;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fog fixtures' harness: a vanilla world and a Shard one on a {@value #WIDTH} × {@value #HEIGHT} fog map, built
 * alike (buildings, items, {@link Patrols} units, a connected player per team), and the comparisons the fog gates use.
 * {@link FogOracleTest} runs vanilla's fog on Shard's schedule ({@link VanillaFog}); {@link FogOutcomeOracleTest}
 * runs it unmodified and compares outcomes.
 */
abstract class FogFixture{
    static final Epoch EPOCH = Epoch.vanilla();
    static final int WIDTH = 96, HEIGHT = 48, FRAMES = 1500, SNAPSHOT_EVERY = 12, STREAM_EVERY = 60, SAVE_AT = 700, TARGETING_FRAMES = 600;
    static final float ALPHA_RANGE = 30 * 8f;

    World shard;
    int vanillaBase, shardBase;
    final List<int[]> turrets = new ArrayList<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Integer> shardIds = new ArrayList<>();
    final EntitySnapshots snapshots = new EntitySnapshots(new EntityProjector(EPOCH, BuildingCodecs.builtIn()));
    final Map<Team, Player> players = new LinkedHashMap<>();
    Pool<mindustry.gen.Bullet> pool;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @AfterEach
    void clearPool(){
        if(pool != null) pool.clear();
    }

    // ---------------------------------------------------------------- the worlds

    void worlds(){
        Vanilla.emptyWorld(WIDTH, HEIGHT, content.block("stone"));
        HazardPools.empty();
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        state.rules.fog = true;
        state.rules.staticFog = true;
        mindustry.gen.Groups.updatePooling();
        mindustry.gen.Bullet.create();
        pool = Pools.get(mindustry.gen.Bullet.class, () -> null);
        pool.clear();
        shard = new World(WorldConfig.of(EPOCH, WIDTH, HEIGHT, 1).withSystems(Systems.builtIn())
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        com.xpdustry.shard.sim.rules.Rules rules = shard.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.waves = false;
        rules.fog = true;
        rules.staticFog = true;
        shardBase = shard.ids().peek();
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shardBase);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        arc.util.Time.setInternalTime(0);
        vanillaBase = lastId();
        Patrols.vanillaBase = vanillaBase;
        Patrols.shardBase = shardBase;
    }

    void place(String block, int x, int y, Team team){
        world.tile(x, y).setBlock(content.block(block), team, 0);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
        if(world.build(x, y) instanceof Turret.TurretBuild) turrets.add(new int[]{x, y});
        sameRandom("after placing " + block);
    }

    void items(int x, int y, String item, int amount){
        for(int i = 0; i < amount; i++){
            world.build(x, y).handleItem(null, content.item(item));
            shard.buildings().at(x, y).handleItem(null, EPOCH.item(item).id());
        }
    }

    void patrol(String type, Team team, int ax, int ay, int bx, int by, int period, float range){
        mindustry.gen.Unit v = content.unit(type).spawn(team, ax * 8f, ay * 8f);
        Unit s = Unit.spawn(shard, EPOCH.unit(type), team.id, ax * 8f, ay * 8f, 0f);
        v.controller(new Patrols.VanillaPatrol(ax * 8f, ay * 8f, bx * 8f, by * 8f, period, range));
        s.controller(new Patrols.ShardPatrol(ax * 8f, ay * 8f, bx * 8f, by * 8f, period, range));
        vanillaUnits.add(v);
        shardIds.add(s.id());
        sameRandom("after spawning " + type);
    }

    /** A connected player of {@code team} on both sides: snapshots go to a team's players, and carry them. */
    void player(Team team){
        Player vanilla = Player.create();
        vanilla.con = Vanilla.connection();
        vanilla.con.hasConnected = true;
        vanilla.name = "oracle-" + team.name;
        vanilla.team(team);
        vanilla.add();
        var player = com.xpdustry.shard.sim.unit.Player.create(shard);
        player.id(vanilla.id);
        player.name = vanilla.name;
        player.color = vanilla.color.rgba();
        player.team = team.id;
        player.add();
        Vanilla.runPosted();
        players.put(team, vanilla);
    }

    void restoreShard(){
        shard = Checkpoint.decode(Checkpoint.capture(shard).encode(Compression.FAST)).restore(shard.config());
    }

    Unit shardUnit(int index){
        return (Unit)EntityGroups.of(shard).byId(shardIds.get(index));
    }

    // ---------------------------------------------------------------- comparing

    void compare(String at){
        sameRandom(at);
        assertEquals(lastId() - vanillaBase, shard.ids().peek() - shardBase, at + ": the entity id counter");
        sameFog(at);
        sameWorld(at);
    }

    /** What {@link #compare} holds besides the fog: turrets, units, their targets and the live bullets. */
    void sameWorld(String at){
        for(int[] t : turrets) assertEquals(vanillaTurret(t[0], t[1]), shardTurret(t[0], t[1]), at + ": turret at " + t[0] + "," + t[1]);
        for(int i = 0; i < vanillaUnits.size(); i++){
            assertEquals(vanillaUnit(vanillaUnits.get(i)), shardUnit(shardUnit(i)), at + ": unit " + i);
        }
        assertEquals(Patrols.VANILLA, Patrols.SHARD, at + ": every unit's target");
        assertEquals(vanillaBullets(), shardBullets(), at + ": live bullets");
    }

    void sameRandom(String at){
        assertEquals(Mathf.rand.seed0, shard.rand().getState(0), at + ": random state");
        assertEquals(Mathf.rand.seed1, shard.rand().getState(1), at + ": random state");
    }

    void sameFog(String at){
        FogControl shardFog = FogControl.of(shard);
        for(Team team : Team.all){
            BitSet visible = VanillaFog.visible(team), discovered = VanillaFog.discovered(team);
            assertEquals(visible != null, shardFog.hasData(team.id), at + ": team " + team.name + " has fog");
            if(visible == null) continue;
            assertEquals(visible, BitSet.valueOf(shardFog.visible(team.id)), at + ": team " + team.name + "'s visible tiles");
            assertEquals(discovered, BitSet.valueOf(shardFog.discovered(team.id)), at + ": team " + team.name + "'s discovered tiles");
        }
    }

    void sameChunk(String at){
        byte[] expected = VanillaFog.chunk();
        byte[] actual = FogControl.of(shard).writeChunk(WIDTH, HEIGHT);
        assertEquals(HexFormat.of().formatHex(expected), HexFormat.of().formatHex(actual), at + ": the static-fog-data chunk");
    }

    /** Each team's {@code writeEntitySnapshotsTeam}, packet for packet; returns crux units hidden from and shown to sharded. */
    int[] sameSnapshots(String at){
        int hidden = 0, shown = 0;
        WorldWireContext wire = WorldWireContext.capture(shard);
        for(var entry : players.entrySet()){
            Team team = entry.getKey();
            var expected = new ArrayList<String>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                try{
                    netServer.writeEntitySnapshotsTeam(team, Seq.with(entry.getValue()));
                }catch(java.io.IOException e){
                    throw new AssertionError(e);
                }
                for(Vanilla.Capture.Sent sent : Vanilla.captured()){
                    expected.add(sent.frame().id() + " " + Hex.of(sent.frame().body()));
                }
            });
            EntitySnapshots.Team snapshot = snapshots.team(shard, team.id, wire);
            var actual = new ArrayList<String>();
            for(Packet packet : snapshot.snapshots()) actual.add(packet.packetId() + " " + Hex.of(body(packet, wire)));
            if(snapshot.hidden() != null) actual.add(snapshot.hidden().packetId() + " " + Hex.of(body(snapshot.hidden(), wire)));
            assertEquals(expected, actual, at + ": " + team.name + "'s entity snapshots");
            if(team == Team.sharded){
                for(mindustry.gen.Unit unit : vanillaUnits){
                    if(unit.team != Team.crux || !unit.isAdded()) continue;
                    if(unit.isSyncHidden(Team.sharded)) hidden++;
                    else shown++;
                }
            }
        }
        return new int[]{hidden, shown};
    }

    /** A world stream to one team's player: vanilla's bytes, and the entity ids of Shard's. */
    record Stream(String at, Team team, byte[] vanilla, List<Integer> shard){
    }

    final List<Stream> streams = new ArrayList<>();

    /**
     * Keeps the world streams to each team's player, vanilla's as bytes and Shard's entity ids; returns the units Shard
     * left out. Decoding loads the stream into vanilla ({@code NetworkIO.loadWorld}), so {@link #sameStreams()} decodes
     * them once the run is over.
     */
    int keepStreams(String at){
        int leftOut = 0;
        var projection = new WorldProjection(EPOCH, BuildingCodecs.builtIn());
        for(var entry : players.entrySet()){
            Player player = entry.getValue();
            var actual = new ArrayList<Integer>();
            var source = projection.stream(shard, new MapState(), PlayerState.fresh(player.id, player.name, entry.getKey().id));
            for(MapState.EntityChunk entity : source.entities()) actual.add(entity.id() - shardBase);
            streams.add(new Stream(at, entry.getKey(), Golden.worldStream(player).raw(), actual));
            leftOut += shard.units().size() - actual.size();
        }
        return leftOut;
    }

    /** The kept streams' entities, by id: the units {@code writeWorld} leaves in for the team. Ends vanilla's world. */
    void sameStreams(){
        for(Stream stream : streams){
            var expected = new ArrayList<Integer>();
            for(Oracle.EntityView entity : Oracle.decodeWorldStream(stream.vanilla()).entities()) expected.add(entity.id() - vanillaBase);
            assertEquals(expected, stream.shard(), stream.at() + ": the units in a world stream to " + stream.team().name);
        }
    }

    static byte[] body(Packet packet, WorldWireContext wire){
        var out = new com.xpdustry.shard.common.io.Writes();
        packet.write(out, true, wire);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- state lines

    String vanillaTurret(int x, int y){
        var b = (Turret.TurretBuild)world.build(x, y);
        var ammo = new StringBuilder();
        for(Turret.AmmoEntry entry : b.ammo){
            ammo.append(entry instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemEntry item ? item.item.id : -1).append('x').append(entry.amount).append(' ');
        }
        String target = switch(b.target){
            case null -> "-";
            case mindustry.gen.Unit u -> "unit " + (u.id - vanillaBase);
            case mindustry.gen.Building t -> "build " + t.tileX() + "," + t.tileY();
            default -> b.target.toString();
        };
        return turret(b.rotation, b.reloadCounter, ammo.toString(), target, b.targetPos.x, b.targetPos.y, b.totalShots, b.isShooting, b.health);
    }

    String shardTurret(int x, int y){
        var b = (TurretBuild)shard.buildings().at(x, y);
        var ammo = new StringBuilder();
        for(int i = 0; i < b.ammoSize; i++) ammo.append(b.ammoIds[i]).append('x').append(b.ammoAmounts[i]).append(' ');
        String target = b.target instanceof Unit u ? "unit " + (u.id() - shardBase)
            : b.target instanceof Building t ? "build " + t.tileX() + "," + t.tileY() : "-";
        return turret(b.turretRotation, b.reloadCounter, ammo.toString(), target, b.targetX, b.targetY, b.totalShots, b.isShooting, b.health);
    }

    private static String turret(float rotation, float reload, String ammo, String target, float tx, float ty, int shots, boolean shooting, float health){
        return "rot " + rotation + " reload " + reload + " ammo [" + ammo + "] target " + target + " aim (" + tx + ", " + ty + ") shots " + shots
            + " shooting " + shooting + " health " + health;
    }

    private static String vanillaUnit(mindustry.gen.Unit u){
        if(!u.isAdded()) return "gone";
        return unit(u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health, u.isShooting);
    }

    private static String shardUnit(Unit u){
        if(u == null || !u.isAdded()) return "gone";
        return unit(u.x, u.y, u.vel.x, u.vel.y, u.rotation, u.health, u.isShooting);
    }

    private static String unit(float x, float y, float vx, float vy, float rotation, float health, boolean shooting){
        return "(" + x + ", " + y + ") v(" + vx + ", " + vy + ") rot " + rotation + " health " + health + " shooting " + shooting;
    }

    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            out.add(bullet(b.id - vanillaBase, b.type.id, b.team.id, b.x, b.y, b.vel.x, b.vel.y, b.time, b.damage));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++) out.add(bullet(t.id[r] - shardBase, t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.damage[r]));
        return out;
    }

    private static String bullet(int id, int type, int team, float x, float y, float vx, float vy, float time, float damage){
        return id + " t" + type + " team" + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") time " + time + " dmg " + damage;
    }

    // ---------------------------------------------------------------- vanilla's counters

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    static void setStatic(Class<?> owner, String name, int value){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
