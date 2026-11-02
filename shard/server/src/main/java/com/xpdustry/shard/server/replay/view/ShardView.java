package com.xpdustry.shard.server.replay.view;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.server.chat.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * A Shard {@link World} as a {@link ViewSource}: what the live recorder samples at {@code SYNC} and what a replay
 * service samples while it re-simulates a bundle. Both must write the same bytes, so it reads only the world and the
 * events handed to it ({@link #accept}), on the world's thread, and computes angles with {@link Angles} (strict math):
 * never a clock, a session, or anything the server keeps beside the world.
 *
 * <p>Content tables are the epoch's, by content id. Events become view events against the units seen at the last
 * sample: a unit that dies has left the world by the time the frame is written.
 */
public final class ShardView implements ViewSource{
    /** Vanilla's six named teams' colours ({@code Team.java}); others are grey. */
    private static final int[] COLORS = {0x4d4e58ff, 0xffd37fff, 0xf25555ff, 0xa27ce5ff, 0x54d67dff, 0x6c87fdff, 0xe05438ff};
    private static final String[] NAMES = {"derelict", "sharded", "crux", "malis", "green", "blue", "neoplastic"};

    private final World world;
    private final String map;
    private final MatchView match;
    private final List<String> blocks, units, liquids;
    private final List<BulletStyle> bullets;
    private final int[] blockSize;
    private final BitSet teamsSeen = new BitSet(256);
    /** The units at the last sample, by id. */
    private final HashMap<Integer, UnitView> seeing = new HashMap<>();
    private final ArrayList<ViewEvent> events = new ArrayList<>();
    private final ArrayList<ChatView> chat = new ArrayList<>();

    /**
     * @param match the match's id and mode; {@code pvp} and {@code fog} are read from the world's rules
     */
    public ShardView(World world, String map, String matchId, String mode){
        this.world = world;
        this.map = map;
        ContentEpoch content = world.content();
        blocks = content.names(ContentType.BLOCK);
        units = content.names(ContentType.UNIT);
        liquids = content.count(ContentType.LIQUID) > 0 ? content.names(ContentType.LIQUID) : List.of();
        bullets = styles(content);
        blockSize = new int[blocks.size()];
        for(int i = 0; i < blockSize.length; i++) blockSize[i] = content.block(i).size();
        Rules rules = world.part(WorldRules.class).rules;
        match = new MatchView(matchId, mode, rules.pvp, rules.fog);
        // derelict: the team of waves and of a game over without a winner
        teamsSeen.set(0);
    }

    /** The world's events of the tick it just ran, in order: those a viewer sees are kept for the next frame. */
    public void accept(List<WorldEvent> tickEvents, long tick){
        for(WorldEvent event : tickEvents){
            switch(event){
                case UnitEvent.Death death -> {
                    UnitView unit = seeing.get(death.unit());
                    if(unit != null) events.add(new ViewEvent(tick, "unit-death", unit.x(), unit.y(), unit.rotation(), unit.team(), unit.type(), hitSize(unit.type())));
                }
                case BuildingEvent.Destroyed destroyed -> {
                    int width = world.tiles().width(), size = destroyed.block() >= 0 && destroyed.block() < blockSize.length ? blockSize[destroyed.block()] : 1;
                    float offset = ((size + 1) % 2) * 4f;
                    events.add(new ViewEvent(tick, "block-destroy", destroyed.pos() % width * 8f + offset, destroyed.pos() / width * 8f + offset, 0f,
                        destroyed.team(), destroyed.block(), size));
                }
                case WaveEvent.Started started -> events.add(new ViewEvent(tick, "wave", 0f, 0f, 0f, 0, -1, started.wave()));
                case GameEvent.GameOver over -> events.add(new ViewEvent(tick, "game-over", 0f, 0f, 0f, over.winner(), -1, over.winner()));
                case ChatEvent.Said said -> chat.add(new ChatView(tick, said.player(), said.message()));
                default -> {
                }
            }
        }
    }

    private float hitSize(int type){
        return world.content().unit(type).hitSize();
    }

    @Override
    public String producer(){
        return "shard " + BuildInfo.id();
    }

    @Override
    public String game(){
        return "v160.7";
    }

    @Override
    public String content(){
        return world.content().hash();
    }

    @Override
    public String map(){
        return map;
    }

    @Override
    public int width(){
        return world.tiles().width();
    }

    @Override
    public int height(){
        return world.tiles().height();
    }

    @Override
    public long tick(){
        return world.ticks();
    }

    @Override
    public List<String> blocks(){
        return blocks;
    }

    @Override
    public List<String> units(){
        return units;
    }

    @Override
    public List<BulletStyle> bullets(){
        return bullets;
    }

    @Override
    public List<String> liquids(){
        return liquids;
    }

    @Override
    public List<TeamView> teams(){
        var out = new ArrayList<TeamView>();
        for(int id = teamsSeen.nextSetBit(0); id >= 0; id = teamsSeen.nextSetBit(id + 1)){
            out.add(new TeamView(id, id < NAMES.length ? NAMES[id] : "team#" + id, id < COLORS.length ? COLORS[id] : 0x808080ff));
        }
        return out;
    }

    @Override
    public MatchView match(){
        return match;
    }

    @Override
    public void terrain(short[] floor, short[] overlay, short[] env){
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            floor[pos] = tiles.floor(pos);
            overlay[pos] = tiles.overlay(pos);
            env[pos] = tiles.build(pos) < 0 ? tiles.block(pos) : 0;
        }
    }

    @Override
    public void buildings(Consumer<BuildView> out){
        world.buildings().each(build -> {
            teamsSeen.set(build.team & 0xFF);
            int flags = 0, target = -1;
            float aim = 0f, progress = 0f;
            switch(build){
                case ConstructBuild site -> {
                    // the block it becomes, or the one it takes down (ConstructBuild.setDeconstruct sets current to it)
                    flags |= site.activeDeconstruct ? BuildView.DECONSTRUCT : BuildView.CONSTRUCT;
                    target = site.current;
                    progress = site.progress;
                }
                case TurretBuild turret -> {
                    flags |= BuildView.AIM;
                    aim = Float.isNaN(turret.turretRotation) ? 0f : turret.turretRotation;
                    if(turret.wasShooting) flags |= BuildView.SHOOTING;
                }
                default -> {
                }
            }
            out.accept(new BuildView(build.tile(), build.block().id(), build.team, build.rotation, build.healthf(), flags, aim, target, progress));
        });
    }

    @Override
    public void units(Consumer<UnitView> out){
        var players = new HashMap<Integer, Integer>();
        Players roster = Players.of(world);
        if(roster != null){
            for(int i = 0; i < roster.list().size(); i++){
                Player player = roster.list().get(i);
                if(player.unit() != null) players.put(player.unit().id(), player.id());
            }
        }
        seeing.clear();
        world.units().each(unit -> {
            teamsSeen.set(unit.team & 0xFF);
            int flags = 0;
            float aim = 0f;
            if(Weapons.isShooting(unit)){
                flags |= UnitView.SHOOTING | UnitView.AIM;
                aim = Angles.angle(unit.x, unit.y, unit.aimX, unit.aimY);
            }
            var view = new UnitView(unit.id(), unit.type().id(), unit.team, unit.x, unit.y, unit.rotation, unit.healthf(), unit.elevation, flags,
                players.getOrDefault(unit.id(), -1), aim);
            seeing.put(unit.id(), view);
            out.accept(view);
        });
    }

    @Override
    public void bullets(Consumer<BulletView> out){
        Bullets table = world.bullets();
        for(int row = 0; row < table.size(); row++){
            out.accept(new BulletView(table.id[row], table.type[row], table.team[row], table.x[row], table.y[row], table.rotation[row], (int)table.time[row]));
        }
    }

    @Override
    public void players(Consumer<PlayerView> out){
        Players roster = Players.of(world);
        if(roster == null) return;
        for(int i = 0; i < roster.list().size(); i++){
            Player player = roster.list().get(i);
            out.accept(new PlayerView(player.id(), player.name, player.color, player.team, player.admin, player.unit() == null ? -1 : player.unit().id()));
        }
    }

    @Override
    public void plans(Consumer<PlanView> out){
        int width = world.tiles().width();
        world.units().each(unit -> {
            PlanQueue queue = unit.plans;
            if(queue.isEmpty()) return;
            var plans = new ArrayList<Plan>(queue.size());
            for(int i = 0; i < queue.size(); i++){
                BuildPlan plan = queue.get(i);
                plans.add(new Plan(plan.y * width + plan.x, plan.breaking ? -1 : plan.block, plan.rotation, plan.progress));
            }
            out.accept(new PlanView(unit.id(), plans));
        });
    }

    @Override
    public void fires(Consumer<FireView> out){
        EntityGroups groups = world.find(EntityGroups.class);
        if(groups == null) return;
        ActiveList<Entity> all = groups.all();
        for(int i = 0; i < all.size(); i++){
            if(all.get(i) instanceof Fire fire && fire.tile >= 0) out.accept(new FireView(fire.tile, fire.lifetime > 0f ? 1f - fire.time / fire.lifetime : 0f));
        }
    }

    @Override
    public void puddles(Consumer<PuddleView> out){
        EntityGroups groups = world.find(EntityGroups.class);
        if(groups == null) return;
        ActiveList<Entity> all = groups.all();
        for(int i = 0; i < all.size(); i++){
            if(all.get(i) instanceof Puddle puddle && puddle.tile >= 0 && puddle.liquid >= 0){
                out.accept(new PuddleView(puddle.tile, puddle.liquid, puddle.amount / Puddle.MAX_LIQUID));
            }
        }
    }

    @Override
    public void rebuild(Consumer<RebuildView> out){
        Teams teams = Teams.of(world);
        if(teams == null) return;
        int width = world.tiles().width(), height = world.tiles().height();
        // by tile, the first team's newest plan: two teams rarely plan one tile, and a tile shows one ghost
        var byTile = new TreeMap<Integer, RebuildView>();
        for(int team = 0; team < Team.COUNT; team++){
            List<BlockPlan> plans = teams.get(team).plans();
            for(int i = 0; i < plans.size(); i++){
                BlockPlan plan = plans.get(i);
                if(plan.removed || plan.x < 0 || plan.y < 0 || plan.x >= width || plan.y >= height) continue;
                byTile.putIfAbsent(plan.y * width + plan.x, new RebuildView(plan.y * width + plan.x, team, plan.block, plan.rotation & 3));
            }
        }
        byTile.values().forEach(out);
    }

    @Override
    public List<ViewEvent> events(){
        var out = List.copyOf(events);
        events.clear();
        return out;
    }

    @Override
    public List<ChatView> chat(){
        var out = List.copyOf(chat);
        chat.clear();
        return out;
    }

    @Override
    public Status status(){
        WaveState waves = world.find(WaveState.class);
        return waves == null ? new Status(0, 0f, 0) : new Status(waves.wave, waves.wavetime, waves.enemies);
    }

    /** Every bullet type of the epoch, as a viewer draws it, from its definition. */
    static List<BulletStyle> styles(ContentEpoch content){
        int count = content.count(ContentType.BULLET);
        var out = new ArrayList<BulletStyle>(count);
        for(int id = 0; id < count; id++){
            BulletDef def = content.bullet(id);
            if(!def.baked()){
                out.add(new BulletStyle(def.name(), "bullet", "", 4f, 4f, -1, -1, 0f, def.lifetime(), 0, 0f, 0, 4f));
                continue;
            }
            Struct fields = def.fields();
            String kind = kind(fields.kind().key());
            float hitSize = number(fields, "hitSize", 4f);
            String sprite = fields.has("sprite") ? Objects.toString(fields.get("sprite"), "") : "";
            float width = number(fields, "width", hitSize), height = number(fields, "height", hitSize);
            int hit = color(fields, "hitColor", -1);
            int front = color(fields, "frontColor", hit), back = color(fields, "backColor", hit);
            if(kind.equals("laser") || kind.startsWith("continuous")) height = number(fields, "length", height);
            out.add(new BulletStyle(def.name(), kind, sprite, width, height, front, back, number(fields, "shrinkY", 0f), number(fields, "lifetime", 0f),
                (int)number(fields, "trailLength", 0f), number(fields, "trailWidth", 0f), color(fields, "trailColor", 0), hitSize));
        }
        return out;
    }

    /** {@code basic-bullet-type} is {@code basic}, {@code continuous-laser-bullet-type} {@code continuousLaser}. */
    static String kind(String key){
        String name = key.endsWith("-bullet-type") ? key.substring(0, key.length() - "-bullet-type".length()) : key.equals("bullet-type") ? "bullet" : key;
        var out = new StringBuilder();
        boolean upper = false;
        for(char c : name.toCharArray()){
            if(c == '-'){
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static float number(Struct fields, String name, float fallback){
        if(!fields.has(name)) return fallback;
        return fields.get(name) instanceof Number number ? number.floatValue() : fallback;
    }

    /** A colour field ({@code #rrggbbaa}) as RGBA8888. */
    private static int color(Struct fields, String name, int fallback){
        if(!fields.has(name)) return fallback;
        Object value = fields.get(name);
        if(value instanceof Number number) return number.intValue();
        if(value instanceof String text && text.matches("#?[0-9a-fA-F]{8}")) return (int)Long.parseLong(text.startsWith("#") ? text.substring(1) : text, 16);
        return fallback;
    }
}
