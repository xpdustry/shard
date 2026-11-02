package com.xpdustry.shard.wire;

import arc.audio.*;
import arc.graphics.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.struct.Queue;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.net.*;
import mindustry.net.Packet;
import mindustry.type.*;
import mindustry.ui.builder.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.payloads.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Random vanilla values for the round-trip tests, in a 64x64 world with a router at (10, 10), a duo at (20, 20) and a
 * dagger. Strings include U+0000, two-byte, three-byte and astral characters, so every modified UTF-8 path is used.
 */
final class Randoms{
    final Random rnd;
    Player player;
    Building building;
    Unit unit, blockUnit;

    Randoms(long seed){
        rnd = new Random(seed);
    }

    /** An empty 64x64 world with the entities the values refer to. */
    static Randoms world(long seed){
        //emptyWorld(w, h) reads Blocks.stone before it boots vanilla
        com.xpdustry.shard.oracle.Vanilla.boot();
        com.xpdustry.shard.oracle.Vanilla.emptyWorld(64, 64);
        Randoms r = new Randoms(seed);
        r.player = Player.create();
        r.player.name = "oracle";
        r.player.add();
        world.tile(10, 10).setBlock(Blocks.router, Team.sharded, 0);
        r.building = world.tile(10, 10).build;
        world.tile(20, 20).setBlock(Blocks.duo, Team.sharded, 0);
        r.blockUnit = ((ControlBlock)world.tile(20, 20).build).unit();
        r.unit = UnitTypes.dagger.spawn(Team.sharded, 80f, 80f);
        return r;
    }

    float f(){
        return rnd.nextInt(8) == 0 ? 0f : (rnd.nextFloat() - 0.3f) * 4000f;
    }

    String string(){
        String[] parts = {"frog", "[accent]", "\u0000", "é", "\uD83D\uDC38", "日本", " "};
        StringBuilder out = new StringBuilder();
        int n = rnd.nextInt(6);
        for(int i = 0; i < n; i++) out.append(parts[rnd.nextInt(parts.length)]);
        return out.toString();
    }

    String nullableString(){
        return rnd.nextInt(6) == 0 ? null : string();
    }

    <T> T pick(Seq<T> seq){
        return seq.get(rnd.nextInt(seq.size));
    }

    Tile tile(){
        return world.tile(rnd.nextInt(64), rnd.nextInt(64));
    }

    /** One value of each {@code writeObject} type a client may send, readable by {@code readObjectSafe}. */
    Object object(){
        return switch(rnd.nextInt(22)){
            case 0 -> null;
            case 1 -> rnd.nextInt();
            case 2 -> rnd.nextLong();
            case 3 -> rnd.nextFloat();
            case 4 -> string();
            case 5 -> pick(content.items());
            case 6 -> IntSeq.with(rnd.nextInt(), rnd.nextInt(), rnd.nextInt());
            case 7 -> new Point2(rnd.nextInt(100), rnd.nextInt(100));
            case 8 -> new Point2[]{new Point2(1, 2), new Point2(-3, 4)};
            case 9 -> rnd.nextBoolean();
            case 10 -> rnd.nextDouble();
            case 11 -> new byte[]{1, 2, 3, (byte)rnd.nextInt()};
            case 12 -> Team.all[rnd.nextInt(256)];
            case 13 -> new Vec2(f(), f());
            case 14 -> new Vec2[]{new Vec2(1, 2), new Vec2(f(), 0)};
            case 15 -> new boolean[]{true, false, rnd.nextBoolean()};
            case 16 -> LAccess.all[rnd.nextInt(LAccess.all.length)];
            case 17 -> building;
            case 18 -> unit;
            case 19 -> new int[]{rnd.nextInt(), rnd.nextInt()};
            case 20 -> new Object[]{rnd.nextInt(), "x", null, new int[]{1}};
            default -> pick(content.unitCommands());
        };
    }

    Queue<BuildPlan> plans(){
        Queue<BuildPlan> plans = new Queue<>();
        int n = rnd.nextInt(6);
        for(int i = 0; i < n; i++){
            int x = rnd.nextInt(64), y = rnd.nextInt(64);
            plans.add(rnd.nextInt(4) == 0 ? new BuildPlan(x, y) : new BuildPlan(x, y, rnd.nextInt(4), content.block(1 + rnd.nextInt(content.blocks().size - 1)), object()));
        }
        return plans;
    }

    TypeIO.ClientBuildPlans clientPlans(){
        var plans = new TypeIO.ClientBuildPlans();
        int n = rnd.nextInt(8);
        for(int i = 0; i < n; i++){
            Object config = switch(rnd.nextInt(5)){
                case 0 -> null;
                case 1 -> rnd.nextInt();
                case 2 -> rnd.nextBoolean();
                case 3 -> pick(content.items());
                default -> "not sent";
            };
            plans.add(new BuildPlan(rnd.nextInt(64), rnd.nextInt(64), rnd.nextInt(4), content.block(1 + rnd.nextInt(content.blocks().size - 1)), config));
        }
        return plans;
    }

    /** A UiBuilder tree, written by Shard and read by vanilla. */
    UiBuilder.NodeBuilder<?> uiBuilder(){
        var node = new Codecs.UiNode(rnd.nextInt(Codecs.UI_NODE_TYPES), List.of(
            new Codecs.UiEntry(Codecs.UI_NODE_TYPES + 1, string()),
            new Codecs.UiEntry(40, rnd.nextFloat()),
            new Codecs.UiEntry(41, rnd.nextBoolean()),
            new Codecs.UiEntry(0, new Codecs.UiNode(rnd.nextInt(Codecs.UI_NODE_TYPES), List.of()))
        ));
        var write = new com.xpdustry.shard.common.io.Writes();
        Codecs.writeUiBuilder(write, node);
        return UiBuilder.NodeBuilder.read(new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(write.toByteArray()))));
    }

    /**
     * A random value for a field of this type. Covers every parameter type of v160.7's {@code Call} packets.
     */
    Object value(Class<?> t){
        if(t == int.class) return rnd.nextInt();
        if(t == float.class) return f();
        if(t == boolean.class) return rnd.nextBoolean();
        if(t == long.class) return rnd.nextLong();
        if(t == double.class) return rnd.nextDouble();
        if(t == short.class) return (short)rnd.nextInt();
        if(t == byte.class) return (byte)rnd.nextInt();
        if(t == String.class) return nullableString();
        if(t == byte[].class){
            byte[] b = new byte[rnd.nextInt(64)];
            rnd.nextBytes(b);
            return b;
        }
        if(t == int[].class){
            int[] b = new int[rnd.nextInt(8)];
            for(int i = 0; i < b.length; i++) b[i] = rnd.nextInt();
            return b;
        }
        if(t == short[].class){
            short[] b = new short[rnd.nextInt(8)];
            for(int i = 0; i < b.length; i++) b[i] = (short)rnd.nextInt();
            return b;
        }
        if(t == String[][].class){
            String[][] rows = new String[rnd.nextInt(4)][];
            for(int i = 0; i < rows.length; i++){
                rows[i] = new String[rnd.nextInt(4)];
                for(int j = 0; j < rows[i].length; j++) rows[i][j] = nullableString();
            }
            return rows;
        }
        if(t == ItemStack[].class){
            ItemStack[] stacks = new ItemStack[rnd.nextInt(5)];
            for(int i = 0; i < stacks.length; i++) stacks[i] = new ItemStack(pick(content.items()), rnd.nextInt());
            return stacks;
        }
        if(t == LiquidStack[].class){
            LiquidStack[] stacks = new LiquidStack[rnd.nextInt(5)];
            for(int i = 0; i < stacks.length; i++) stacks[i] = new LiquidStack(pick(content.liquids()), f());
            return stacks;
        }
        if(t == IntSeq.class) return IntSeq.with(rnd.nextInt(), rnd.nextInt());
        if(t == Vec2.class) return new Vec2(f(), f());
        if(t == Color.class) return new Color(rnd.nextInt());
        if(t == Tile.class) return rnd.nextInt(5) == 0 ? null : tile();
        if(t == Team.class) return Team.all[rnd.nextInt(256)];
        if(t == Block.class) return rnd.nextInt(8) == 0 ? null : pick(content.blocks());
        if(t == Item.class) return rnd.nextInt(8) == 0 ? null : pick(content.items());
        if(t == Liquid.class) return rnd.nextInt(8) == 0 ? null : pick(content.liquids());
        if(t == UnitType.class) return pick(content.units());
        if(t == Weather.class) return rnd.nextInt(8) == 0 ? null : pick(content.<Weather>getBy(ContentType.weather));
        if(t == BulletType.class) return pick(content.bullets());
        if(t == Content.class) return pick(content.items());
        if(t == Effect.class) return pick(Effect.all);
        if(t == Sound.class){
            Sound sound = Sounds.getSound(rnd.nextInt(40));
            return sound == null ? Sounds.none : sound;
        }
        if(t == UnitCommand.class) return rnd.nextInt(6) == 0 ? null : pick(content.unitCommands());
        if(t == UnitStance.class) return pick(content.unitStances());
        if(t == Player.class) return player;
        if(t == Building.class) return rnd.nextInt(5) == 0 ? null : building;
        if(t == Unit.class) return switch(rnd.nextInt(3)){
            case 0 -> null;
            case 1 -> unit;
            default -> blockUnit;
        };
        if(t == Object.class) return object();
        if(t == Queue.class) return rnd.nextInt(5) == 0 ? null : plans();
        if(t == TypeIO.ClientBuildPlans.class) return rnd.nextInt(5) == 0 ? null : clientPlans();
        if(t == MenuResult.class){
            var result = new MenuResult(nullableString());
            result.token = rnd.nextLong();
            int n = rnd.nextInt(5);
            for(int i = 0; i < n; i++){
                result.values.put("key" + i + string(), switch(rnd.nextInt(3)){
                    case 0 -> rnd.nextFloat();
                    case 1 -> rnd.nextBoolean();
                    default -> string();
                });
            }
            return result;
        }
        if(t == UiBuilder.NodeBuilder.class) return uiBuilder();
        if(t == Administration.TraceInfo.class){
            String[] ips = new String[rnd.nextInt(15)], names = new String[rnd.nextInt(15)];
            for(int i = 0; i < ips.length; i++) ips[i] = string();
            for(int i = 0; i < names.length; i++) names[i] = nullableString();
            return new Administration.TraceInfo(nullableString(), nullableString(), nullableString(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextInt(), rnd.nextInt(), ips, names);
        }
        if(t == Rules.class){
            Rules rules = new Rules();
            rules.waveSpacing = rnd.nextFloat() * 1000;
            rules.modeName = string();
            return rules;
        }
        if(t == MapObjectives.class){
            var objectives = new MapObjectives();
            if(rnd.nextBoolean()) objectives.add(new MapObjectives.TimerObjective(string(), rnd.nextInt(600)));
            return objectives;
        }
        if(t == MapObjectives.ObjectiveMarker.class) return new MapObjectives.TextMarker(string(), f(), f());
        if(t == Units.UnitSyncContainer.class) return new Units.UnitSyncContainer(unit);
        if(t.isEnum()) return t.getEnumConstants()[rnd.nextInt(t.getEnumConstants().length)];
        //interfaces a packet takes an entity as (Itemsc, Posc, Entityc...)
        if(t.isInstance(unit)) return unit;
        if(t.isInstance(player)) return player;
        throw new IllegalArgumentException("no random value for " + t.getName());
    }

    //region vanilla entities

    /** A unit of {@code type} with random fields, statuses, plans, payloads and controller. */
    Unit unit(UnitType type){
        Unit u = type.create(Team.all[rnd.nextInt(256)]);
        u.set(f(), f());
        u.rotation = f();
        u.health = f();
        u.shield = f();
        u.elevation = rnd.nextFloat();
        u.flag = rnd.nextDouble() * 1e9;
        u.vel.set(f(), f());
        u.aimX = f();
        u.aimY = f();
        u.isShooting = rnd.nextBoolean();
        u.spawnedByCore = rnd.nextBoolean();
        u.updateBuilding = rnd.nextBoolean();
        for(var m : u.mounts){
            m.aimX = f();
            m.aimY = f();
            m.shoot = rnd.nextBoolean();
            m.rotate = rnd.nextBoolean();
        }
        for(var a : u.abilities) a.data = f();
        int statuses = rnd.nextInt(4);
        for(int i = 0; i < statuses; i++){
            StatusEffect effect = content.statusEffects().get(1 + rnd.nextInt(content.statusEffects().size - 1));
            if(effect.dynamic){
                var entry = u.applyDynamicStatus();
                entry.time = 1 + rnd.nextInt(600);
                if(rnd.nextBoolean()) entry.damageMultiplier = rnd.nextFloat() * 3;
                if(rnd.nextBoolean()) entry.speedMultiplier = rnd.nextFloat() * 3;
                if(rnd.nextBoolean()) entry.armorOverride = rnd.nextFloat() * 20;
            }else{
                u.apply(effect, 1 + rnd.nextInt(600));
            }
        }
        u.stack.set(pick(content.items()), rnd.nextInt(1000));
        u.mineTile = rnd.nextBoolean() ? null : tile();
        int plans = rnd.nextInt(5);
        for(int i = 0; i < plans; i++){
            int x = rnd.nextInt(64), y = rnd.nextInt(64);
            u.plans.addLast(rnd.nextInt(4) == 0 ? new BuildPlan(x, y)
                : new BuildPlan(x, y, rnd.nextInt(4), content.block(1 + rnd.nextInt(content.blocks().size - 1)), object()));
        }
        if(u instanceof Payloadc carrier && rnd.nextBoolean()){
            carrier.addPayload(new UnitPayload(UnitTypes.dagger.create(Team.sharded)));
            carrier.addPayload(new BuildPayload(Blocks.router, Team.sharded));
        }
        switch(rnd.nextInt(4)){
            case 0 -> u.controller(player);
            case 1 -> {
                var ai = new CommandAI();
                if(rnd.nextBoolean()) ai.targetPos = new Vec2(f(), f());
                if(rnd.nextBoolean()) ai.attackTarget = building;
                ai.command = rnd.nextBoolean() ? null : pick(content.unitCommands());
                if(rnd.nextBoolean()) ai.commandQueue.add(new Vec2(f(), f()));
                if(rnd.nextBoolean()) ai.commandQueue.add(building);
                ai.setStance(pick(content.unitStances()));
                u.controller(ai);
            }
            case 2 -> u.controller(new AssemblerAI());
            default -> u.controller(type.createController(u));
        }
        return u;
    }

    /** A player with random fields. */
    Player player(){
        Player p = Player.create();
        p.name = nullableString();
        p.color.set(rnd.nextInt());
        p.admin = rnd.nextBoolean();
        p.typing = rnd.nextBoolean();
        p.shooting = rnd.nextBoolean();
        p.boosting = rnd.nextBoolean();
        p.mouseX = f();
        p.mouseY = f();
        p.selectedBlock = pick(content.blocks());
        p.selectedRotation = rnd.nextInt(4);
        p.team(Team.all[rnd.nextInt(256)]);
        p.set(f(), f());
        return p;
    }

    Fire fire(){
        Fire fire = Fire.create();
        fire.tile = tile();
        fire.lifetime = f();
        fire.time = f();
        fire.set(f(), f());
        return fire;
    }

    Puddle puddle(){
        Puddle puddle = Puddle.create();
        puddle.tile = tile();
        puddle.liquid = pick(content.liquids());
        puddle.amount = f();
        puddle.set(f(), f());
        return puddle;
    }

    WeatherState weather(){
        WeatherState weather = WeatherState.create();
        weather.weather = pick(content.<Weather>getBy(ContentType.weather));
        weather.intensity = rnd.nextFloat();
        weather.life = f();
        weather.opacity = rnd.nextFloat();
        weather.effectTimer = f();
        weather.windVector.set(f(), f());
        weather.set(f(), f());
        return weather;
    }

    WorldLabel label(){
        WorldLabel label = WorldLabel.create();
        label.text = nullableString();
        label.fontSize = f();
        label.flags = (byte)rnd.nextInt();
        label.z = f();
        label.set(f(), f());
        return label;
    }

    LaunchPayload launchPayload(){
        LaunchPayload launch = LaunchPayload.create();
        launch.team(Team.all[rnd.nextInt(256)]);
        launch.lifetime = f();
        launch.time = f();
        launch.set(f(), f());
        int stacks = rnd.nextInt(4);
        for(int i = 0; i < stacks; i++) launch.stacks.add(new ItemStack(pick(content.items()), rnd.nextInt(1000)));
        return launch;
    }

    /**
     * A random vanilla entity of class {@code classId}: a unit of a type that creates that class (else of the class,
     * with the default type), or one of the other saved classes.
     */
    Entityc entity(int classId){
        Entityc plain = (Entityc)EntityMapping.map(classId).get();
        if(plain instanceof Unit){
            UnitType type = content.units().find(t -> t.constructor.get().classId() == plain.classId());
            return type != null ? unit(type) : plain;
        }
        return switch(plain){
            case Player _ -> player();
            case Fire _ -> fire();
            case Puddle _ -> puddle();
            case WeatherState _ -> weather();
            case WorldLabel _ -> label();
            case LaunchPayload _ -> launchPayload();
            default -> plain;
        };
    }

    //endregion

    /** Fills the public fields a generated {@code CallPacket} serializes. */
    void fill(Packet packet) throws IllegalAccessException{
        for(Field f : packet.getClass().getFields()){
            if(Modifier.isStatic(f.getModifiers())) continue;
            f.set(packet, value(f.getType()));
        }
    }
}
