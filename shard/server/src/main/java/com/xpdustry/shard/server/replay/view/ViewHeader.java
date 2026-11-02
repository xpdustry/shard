package com.xpdustry.shard.server.replay.view;

import com.xpdustry.shard.server.replay.view.ViewSource.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;
import tools.jackson.databind.node.*;

import java.util.*;

/**
 * A format 2 view stream's header ({@code docs/replay/FORMAT.md}): what was recorded, the content tables every segment
 * indexes into, the match, and the segment index with each segment's hash. It is JSON in the file and as
 * {@code header.json}.
 *
 * @param start the first tick, absolute; ticks in the stream count from it
 * @param ticks the length in ticks
 * @param frames records in the stream, keyframes included
 * @param complete whether the stream covers its match to the end
 * @param winner the winning team once the match ended in a game over, else -1
 * @param terrain the terrain chunk's compressed length
 */
public record ViewHeader(String producer, String game, String content, String map, int width, int height, int frameEvery, int keyframeEvery,
                         long start, long ticks, long frames, List<TeamView> teams, List<String> blocks, List<String> units,
                         List<BulletStyle> bullets, List<String> liquids, MatchView match, boolean complete, int winner, int terrain,
                         List<Index> segments){
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * One segment.
     *
     * @param offset from the first segment's length prefix in the file, or -1 when served in pieces
     * @param tick its keyframe's tick, from {@link #start}
     * @param hash SHA-256 of its inflated bytes, in hex
     */
    public record Index(long offset, int length, long tick, int frames, String hash){
    }

    public ViewHeader{
        teams = List.copyOf(teams);
        blocks = List.copyOf(blocks);
        units = List.copyOf(units);
        bullets = List.copyOf(bullets);
        liquids = List.copyOf(liquids);
        segments = List.copyOf(segments);
    }

    /** The header of {@code source}'s stream, with no segments yet. */
    public static ViewHeader of(ViewSource source, ViewWriter.Options options, long start){
        return new ViewHeader(source.producer(), source.game(), source.content(), source.map(), source.width(), source.height(),
            options.frameEvery(), options.keyframeEvery(), start, 0, 0, source.teams(), source.blocks(), source.units(), source.bullets(),
            source.liquids(), source.match(), false, -1, 0, List.of());
    }

    public ViewHeader withSegments(List<Index> segments, int terrain){
        long frames = 0, ticks = 0;
        for(Index index : segments){
            frames += index.frames();
            ticks = Math.max(ticks, index.tick());
        }
        return new ViewHeader(producer, game, content, map, width, height, frameEvery, keyframeEvery, start, ticks, frames, teams, blocks, units,
            bullets, liquids, match, complete, winner, terrain, segments);
    }

    /** {@link #ticks} set from the stream's last tick (absolute): the last segment's end. */
    public ViewHeader withEnd(long lastTick, boolean complete, int winner){
        return new ViewHeader(producer, game, content, map, width, height, frameEvery, keyframeEvery, start, Math.max(0, lastTick - start), frames,
            teams, blocks, units, bullets, liquids, match, complete, winner, terrain, segments);
    }

    public ViewHeader withTeams(List<TeamView> teams){
        return new ViewHeader(producer, game, content, map, width, height, frameEvery, keyframeEvery, start, ticks, frames, teams, blocks, units,
            bullets, liquids, match, complete, winner, terrain, segments);
    }

    public byte[] json(){
        var out = new LinkedHashMap<String, Object>();
        out.put("format", ViewWriter.FORMAT);
        out.put("producer", producer);
        out.put("game", game);
        out.put("content", content);
        out.put("map", map);
        out.put("width", width);
        out.put("height", height);
        out.put("tickRate", 60);
        out.put("frameEvery", frameEvery);
        out.put("keyframeEvery", keyframeEvery);
        out.put("start", start);
        out.put("ticks", ticks);
        out.put("frames", frames);
        var teamList = new ArrayList<Map<String, Object>>();
        for(TeamView team : teams) teamList.add(ordered("id", team.id(), "name", team.name(), "color", hex(team.color())));
        out.put("teams", teamList);
        out.put("blocks", blocks);
        out.put("units", units);
        var styles = new ArrayList<Map<String, Object>>();
        for(BulletStyle style : bullets){
            styles.add(ordered("name", style.name(), "kind", style.kind(), "sprite", style.sprite(), "width", finite(style.width()),
                "height", finite(style.height()), "front", hex(style.front()), "back", hex(style.back()), "shrinkY", finite(style.shrinkY()),
                "lifetime", finite(style.lifetime()), "trailLength", style.trailLength(), "trailWidth", finite(style.trailWidth()),
                "trailColor", hex(style.trailColor()), "hitSize", finite(style.hitSize())));
        }
        out.put("bullets", styles);
        out.put("liquids", liquids);
        out.put("effects", ViewWriter.EFFECTS);
        out.put("match", ordered("id", match.id(), "mode", match.mode(), "pvp", match.pvp(), "fog", match.fog(), "complete", complete, "winner", winner));
        out.put("terrain", terrain);
        var index = new ArrayList<Map<String, Object>>();
        for(Index segment : segments){
            var entry = new LinkedHashMap<String, Object>();
            if(segment.offset() >= 0) entry.put("offset", segment.offset());
            entry.put("length", segment.length());
            entry.put("tick", segment.tick());
            entry.put("frames", segment.frames());
            entry.put("hash", segment.hash());
            index.add(entry);
        }
        out.put("segments", index);
        return MAPPER.writeValueAsBytes(out);
    }

    /** Reads a format 2 header. */
    public static ViewHeader parse(byte[] json){
        JsonNode root = MAPPER.readTree(json);
        int format = root.path("format").asInt(-1);
        if(format != ViewWriter.FORMAT) throw new IllegalArgumentException("not a format " + ViewWriter.FORMAT + " header: format " + format);
        var teams = new ArrayList<TeamView>();
        for(JsonNode team : root.path("teams")) teams.add(new TeamView(team.path("id").asInt(), team.path("name").asString(), color(team.path("color"))));
        var styles = new ArrayList<BulletStyle>();
        for(JsonNode style : root.path("bullets")){
            styles.add(new BulletStyle(style.path("name").asString(), style.path("kind").asString(), style.path("sprite").asString(),
                (float)style.path("width").asDouble(), (float)style.path("height").asDouble(), color(style.path("front")), color(style.path("back")),
                (float)style.path("shrinkY").asDouble(), (float)style.path("lifetime").asDouble(), style.path("trailLength").asInt(),
                (float)style.path("trailWidth").asDouble(), color(style.path("trailColor")), (float)style.path("hitSize").asDouble()));
        }
        JsonNode match = root.path("match");
        var segments = new ArrayList<Index>();
        for(JsonNode segment : root.path("segments")){
            segments.add(new Index(segment.has("offset") ? segment.path("offset").asLong() : -1, segment.path("length").asInt(), segment.path("tick").asLong(),
                segment.path("frames").asInt(), segment.path("hash").asString()));
        }
        return new ViewHeader(root.path("producer").asString(), root.path("game").asString(), root.path("content").asString(), root.path("map").asString(),
            root.path("width").asInt(), root.path("height").asInt(), root.path("frameEvery").asInt(), root.path("keyframeEvery").asInt(),
            root.path("start").asLong(), root.path("ticks").asLong(), root.path("frames").asLong(), teams, strings(root.path("blocks")),
            strings(root.path("units")), styles, strings(root.path("liquids")),
            new MatchView(match.path("id").asString(), match.path("mode").asString(), match.path("pvp").asBoolean(), match.path("fog").asBoolean()),
            match.path("complete").asBoolean(), match.path("winner").asInt(-1), root.path("terrain").asInt(), segments);
    }

    private static List<String> strings(JsonNode array){
        var out = new ArrayList<String>();
        for(JsonNode node : array) out.add(node.asString());
        return out;
    }

    private static int color(JsonNode node){
        return (int)Long.parseLong(node.asString("00000000"), 16);
    }

    /** {@code value} as JSON holds it: an infinity (a bullet that never expires) as the largest float, NaN as 0. */
    private static float finite(float value){
        return Float.isNaN(value) ? 0f : Math.clamp(value, -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    private static String hex(int color){
        return String.format("%08x", color);
    }

    private static Map<String, Object> ordered(Object... pairs){
        var map = new LinkedHashMap<String, Object>();
        for(int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i + 1]);
        return map;
    }
}
