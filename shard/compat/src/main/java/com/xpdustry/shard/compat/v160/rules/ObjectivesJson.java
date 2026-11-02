package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.rules.Color;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Objectives, markers, game stats and map locales as vanilla's {@code JsonIO} writes and reads them: the rules'
 * {@code objectives} (JsonIO.java:280), a marker in {@code createMarker} ({@code TypeIO.writeObjectiveMarker}), the
 * markers of saves and world streams (UBJSON, {@code MapMarkers.write}), the save meta's {@code stats} and
 * {@code locales}.
 *
 * <p>Writing is Arc's reflective {@code Json.writeFields}: an object of a subclass starts with its class tag, fields
 * come superclass first in declaration order, and a field equal to a fresh instance's is left out ({@code Vec2} and
 * {@code Point2} are compared, then written member by member the same way). Content is written by name, teams by id,
 * colours as {@code rrggbbaa}. Reading is {@code Json.readFields}: members matched by exact name, unknown ones skipped,
 * with the coercions {@link RulesJson} has; a marker's {@code world}, {@code minimap} and {@code light} that aren't
 * integers read as 1 or -1 first (MapObjectives.java:789).
 */
public final class ObjectivesJson{
    private final ContentEpoch content;

    public ObjectivesJson(ContentEpoch content){
        this.content = content;
    }

    // region objectives

    /** The {@code MapObjectives} serializer's array. */
    public void writeObjectives(JsonSink out, MapObjectives objectives){
        out.array();
        for(MapObjective objective : objectives.all){
            out.object().name("class").value(objective.tag());
            writeFields(out, objective, objective.blank(), objectiveFields(objective));
            out.name("parents").array();
            for(int parent : objective.parents) out.value(parent);
            out.pop();
            out.name("editorPos").value(Point2.pack(objective.editorX, objective.editorY));
            out.pop();
        }
        out.pop();
    }

    /** {@code JsonIO.write(executor)}, the {@code setObjectives} payload. */
    public String writeObjectives(MapObjectives objectives){
        var out = new ArcJson.Writer();
        writeObjectives(out, objectives);
        return out.toString();
    }

    /**
     * The {@code MapObjectives} serializer's read: objectives in order, then their parents by index. A list written
     * with the old lowercase class tags reads as none at all; anything not an array too.
     */
    public MapObjectives readObjectives(JsonValue value){
        var out = new MapObjectives();
        if(!value.isArray()) return out;
        for(JsonValue child : value.children()){
            String tag = child.isObject() ? child.getString("class", null) : null;
            if(tag != null && !tag.isEmpty() && Character.isLowerCase(tag.charAt(0))) return new MapObjectives();
        }
        for(JsonValue child : value.children()){
            MapObjective objective = objective(child);
            if(child.has("editorPos")){
                int pos = child.getInt("editorPos", 0);
                objective.editorX = Point2.x(pos);
                objective.editorY = Point2.y(pos);
            }
            out.all.add(objective);
            objective.validate();
        }
        for(int i = 0; i < value.size(); i++){
            JsonValue parents = value.children().get(i).get("parents");
            // vanilla dereferences it unchecked (JsonIO.java:323)
            if(parents == null) throw new IllegalArgumentException("objective " + i + " has no parents");
            for(JsonValue parent : parents.children()){
                int index = RulesJson.readInt(parent);
                if(index >= 0 && index < out.all.size()) out.all.get(i).parents.add(index);
            }
        }
        return out;
    }

    private MapObjective objective(JsonValue json){
        if(!json.isObject()) throw new IllegalArgumentException("an objective must be an object: " + json);
        String tag = json.getString("class", null);
        MapObjective objective = tag == null ? null : MapObjective.byTag(classTag(tag, "Objective"));
        if(objective == null) throw new IllegalArgumentException("no objective class " + tag);
        readFields(objective, json, objectiveFields(objective));
        return objective;
    }

    /** A full class name ({@code mindustry.game.MapObjectives$TimerObjective}) reads as its tag, as Arc's class lookup allows. */
    private static String classTag(String tag, String suffix){
        int nested = tag.lastIndexOf('$');
        if(nested < 0 || !tag.endsWith(suffix)) return tag;
        return tag.substring(nested + 1, tag.length() - suffix.length());
    }

    @SuppressWarnings("unchecked")
    private List<F<MapObjective>> objectiveFields(MapObjective objective){
        // MapObjective's own fields, its private completed last, then the kind's
        var fields = new ArrayList<F<MapObjective>>(objectiveBase);
        fields.add(COMPLETED);
        List<? extends F<?>> own = switch(objective){
            case MapObjective.Research r -> List.of(F.<MapObjective.Research, ContentRef>of("content", o -> o.content, (o, v) -> o.content = v,
                (s, v) -> s.value(contentName(v)), this::unlockable));
            case MapObjective.Produce r -> List.of(F.<MapObjective.Produce, ContentRef>of("content", o -> o.content, (o, v) -> o.content = v,
                (s, v) -> s.value(contentName(v)), this::unlockable));
            case MapObjective.Item r -> List.of(
                F.<MapObjective.Item, Integer>of("item", o -> o.item, (o, v) -> o.item = v, item(), this::item),
                F.<MapObjective.Item, Integer>of("amount", o -> o.amount, (o, v) -> o.amount = v, value(), RulesJson::readInt));
            case MapObjective.CoreItem r -> List.of(
                F.<MapObjective.CoreItem, Integer>of("item", o -> o.item, (o, v) -> o.item = v, item(), this::item),
                F.<MapObjective.CoreItem, Integer>of("amount", o -> o.amount, (o, v) -> o.amount = v, value(), RulesJson::readInt));
            case MapObjective.BuildCount r -> List.of(
                F.<MapObjective.BuildCount, Integer>of("block", o -> o.block, (o, v) -> o.block = v, block(), this::block),
                F.<MapObjective.BuildCount, Integer>of("count", o -> o.count, (o, v) -> o.count = v, value(), RulesJson::readInt));
            case MapObjective.UnitCount r -> List.of(
                F.<MapObjective.UnitCount, Integer>of("unit", o -> o.unit, (o, v) -> o.unit = v,
                    (s, v) -> s.value(ContentLookup.name(content, ContentType.UNIT, v)), this::unit),
                F.<MapObjective.UnitCount, Integer>of("count", o -> o.count, (o, v) -> o.count = v, value(), RulesJson::readInt));
            case MapObjective.DestroyUnits r -> List.of(
                F.<MapObjective.DestroyUnits, Integer>of("count", o -> o.count, (o, v) -> o.count = v, value(), RulesJson::readInt));
            case MapObjective.Timer r -> List.of(
                F.<MapObjective.Timer, String>of("text", o -> o.text, (o, v) -> o.text = v, value(), RulesJson::readString),
                F.<MapObjective.Timer, Float>of("duration", o -> o.duration, (o, v) -> o.duration = v, value(), RulesJson::readFloat),
                F.<MapObjective.Timer, Float>of("countup", o -> o.countup, (o, v) -> o.countup = v, value(), RulesJson::readFloat));
            case MapObjective.DestroyBlock r -> List.of(
                F.<MapObjective.DestroyBlock, Point2>of("pos", o -> o.pos, (o, v) -> o.pos = v, ObjectivesJson::writePoint, ObjectivesJson::readPoint),
                F.<MapObjective.DestroyBlock, Integer>of("team", o -> o.team, (o, v) -> o.team = v, value(), RulesJson::readTeam),
                F.<MapObjective.DestroyBlock, Integer>of("block", o -> o.block, (o, v) -> o.block = v, block(), this::block));
            case MapObjective.DestroyBlocks r -> List.of(
                F.<MapObjective.DestroyBlocks, List<Point2>>of("positions", o -> o.positions, (o, v) -> o.positions = v,
                    (s, v) -> list(s, v, ObjectivesJson::writePoint), v -> readList(v, ObjectivesJson::readPoint)),
                F.<MapObjective.DestroyBlocks, Integer>of("team", o -> o.team, (o, v) -> o.team = v, value(), RulesJson::readTeam),
                F.<MapObjective.DestroyBlocks, Integer>of("block", o -> o.block, (o, v) -> o.block = v, block(), this::block));
            case MapObjective.Flag r -> List.of(
                F.<MapObjective.Flag, String>of("flag", o -> o.flag, (o, v) -> o.flag = v, value(), RulesJson::readString),
                F.<MapObjective.Flag, String>of("text", o -> o.text, (o, v) -> o.text = v, value(), RulesJson::readString));
            case MapObjective.CommandMode r -> List.of();
            case MapObjective.DestroyCore r -> List.of();
        };
        for(F<?> field : own) fields.add((F<MapObjective>)field);
        return fields;
    }

    private final List<F<MapObjective>> objectiveBase = List.of(
        F.<MapObjective, Boolean>of("hidden", o -> o.hidden, (o, v) -> o.hidden = v, value(), RulesJson::readBool),
        F.<MapObjective, String>of("details", o -> o.details, (o, v) -> o.details = v, value(), RulesJson::readString),
        F.<MapObjective, String>of("completionLogicCode", o -> o.completionLogicCode, (o, v) -> o.completionLogicCode = v, value(), RulesJson::readString),
        F.<MapObjective, List<String>>of("flagsAdded", o -> o.flagsAdded, (o, v) -> o.flagsAdded = v, ObjectivesJson::writeStrings, ObjectivesJson::readStrings),
        F.<MapObjective, List<String>>of("flagsRemoved", o -> o.flagsRemoved, (o, v) -> o.flagsRemoved = v, ObjectivesJson::writeStrings, ObjectivesJson::readStrings),
        F.<MapObjective, List<ObjectiveMarker>>of("markers", o -> o.markers, (o, v) -> o.markers = v,
            (s, v) -> list(s, v, this::writeMarker), v -> readList(v, this::readMarker)));
    private static final F<MapObjective> COMPLETED =
        F.<MapObjective, Boolean>of("completed", o -> o.completed, (o, v) -> o.completed = v, value(), RulesJson::readBool);

    // endregion
    // region markers

    /** A marker as an element of a {@code ObjectiveMarker}-typed field: its class tag, then its fields. */
    public void writeMarker(JsonSink out, ObjectiveMarker marker){
        out.object().name("class").value(marker.tag());
        writeFields(out, marker, marker.blank(), markerFields(marker));
        out.pop();
    }

    /** {@code JsonIO.json.toJson(marker, ObjectiveMarker.class)}: {@code createMarker}'s payload. */
    public String writeMarker(ObjectiveMarker marker){
        var out = new ArcJson.Writer();
        writeMarker(out, marker);
        return out.toString();
    }

    /** {@code ObjectiveMarker.read}: by class tag; null for JSON null. */
    public ObjectiveMarker readMarker(JsonValue json){
        if(json.isNull()) return null;
        if(!json.isObject()) throw new IllegalArgumentException("a marker must be an object: " + json);
        String tag = json.getString("class", null);
        ObjectiveMarker marker = tag == null ? null : ObjectiveMarker.byName(classTag(tag, "Marker"));
        if(marker == null) throw new IllegalArgumentException("no marker class " + tag);
        JsonValue flags = indexBools(json);
        readFields(marker, flags, markerFields(marker));
        String legacy = json.getString("textureName", null);
        if(legacy != null) marker.setTexture(ObjectiveMarker.Texture.string(legacy));
        return marker;
    }

    /** {@link #readMarker(JsonValue)}, with {@code building} telling whether a building stands at a packed position. */
    public ObjectiveMarker readMarker(JsonValue json, IntPredicate building){
        IntPredicate was = this.building;
        this.building = building;
        try{
            return readMarker(json);
        }finally{
            this.building = was;
        }
    }

    /** {@code MapMarkers.write}: UBJSON of the {@code IntMap}, ids as names in iteration order. */
    public byte[] writeMarkers(MapMarkers markers){
        var out = new UbJson.Writer();
        out.object();
        markers.each((id, marker) -> {
            out.name(Integer.toString(id));
            writeMarker(out, marker);
        });
        out.pop();
        return out.toBytes();
    }

    /** {@code MapMarkers.read}'s map: markers by id in the bytes' order, for {@link MapMarkers#load}. */
    public SequencedMap<Integer, ObjectiveMarker> readMarkers(byte[] bytes, IntPredicate building){
        JsonValue value = UbJson.parse(bytes);
        var out = new LinkedHashMap<Integer, ObjectiveMarker>();
        if(!value.isObject()) throw new IllegalArgumentException("markers must be an object");
        IntPredicate was = this.building;
        this.building = building;
        try{
            for(int i = 0; i < value.size(); i++) out.put(Integer.parseInt(value.names().get(i)), readMarker(value.children().get(i)));
        }finally{
            this.building = was;
        }
        return out;
    }

    /** Whether a building stands at a {@code Point2.pack} position, for a texture that names one; none while reading rules. */
    private IntPredicate building = pos -> false;

    /** A copy of the marker's JSON with {@code world}, {@code minimap} and {@code light} as vanilla's {@code updateField} leaves them. */
    private static JsonValue indexBools(JsonValue json){
        var out = JsonValue.object();
        var done = new TreeSet<String>();
        for(int i = 0; i < json.size(); i++){
            String name = json.names().get(i);
            JsonValue child = json.children().get(i);
            for(String flag : List.of("world", "minimap", "light")){
                // get(name) finds the first member of that name, ignoring case
                if(name != null && name.equalsIgnoreCase(flag) && done.add(flag) && child.type() != JsonValue.Type.LONG){
                    child = JsonValue.number(child.asBoolean() ? 1L : -1L, null);
                }
            }
            out.add(name, child);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<F<ObjectiveMarker>> markerFields(ObjectiveMarker marker){
        var fields = new ArrayList<F<ObjectiveMarker>>(MARKER_BASE);
        if(marker instanceof ObjectiveMarker.PosMarker) fields.add((F<ObjectiveMarker>)(F<?>)POS);
        List<? extends F<?>> own = switch(marker){
            case ObjectiveMarker.ShapeText m -> List.of(
                F.<ObjectiveMarker.ShapeText, String>of("text", o -> o.text, (o, v) -> o.text = v, value(), RulesJson::readString),
                F.<ObjectiveMarker.ShapeText, Float>of("fontSize", o -> o.fontSize, (o, v) -> o.fontSize = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.ShapeText, Float>of("textHeight", o -> o.textHeight, (o, v) -> o.textHeight = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.ShapeText, Byte>of("flags", o -> o.flags, (o, v) -> o.flags = v, value(), RulesJson::readByte),
                F.<ObjectiveMarker.ShapeText, Integer>of("textAlign", o -> o.textAlign, (o, v) -> o.textAlign = v, value(), RulesJson::readInt),
                F.<ObjectiveMarker.ShapeText, Integer>of("lineAlign", o -> o.lineAlign, (o, v) -> o.lineAlign = v, value(), RulesJson::readInt),
                F.<ObjectiveMarker.ShapeText, Float>of("radius", o -> o.radius, (o, v) -> o.radius = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.ShapeText, Float>of("rotation", o -> o.rotation, (o, v) -> o.rotation = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.ShapeText, Integer>of("sides", o -> o.sides, (o, v) -> o.sides = v, value(), RulesJson::readInt),
                F.<ObjectiveMarker.ShapeText, Color>of("color", o -> o.color, (o, v) -> o.color = v, COLOR, RulesJson::readColor));
            case ObjectiveMarker.Point m -> List.of(
                F.<ObjectiveMarker.Point, Float>of("radius", o -> o.radius, (o, v) -> o.radius = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Point, Float>of("stroke", o -> o.stroke, (o, v) -> o.stroke = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Point, Color>of("color", o -> o.color, (o, v) -> o.color = v, COLOR, RulesJson::readColor));
            case ObjectiveMarker.Shape m -> List.of(
                F.<ObjectiveMarker.Shape, Float>of("radius", o -> o.radius, (o, v) -> o.radius = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Shape, Float>of("rotation", o -> o.rotation, (o, v) -> o.rotation = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Shape, Float>of("stroke", o -> o.stroke, (o, v) -> o.stroke = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Shape, Float>of("startAngle", o -> o.startAngle, (o, v) -> o.startAngle = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Shape, Float>of("endAngle", o -> o.endAngle, (o, v) -> o.endAngle = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Shape, Boolean>of("fill", o -> o.fill, (o, v) -> o.fill = v, value(), RulesJson::readBool),
                F.<ObjectiveMarker.Shape, Boolean>of("outline", o -> o.outline, (o, v) -> o.outline = v, value(), RulesJson::readBool),
                F.<ObjectiveMarker.Shape, Integer>of("sides", o -> o.sides, (o, v) -> o.sides = v, value(), RulesJson::readInt),
                F.<ObjectiveMarker.Shape, Color>of("color", o -> o.color, (o, v) -> o.color = v, COLOR, RulesJson::readColor));
            case ObjectiveMarker.Text m -> List.of(
                F.<ObjectiveMarker.Text, String>of("text", o -> o.text, (o, v) -> o.text = v, value(), RulesJson::readString),
                F.<ObjectiveMarker.Text, Float>of("fontSize", o -> o.fontSize, (o, v) -> o.fontSize = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Text, Byte>of("flags", o -> o.flags, (o, v) -> o.flags = v, value(), RulesJson::readByte),
                F.<ObjectiveMarker.Text, Integer>of("textAlign", o -> o.textAlign, (o, v) -> o.textAlign = v, value(), RulesJson::readInt),
                F.<ObjectiveMarker.Text, Integer>of("lineAlign", o -> o.lineAlign, (o, v) -> o.lineAlign = v, value(), RulesJson::readInt));
            case ObjectiveMarker.Line m -> List.of(
                F.<ObjectiveMarker.Line, Vec2>of("endPos", o -> o.endPos, (o, v) -> o.endPos = v, ObjectivesJson::writeVec, ObjectivesJson::readVec, ObjectivesJson::sameVec),
                F.<ObjectiveMarker.Line, Float>of("stroke", o -> o.stroke, (o, v) -> o.stroke = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Line, Boolean>of("outline", o -> o.outline, (o, v) -> o.outline = v, value(), RulesJson::readBool),
                F.<ObjectiveMarker.Line, Color>of("color1", o -> o.color1, (o, v) -> o.color1 = v, COLOR, RulesJson::readColor),
                F.<ObjectiveMarker.Line, Color>of("color2", o -> o.color2, (o, v) -> o.color2 = v, COLOR, RulesJson::readColor));
            case ObjectiveMarker.TextureMarker m -> List.of(
                F.<ObjectiveMarker.TextureMarker, Float>of("rotation", o -> o.rotation, (o, v) -> o.rotation = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.TextureMarker, Float>of("width", o -> o.width, (o, v) -> o.width = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.TextureMarker, Float>of("height", o -> o.height, (o, v) -> o.height = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.TextureMarker, ObjectiveMarker.Texture>of("texture", o -> o.texture, (o, v) -> o.texture = v,
                    ObjectivesJson::writeTexture, this::readTexture, (a, b) -> false),
                F.<ObjectiveMarker.TextureMarker, Color>of("color", o -> o.color, (o, v) -> o.color = v, COLOR, RulesJson::readColor));
            case ObjectiveMarker.Quad m -> List.of(
                F.<ObjectiveMarker.Quad, ObjectiveMarker.Texture>of("texture", o -> o.texture, (o, v) -> o.texture = v,
                    ObjectivesJson::writeTexture, this::readTexture, (a, b) -> false),
                F.<ObjectiveMarker.Quad, float[]>of("vertices", o -> o.vertices, (o, v) -> o.vertices = v, ObjectivesJson::writeFloats,
                    ObjectivesJson::readFloats, Arrays::equals),
                F.<ObjectiveMarker.Quad, Boolean>of("mapRegion", o -> o.mapRegion, (o, v) -> o.mapRegion = v, value(), RulesJson::readBool));
            case ObjectiveMarker.Light m -> List.of(
                F.<ObjectiveMarker.Light, Float>of("radius", o -> o.radius, (o, v) -> o.radius = v, value(), RulesJson::readFloat),
                F.<ObjectiveMarker.Light, Color>of("color", o -> o.color, (o, v) -> o.color = v, COLOR, RulesJson::readColor));
        };
        for(F<?> field : own) fields.add((F<ObjectiveMarker>)field);
        return fields;
    }

    private static final List<F<ObjectiveMarker>> MARKER_BASE = List.of(
        F.<ObjectiveMarker, Integer>of("world", o -> o.world, (o, v) -> o.world = v, value(), RulesJson::readInt),
        F.<ObjectiveMarker, Integer>of("minimap", o -> o.minimap, (o, v) -> o.minimap = v, value(), RulesJson::readInt),
        F.<ObjectiveMarker, Integer>of("light", o -> o.light, (o, v) -> o.light = v, value(), RulesJson::readInt),
        F.<ObjectiveMarker, Boolean>of("autoscale", o -> o.autoscale, (o, v) -> o.autoscale = v, value(), RulesJson::readBool),
        F.<ObjectiveMarker, Float>of("drawLayer", o -> o.drawLayer, (o, v) -> o.drawLayer = v, value(), RulesJson::readFloat));
    private static final F<ObjectiveMarker.PosMarker> POS =
        F.of("pos", o -> o.pos, (o, v) -> o.pos = v, ObjectivesJson::writeVec, ObjectivesJson::readVec, ObjectivesJson::sameVec);

    /** {@code TextureHolder.write}: one member, or none for anything else. */
    private static void writeTexture(JsonSink out, ObjectiveMarker.Texture texture){
        out.object();
        switch(texture.kind()){
            case STRING -> out.name("string").value(texture.name());
            case CONTENT -> out.name("content").value(texture.name());
            case BUILDING -> out.name("building").value(texture.pos());
            case NONE -> {}
        }
        out.pop();
    }

    /** {@code TextureHolder.read}: {@code content.byName}, and a building as the world has it then. */
    private ObjectiveMarker.Texture readTexture(JsonValue json){
        if(json.has("string")){
            String name = json.get("string").asString();
            return name == null ? ObjectiveMarker.Texture.NONE : ObjectiveMarker.Texture.string(name);
        }else if(json.has("content")){
            String name = json.get("content").asString();
            return unlockableName(name) ? ObjectiveMarker.Texture.content(name) : ObjectiveMarker.Texture.NONE;
        }else if(json.has("building")){
            int pos = RulesJson.readInt(json.get("building"));
            return building.test(pos) ? ObjectiveMarker.Texture.building(pos) : ObjectiveMarker.Texture.NONE;
        }
        return ObjectiveMarker.Texture.WHITE;
    }

    // endregion
    // region stats and locales

    /** {@code JsonIO.write(state.stats)}: counts by content name, in id order where vanilla's is identity-hash order. */
    public String writeStats(GameStats stats){
        var out = new ArcJson.Writer().object();
        stat(out, "enemyUnitsDestroyed", stats.enemyUnitsDestroyed);
        stat(out, "wavesLasted", stats.wavesLasted);
        stat(out, "buildingsBuilt", stats.buildingsBuilt);
        stat(out, "buildingsDeconstructed", stats.buildingsDeconstructed);
        stat(out, "buildingsDestroyed", stats.buildingsDestroyed);
        stat(out, "unitsCreated", stats.unitsCreated);
        counts(out, "placedBlockCount", stats.placedBlockCount, ContentType.BLOCK);
        counts(out, "destroyedBlockCount", stats.destroyedBlockCount, ContentType.BLOCK);
        counts(out, "coreItemCount", stats.coreItemCount, ContentType.ITEM);
        return out.pop().toString();
    }

    private static void stat(ArcJson.Writer out, String name, int value){
        if(value != 0) out.name(name).value(value);
    }

    private void counts(ArcJson.Writer out, String name, TreeMap<Integer, Integer> counts, ContentType type){
        if(counts.isEmpty()) return;
        out.name(name).object();
        counts.forEach((id, count) -> out.name(ContentLookup.name(content, type, id)).value(count));
        out.pop();
    }

    /** {@code JsonIO.read(GameStats.class, json)}; content keys through vanilla's serializers (air, copper). */
    public GameStats readStats(String json){
        var stats = new GameStats();
        JsonValue value = ArcJson.parse(json);
        if(!value.isObject()) return stats;
        for(int i = 0; i < value.size(); i++){
            JsonValue child = value.children().get(i);
            switch(value.names().get(i)){
                case "enemyUnitsDestroyed" -> stats.enemyUnitsDestroyed = RulesJson.readInt(child);
                case "wavesLasted" -> stats.wavesLasted = RulesJson.readInt(child);
                case "buildingsBuilt" -> stats.buildingsBuilt = RulesJson.readInt(child);
                case "buildingsDeconstructed" -> stats.buildingsDeconstructed = RulesJson.readInt(child);
                case "buildingsDestroyed" -> stats.buildingsDestroyed = RulesJson.readInt(child);
                case "unitsCreated" -> stats.unitsCreated = RulesJson.readInt(child);
                case "placedBlockCount" -> stats.placedBlockCount = readCounts(child, this::block);
                case "destroyedBlockCount" -> stats.destroyedBlockCount = readCounts(child, this::block);
                case "coreItemCount" -> stats.coreItemCount = readCounts(child, this::item);
                default -> {}
            }
        }
        return stats;
    }

    private static TreeMap<Integer, Integer> readCounts(JsonValue json, Function<JsonValue, Integer> key){
        var out = new TreeMap<Integer, Integer>();
        for(int i = 0; i < json.size(); i++) out.put(key.apply(JsonValue.string(json.names().get(i))), json.children().get(i).asInt());
        return out;
    }

    /** {@code MapLocales.read}: each locale's strings. */
    public MapLocales readLocales(String json){
        var out = new MapLocales();
        JsonValue value = ArcJson.parse(json);
        for(int i = 0; i < value.size(); i++){
            JsonValue bundle = value.children().get(i);
            for(int j = 0; j < bundle.size(); j++) out.put(value.names().get(i), bundle.names().get(j), RulesJson.readString(bundle.children().get(j)));
        }
        return out;
    }

    // endregion
    // region content

    private String contentName(ContentRef ref){
        return ContentLookup.name(content, ref.type(), ref.id());
    }

    private ValueWriter<Integer> item(){
        return (s, v) -> s.value(ContentLookup.name(content, ContentType.ITEM, v));
    }

    private ValueWriter<Integer> block(){
        return (s, v) -> s.value(ContentLookup.name(content, ContentType.BLOCK, v));
    }

    /** Vanilla's {@code Item} serializer: null or unknown names read as copper. */
    private Integer item(JsonValue json){
        int id = ContentLookup.byName(content, ContentType.ITEM, json.asString());
        return id < 0 ? com.xpdustry.shard.sim.rules.VanillaIds.COPPER : id;
    }

    /** Vanilla's {@code UnitType} serializer: null or unknown names read as dagger. */
    private Integer unit(JsonValue json){
        int id = ContentLookup.byName(content, ContentType.UNIT, json.asString());
        return id < 0 ? com.xpdustry.shard.sim.rules.VanillaIds.DAGGER : id;
    }

    /** Vanilla's {@code Block} serializer: the name, then its fallback, then air. */
    private Integer block(JsonValue json){
        String name = json.asString();
        int id = ContentLookup.byName(content, ContentType.BLOCK, name);
        if(id < 0) id = ContentLookup.byName(content, ContentType.BLOCK, ContentLookup.BLOCK_FALLBACK.getOrDefault(name, ""));
        return Math.max(id, 0);
    }

    /** The unlockable types in reverse registration order: {@code content.byName} returns the last one registered. */
    private static final List<ContentType> UNLOCKABLE = List.of(ContentType.WEATHER, ContentType.BLOCK, ContentType.UNIT, ContentType.LIQUID,
        ContentType.STATUS, ContentType.ITEM);

    /**
     * Vanilla's {@code UnlockableContent} serializer: {@code content.byName}, if unlockable, else null. Planets and sector
     * presets have no Shard definitions and read as null too.
     */
    private ContentRef unlockable(JsonValue json){
        if(json.isNull()) return null;
        String name = json.asString();
        if(name == null || content.planets().contains(name)) return null;
        for(ContentType type : UNLOCKABLE){
            int id = content.id(type, name);
            if(id >= 0) return new ContentRef(type, id);
        }
        return null;
    }

    private boolean unlockableName(String name){
        if(name == null) return false;
        if(content.planets().contains(name)) return true;
        for(ContentType type : UNLOCKABLE){
            if(content.id(type, name) >= 0) return true;
        }
        return false;
    }

    // endregion
    // region reflection

    /** Writes one value. */
    private interface ValueWriter<T>{
        void write(JsonSink out, T value);
    }

    /** A string, number or boolean as itself. */
    private static <T> ValueWriter<T> value(){
        return JsonSink::value;
    }
    private static final ValueWriter<Color> COLOR = (s, v) -> s.value(v.hex());

    /** One reflected field: written unless {@code same} as a fresh instance's, read by exact name. */
    private record F<O>(String name, Function<O, Object> get, BiConsumer<O, Object> set, ValueWriter<Object> writer,
                        Function<JsonValue, Object> reader, BiPredicate<Object, Object> same){
        @SuppressWarnings("unchecked")
        static <O, T> F<O> of(String name, Function<O, T> get, BiConsumer<O, T> set, ValueWriter<? super T> writer,
                              Function<JsonValue, T> reader, BiPredicate<T, T> same){
            return new F<>(name, (Function<O, Object>)get, (BiConsumer<O, Object>)set, (ValueWriter<Object>)writer,
                (Function<JsonValue, Object>)reader, (BiPredicate<Object, Object>)same);
        }

        static <O, T> F<O> of(String name, Function<O, T> get, BiConsumer<O, T> set, ValueWriter<? super T> writer,
                              Function<JsonValue, T> reader){
            return of(name, get, set, writer, reader, Objects::equals);
        }
    }

    private static <O> void writeFields(JsonSink out, O object, O defaults, List<F<O>> fields){
        for(F<O> field : fields){
            Object value = field.get.apply(object), standard = field.get.apply(defaults);
            if(value == null && standard == null) continue;
            if(value != null && standard != null && field.same.test(value, standard)) continue;
            out.name(field.name);
            if(value == null) out.value(null);
            else field.writer.write(out, value);
        }
    }

    private static <O> void readFields(O object, JsonValue json, List<F<O>> fields){
        for(int i = 0; i < json.size(); i++){
            String name = json.names().get(i);
            if(name == null) continue;
            for(F<O> field : fields){
                if(!field.name.equals(name.replace(' ', '_'))) continue;
                JsonValue child = json.children().get(i);
                Object value = child.isNull() ? null : field.reader.apply(child);
                field.set.accept(object, value);
                break;
            }
        }
    }

    private static void writeStrings(JsonSink out, List<String> values){
        out.array();
        for(String value : values) out.value(value);
        out.pop();
    }

    private static List<String> readStrings(JsonValue json){
        if(!json.isArray()) throw new IllegalArgumentException("expected an array, got " + json);
        var out = new ArrayList<String>(json.size());
        for(JsonValue child : json.children()) out.add(child.isNull() ? null : RulesJson.readString(child));
        return out;
    }

    private static <T> void list(JsonSink out, List<T> values, ValueWriter<T> element){
        out.array();
        for(T value : values){
            if(value == null) out.value(null);
            else element.write(out, value);
        }
        out.pop();
    }

    private static <T> List<T> readList(JsonValue json, Function<JsonValue, T> element){
        if(!json.isArray()) throw new IllegalArgumentException("expected an array, got " + json);
        var out = new ArrayList<T>(json.size());
        for(JsonValue child : json.children()) out.add(child.isNull() ? null : element.apply(child));
        return out;
    }

    /** {@code Vec2} by {@code writeFields}: members left out where they equal {@code new Vec2()}'s 0, bit for bit. */
    private static void writeVec(JsonSink out, Vec2 value){
        out.object();
        if(Float.floatToIntBits(value.x) != 0) out.name("x").value(value.x);
        if(Float.floatToIntBits(value.y) != 0) out.name("y").value(value.y);
        out.pop();
    }

    /** Arc's {@code Vec2.equals}: by bits. */
    private static boolean sameVec(Vec2 a, Vec2 b){
        return Float.floatToIntBits(a.x) == Float.floatToIntBits(b.x) && Float.floatToIntBits(a.y) == Float.floatToIntBits(b.y);
    }

    private static Vec2 readVec(JsonValue json){
        var out = new Vec2();
        for(int i = 0; i < json.size(); i++){
            JsonValue child = json.children().get(i);
            switch(json.names().get(i)){
                case "x" -> out.x = RulesJson.readFloat(child);
                case "y" -> out.y = RulesJson.readFloat(child);
                default -> {}
            }
        }
        return out;
    }

    private static void writePoint(JsonSink out, Point2 value){
        out.object();
        if(value.x() != 0) out.name("x").value(value.x());
        if(value.y() != 0) out.name("y").value(value.y());
        out.pop();
    }

    private static Point2 readPoint(JsonValue json){
        int x = 0, y = 0;
        for(int i = 0; i < json.size(); i++){
            JsonValue child = json.children().get(i);
            switch(json.names().get(i)){
                case "x" -> x = RulesJson.readInt(child);
                case "y" -> y = RulesJson.readInt(child);
                default -> {}
            }
        }
        return new Point2(x, y);
    }

    private static void writeFloats(JsonSink out, float[] values){
        out.array();
        for(float value : values) out.value(value);
        out.pop();
    }

    private static float[] readFloats(JsonValue json){
        if(!json.isArray()) throw new IllegalArgumentException("expected an array, got " + json);
        float[] out = new float[json.size()];
        for(int i = 0; i < out.length; i++) out[i] = RulesJson.readFloat(json.children().get(i));
        return out;
    }

    // endregion
}
