package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code MapObjectives.MapObjective} (MapObjectives.java:180) and its thirteen kinds, each with vanilla's
 * fields, defaults and completion check ({@code update()}). Content is held by id, teams by id, and an objective's
 * parents by their index in {@link MapObjectives#all}, which is what the JSON carries.
 *
 * <p>What only clients use ({@code text()}, {@code details()}, the editor's position) is kept as data so the JSON
 * round-trips; the checks a server runs read the world.
 */
public abstract sealed class MapObjective implements Checkpointable{
    /** Vanilla's v160.7 ids of the default blocks: {@code Blocks.conveyor} and {@code Blocks.router}. */
    public static final int CONVEYOR = 257, ROUTER = 266;

    public boolean hidden;
    public String details;
    public String completionLogicCode;
    public List<String> flagsAdded = new ArrayList<>();
    public List<String> flagsRemoved = new ArrayList<>();
    public List<ObjectiveMarker> markers = new ArrayList<>();
    /** The objectives that must be completed before this one runs, by index. */
    public List<Integer> parents = new ArrayList<>(2);
    /** The editor's position; {@code -999} is unplaced. */
    public int editorX = -999, editorY = -999;
    /** Whether this objective is done ({@link #done}). */
    public boolean completed;

    /** The JSON class tag: vanilla's simple class name without "Objective". */
    public abstract String tag();

    /** A new objective of the same kind with its defaults. */
    public abstract MapObjective blank();

    /** {@code update()}: true once the objective is met. */
    public abstract boolean update(World world, Rules rules);

    /** {@code reset()}: internal state back to its start. */
    public void reset(){
    }

    /** {@code validate()}: null content back to the defaults, after reading. */
    public void validate(){
    }

    /** {@code qualified()}: not done, and every parent done ({@code dependencyFinished()}). */
    public boolean qualified(List<MapObjective> all){
        if(completed) return false;
        for(int parent : parents){
            if(!all.get(parent).completed) return false;
        }
        return true;
    }

    public final MapObjective copy(){
        MapObjective out = blank();
        out.hidden = hidden;
        out.details = details;
        out.completionLogicCode = completionLogicCode;
        out.flagsAdded = new ArrayList<>(flagsAdded);
        out.flagsRemoved = new ArrayList<>(flagsRemoved);
        out.markers = new ArrayList<>(markers.size());
        for(ObjectiveMarker marker : markers) out.markers.add(marker == null ? null : marker.copy());
        out.parents = new ArrayList<>(parents);
        out.editorX = editorX;
        out.editorY = editorY;
        out.completed = completed;
        copyFields(out);
        return out;
    }

    void copyFields(MapObjective into){
    }

    public void hash(Hasher hasher){
        hasher.str(tag()).bool(hidden);
        ObjectiveMarker.hash(hasher, details);
        ObjectiveMarker.hash(hasher, completionLogicCode);
        strings(hasher, flagsAdded);
        strings(hasher, flagsRemoved);
        hasher.i(markers.size());
        for(ObjectiveMarker marker : markers){
            hasher.bool(marker != null);
            if(marker != null) marker.hash(hasher);
        }
        hasher.i(parents.size());
        for(int parent : parents) hasher.i(parent);
        hasher.i(editorX).i(editorY).bool(completed);
        hashFields(hasher);
    }

    void hashFields(Hasher hasher){
    }

    private static void strings(Hasher hasher, List<String> values){
        hasher.i(values.size());
        for(String value : values) ObjectiveMarker.hash(hasher, value);
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("hidden", hidden).str("details", details).str("completionLogicCode", completionLogicCode).strs("flagsAdded", flagsAdded)
            .strs("flagsRemoved", flagsRemoved).list("markers", markers).ints("parents", parents.stream().mapToInt(Integer::intValue).toArray())
            .i("editorX", editorX).i("editorY", editorY).bool("completed", completed);
    }

    @Override
    public void read(CheckpointIn in){
        hidden = in.bool("hidden");
        details = in.str("details");
        completionLogicCode = in.str("completionLogicCode");
        flagsAdded = new ArrayList<>(in.strs("flagsAdded"));
        flagsRemoved = new ArrayList<>(in.strs("flagsRemoved"));
        markers = new ArrayList<>(in.list("markers", ObjectiveMarker.class));
        parents = new ArrayList<>();
        for(int parent : in.ints("parents")) parents.add(parent);
        editorX = in.i("editorX");
        editorY = in.i("editorY");
        completed = in.bool("completed");
    }

    /** An objective by its JSON class tag (vanilla registers the simple name and its camelized form), or null. */
    public static MapObjective byTag(String tag){
        return switch(tag){
            case "Research", "research" -> new Research();
            case "Produce", "produce" -> new Produce();
            case "Item", "item" -> new Item();
            case "CoreItem", "coreItem" -> new CoreItem();
            case "BuildCount", "buildCount" -> new BuildCount();
            case "UnitCount", "unitCount" -> new UnitCount();
            case "DestroyUnits", "destroyUnits" -> new DestroyUnits();
            case "Timer", "timer" -> new Timer();
            case "DestroyBlock", "destroyBlock" -> new DestroyBlock();
            case "DestroyBlocks", "destroyBlocks" -> new DestroyBlocks();
            case "DestroyCore", "destroyCore" -> new DestroyCore();
            case "CommandMode", "commandMode" -> new CommandMode();
            case "Flag", "flag" -> new Flag();
            case null, default -> null;
        };
    }

    // region world reads

    /** {@code team.items().get(item)}: the team's first core's items, or none. */
    static int coreItems(World world, int team, int item){
        Teams teams = Teams.of(world);
        var core = teams == null ? null : teams.core(team);
        return core == null || core.items == null ? 0 : core.items.get(item);
    }

    /** {@code content.unlocked()} on a server: {@code unlocked || alwaysUnlocked}, and a server unlocks nothing itself. */
    static boolean unlocked(World world, ContentRef content){
        ContentDef def = switch(content.type()){
            case ITEM, BLOCK, LIQUID, STATUS, UNIT, WEATHER -> content.id() >= 0 && content.id() < world.content().count(content.type())
                ? world.content().get(content.type(), content.id()) : null;
            default -> null;
        };
        return def != null && def.baked() && def.fields().has("alwaysUnlocked") && def.fields().b("alwaysUnlocked");
    }

    /** {@code build == null || build.team != team || build.block != block} at (x, y): the block is gone. */
    static boolean gone(World world, int x, int y, int team, int block){
        var tiles = world.tiles();
        Building build = tiles.inBounds(x, y) ? world.buildings().at(x, y) : null;
        return build == null || build.team != team || build.block().id() != block;
    }

    // endregion

    /** {@code ResearchObjective}: content is unlocked in the tech tree. */
    public static final class Research extends MapObjective{
        public ContentRef content = new ContentRef(ContentType.ITEM, VanillaIds.COPPER);

        @Override
        public String tag(){
            return "Research";
        }

        @Override
        public MapObjective blank(){
            return new Research();
        }

        @Override
        public boolean update(World world, Rules rules){
            return unlocked(world, content);
        }

        @Override
        public void validate(){
            if(content == null) content = new ContentRef(ContentType.ITEM, VanillaIds.COPPER);
        }

        @Override
        void copyFields(MapObjective into){
            ((Research)into).content = content;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(content.type().ordinal()).i(content.id());
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.obj("content", content);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            content = in.obj("content", ContentRef.class);
        }
    }

    /** {@code ProduceObjective}: research with other text. */
    public static final class Produce extends MapObjective{
        public ContentRef content = new ContentRef(ContentType.ITEM, VanillaIds.COPPER);

        @Override
        public String tag(){
            return "Produce";
        }

        @Override
        public MapObjective blank(){
            return new Produce();
        }

        @Override
        public boolean update(World world, Rules rules){
            return unlocked(world, content);
        }

        @Override
        public void validate(){
            if(content == null) content = new ContentRef(ContentType.ITEM, VanillaIds.COPPER);
        }

        @Override
        void copyFields(MapObjective into){
            ((Produce)into).content = content;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(content.type().ordinal()).i(content.id());
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.obj("content", content);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            content = in.obj("content", ContentRef.class);
        }
    }

    /** {@code ItemObjective}: the default team's core holds an amount of an item. */
    public static final class Item extends MapObjective{
        public int item = VanillaIds.COPPER;
        public int amount = 1;

        @Override
        public String tag(){
            return "Item";
        }

        @Override
        public MapObjective blank(){
            return new Item();
        }

        @Override
        public boolean update(World world, Rules rules){
            return coreItems(world, rules.defaultTeam, item) >= amount;
        }

        @Override
        void copyFields(MapObjective into){
            var out = (Item)into;
            out.item = item;
            out.amount = amount;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(item).i(amount);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("item", item).i("amount", amount);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            item = in.i("item");
            amount = in.i("amount");
        }
    }

    /** {@code CoreItemObjective}: an amount of an item entered the default team's cores through blocks. */
    public static final class CoreItem extends MapObjective{
        public int item = VanillaIds.COPPER;
        public int amount = 2;

        @Override
        public String tag(){
            return "CoreItem";
        }

        @Override
        public MapObjective blank(){
            return new CoreItem();
        }

        @Override
        public boolean update(World world, Rules rules){
            return ObjectiveState.stats(world).coreItemCount.getOrDefault(item, 0) >= amount;
        }

        @Override
        void copyFields(MapObjective into){
            var out = (CoreItem)into;
            out.item = item;
            out.amount = amount;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(item).i(amount);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("item", item).i("amount", amount);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            item = in.i("item");
            amount = in.i("amount");
        }
    }

    /** {@code BuildCountObjective}: the default team finished building a block a number of times. */
    public static final class BuildCount extends MapObjective{
        public int block = CONVEYOR;
        public int count = 1;

        @Override
        public String tag(){
            return "BuildCount";
        }

        @Override
        public MapObjective blank(){
            return new BuildCount();
        }

        @Override
        public boolean update(World world, Rules rules){
            return ObjectiveState.stats(world).placedBlockCount.getOrDefault(block, 0) >= count;
        }

        @Override
        void copyFields(MapObjective into){
            var out = (BuildCount)into;
            out.block = block;
            out.count = count;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(block).i(count);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("block", block).i("count", count);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            block = in.i("block");
            count = in.i("count");
        }
    }

    /** {@code UnitCountObjective}: the default team has a number of units of a type. */
    public static final class UnitCount extends MapObjective{
        public int unit = VanillaIds.DAGGER;
        public int count = 1;

        @Override
        public String tag(){
            return "UnitCount";
        }

        @Override
        public MapObjective blank(){
            return new UnitCount();
        }

        @Override
        public boolean update(World world, Rules rules){
            TeamUnits units = TeamUnits.of(world);
            return units != null && units.count(rules.defaultTeam, world.content().unit(unit)) >= count;
        }

        @Override
        void copyFields(MapObjective into){
            var out = (UnitCount)into;
            out.unit = unit;
            out.count = count;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(unit).i(count);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("unit", unit).i("count", count);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            unit = in.i("unit");
            count = in.i("count");
        }
    }

    /** {@code DestroyUnitsObjective}: a number of enemy units destroyed. */
    public static final class DestroyUnits extends MapObjective{
        public int count = 1;

        @Override
        public String tag(){
            return "DestroyUnits";
        }

        @Override
        public MapObjective blank(){
            return new DestroyUnits();
        }

        @Override
        public boolean update(World world, Rules rules){
            return ObjectiveState.stats(world).enemyUnitsDestroyed >= count;
        }

        @Override
        void copyFields(MapObjective into){
            ((DestroyUnits)into).count = count;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(count);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("count", count);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            count = in.i("count");
        }
    }

    /** {@code TimerObjective}: a time passes, scaled by {@code objectiveTimerMultiplier}. */
    public static final class Timer extends MapObjective{
        public String text;
        /** In ticks. */
        public float duration = 60f * 30f;
        public float countup;

        @Override
        public String tag(){
            return "Timer";
        }

        @Override
        public MapObjective blank(){
            return new Timer();
        }

        @Override
        public boolean update(World world, Rules rules){
            // Time.delta is 1
            return (countup += 1f) >= duration * rules.objectiveTimerMultiplier;
        }

        @Override
        public void reset(){
            countup = 0f;
        }

        @Override
        void copyFields(MapObjective into){
            var out = (Timer)into;
            out.text = text;
            out.duration = duration;
            out.countup = countup;
        }

        @Override
        void hashFields(Hasher hasher){
            ObjectiveMarker.hash(hasher, text);
            hasher.f(duration).f(countup);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.str("text", text).f("duration", duration).f("countup", countup);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            text = in.str("text");
            duration = in.f("duration");
            countup = in.f("countup");
        }
    }

    /** {@code DestroyBlockObjective}: the block at a tile is no longer the team's block. */
    public static final class DestroyBlock extends MapObjective{
        public Point2 pos = new Point2(0, 0);
        public int team = VanillaIds.CRUX;
        public int block = ROUTER;

        @Override
        public String tag(){
            return "DestroyBlock";
        }

        @Override
        public MapObjective blank(){
            return new DestroyBlock();
        }

        @Override
        public boolean update(World world, Rules rules){
            return gone(world, pos.x(), pos.y(), team, block);
        }

        @Override
        void copyFields(MapObjective into){
            var out = (DestroyBlock)into;
            out.pos = pos;
            out.team = team;
            out.block = block;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(pos.x()).i(pos.y()).i(team).i(block);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.i("pos", pos.pack()).i("team", team).i("block", block);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            int packed = in.i("pos");
            pos = Point2.unpack(packed);
            team = in.i("team");
            block = in.i("block");
        }
    }

    /** {@code DestroyBlocksObjective}: the blocks at a set of tiles are no longer the team's block. */
    public static final class DestroyBlocks extends MapObjective{
        public List<Point2> positions = new ArrayList<>();
        public int team = VanillaIds.CRUX;
        public int block = ROUTER;

        @Override
        public String tag(){
            return "DestroyBlocks";
        }

        @Override
        public MapObjective blank(){
            return new DestroyBlocks();
        }

        /** {@code progress()}: how many are gone. */
        public int progress(World world){
            int count = 0;
            for(Point2 pos : positions){
                if(gone(world, pos.x(), pos.y(), team, block)) count++;
            }
            return count;
        }

        @Override
        public boolean update(World world, Rules rules){
            return progress(world) >= positions.size();
        }

        @Override
        void copyFields(MapObjective into){
            var out = (DestroyBlocks)into;
            out.positions = new ArrayList<>(positions);
            out.team = team;
            out.block = block;
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.i(positions.size());
            for(Point2 pos : positions) hasher.i(pos.x()).i(pos.y());
            hasher.i(team).i(block);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            int[] packed = new int[positions.size()];
            for(int i = 0; i < packed.length; i++) packed[i] = positions.get(i).pack();
            out.ints("positions", packed).i("team", team).i("block", block);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            positions = new ArrayList<>();
            for(int packed : in.ints("positions")) positions.add(Point2.unpack(packed));
            team = in.i("team");
            block = in.i("block");
        }
    }

    /** {@code CommandModeObjective}: any unit commanded; always met on a headless server (MapObjectives.java:674). */
    public static final class CommandMode extends MapObjective{
        @Override
        public String tag(){
            return "CommandMode";
        }

        @Override
        public MapObjective blank(){
            return new CommandMode();
        }

        @Override
        public boolean update(World world, Rules rules){
            return true;
        }
    }

    /** {@code FlagObjective}: a world processor set an objective flag. */
    public static final class Flag extends MapObjective{
        public String flag = "flag";
        public String text;

        @Override
        public String tag(){
            return "Flag";
        }

        @Override
        public MapObjective blank(){
            return new Flag();
        }

        @Override
        public boolean update(World world, Rules rules){
            return rules.objectiveFlags.contains(flag);
        }

        @Override
        void copyFields(MapObjective into){
            var out = (Flag)into;
            out.flag = flag;
            out.text = text;
        }

        @Override
        void hashFields(Hasher hasher){
            ObjectiveMarker.hash(hasher, flag);
            ObjectiveMarker.hash(hasher, text);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.str("flag", flag).str("text", text);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            flag = in.str("flag");
            text = in.str("text");
        }
    }

    /** {@code DestroyCoreObjective}: the wave team has no core left. */
    public static final class DestroyCore extends MapObjective{
        @Override
        public String tag(){
            return "DestroyCore";
        }

        @Override
        public MapObjective blank(){
            return new DestroyCore();
        }

        @Override
        public boolean update(World world, Rules rules){
            Teams teams = Teams.of(world);
            return teams == null || teams.cores(rules.waveTeam).isEmpty();
        }
    }
}
