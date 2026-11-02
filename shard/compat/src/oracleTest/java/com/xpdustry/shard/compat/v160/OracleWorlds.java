package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Oracle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/** Shard and vanilla side by side on bundled maps, for the world stream and save tests. */
final class OracleWorlds{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    static final WorldProjection PROJECTION = new WorldProjection(Worlds.EPOCH, CODECS);

    /** Shard hosting a map: the loaded world, and the stream a new player named "oracle" gets. */
    record Hosted(MapLoader.Loaded loaded, WorldStreamSource source, byte[] raw){
        Rules rules(){
            return loaded.world().part(WorldRules.class).rules;
        }
    }

    /**
     * Shard's {@code host <map> survival}: the map file loaded as a map (generation filters included) with the mode's
     * rules, then a world stream. The world's generator starts as vanilla's {@code Mathf.rand} after
     * {@link Vanilla#reset()}.
     */
    static Hosted shardHost(String name){
        return shardHost(name, false);
    }

    /** {@link #shardHost(String)}, then, if {@code play}, {@link MapLoader#play} before the player joins: the full {@code host}. */
    static Hosted shardHost(String name, boolean play){
        SaveFile save = READER.read(BundledMaps.read(name));
        Rules rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), rules);
        if(play) MapLoader.play(loaded);
        PlayerState player = PlayerState.fresh(loaded.world().ids().next(), "oracle", rules.defaultTeam);
        WorldStreamSource source = PROJECTION.capture(loaded.world(), loaded.state(), player);
        return new Hosted(loaded, source, WorldStreamWriter.write(source));
    }

    /**
     * Vanilla's {@code host <map> survival} without starting the game: the map file through {@code SaveIO.load} with
     * the {@code FilterContext} {@code World.loadMap} uses (generation filters included), the map and its survival
     * rules set as {@code host} sets them, then the stream for a new player named "oracle". This is the world Shard's
     * loader models.
     */
    static byte[] vanillaLoaded(String name){
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(name);
        SaveIO.load(map.file, world.filterContext(map));
        Vanilla.runPosted();
        state.map = map;
        state.rules = map.applyRules(mindustry.game.Gamemode.survival);
        Player player = Player.create();
        player.name = "oracle";
        player.team(state.rules.defaultTeam);
        return Golden.worldStream(player).raw();
    }

    /** What a vanilla client has after reading a stream: the oracle's view and the loaded tiles. */
    record Decoded(WorldView view, Tiles tiles){
    }

    static Decoded decode(byte[] stream){
        Vanilla.reset();
        WorldView view = Oracle.decodeWorldStream(stream);
        return new Decoded(view, Tiles.current());
    }

    /** Vanilla's loaded tiles by position: content ids, team ids, and the position of the building's centre or -1. */
    record Tiles(int width, int height, int[] floor, int[] overlay, int[] block, int[] team, int[] center){
        static Tiles current(){
            int width = world.width(), height = world.height(), size = width * height;
            var tiles = new Tiles(width, height, new int[size], new int[size], new int[size], new int[size], new int[size]);
            for(Tile tile : world.tiles){
                int i = tile.x + tile.y * width;
                tiles.floor[i] = tile.floor().id;
                tiles.overlay[i] = tile.overlay().id;
                tiles.block[i] = tile.block().id;
                tiles.team[i] = tile.team().id;
                tiles.center[i] = tile.build == null ? -1 : tile.build.tile.x + tile.build.tile.y * width;
            }
            return tiles;
        }

        boolean building(int i){
            return center[i] != -1;
        }

        boolean same(Tiles other, int i){
            return floor[i] == other.floor[i] && overlay[i] == other.overlay[i] && block[i] == other.block[i]
                && team[i] == other.team[i] && center[i] == other.center[i];
        }

        String describe(int i){
            return "(" + i % width + ", " + i / width + ") floor=" + content.block(floor[i]).name + " overlay=" + content.block(overlay[i]).name
                + " block=" + content.block(block[i]).name + " team=" + team[i] + " center=" + center[i];
        }
    }

    /**
     * Rules JSON as Arc reads it, made comparable: object keys sorted, and {@code ObjectSet} values ({@code {values:[...]}})
     * sorted, since vanilla writes Arc's hash-ordered sets and maps in hash order and Shard in sorted order.
     */
    static String canonical(String json){
        return canonical(Oracle.parseArcJson(json)).toString();
    }

    @SuppressWarnings("unchecked")
    static Object canonical(Object value){
        if(value instanceof Map<?, ?> map){
            var out = new TreeMap<String, Object>();
            map.forEach((key, child) -> out.put(String.valueOf(key), canonical(child)));
            if(out.size() == 1 && out.get("values") instanceof List<?> list){
                var sorted = new ArrayList<Object>(list);
                sorted.sort(Comparator.comparing(String::valueOf));
                out.put("values", sorted);
            }
            return out;
        }
        if(value instanceof List<?> list){
            var out = new ArrayList<Object>();
            for(Object child : list) out.add(canonical(child));
            return out;
        }
        return value;
    }

    /** Every difference between two decoded streams, one line each, with the first few tile differences. */
    static List<String> differences(Decoded expected, Decoded actual){
        var out = new ArrayList<String>();
        WorldView e = expected.view(), a = actual.view();
        mapDifferences(out, "rules", canonical(Oracle.parseArcJson(e.rules())), canonical(Oracle.parseArcJson(a.rules())));
        check(out, "locales", e.locales(), a.locales());
        check(out, "tags", new TreeMap<>(e.tags()), new TreeMap<>(a.tags()));
        check(out, "wave", e.wave(), a.wave());
        check(out, "wavetime", e.wavetime(), a.wavetime());
        check(out, "tick", e.tick(), a.tick());
        check(out, "seed0", e.seed0(), a.seed0());
        check(out, "seed1", e.seed1(), a.seed1());
        check(out, "player", e.player(), a.player());
        check(out, "size", e.width() + "x" + e.height(), a.width() + "x" + a.height());
        out.addAll(tileDifferences(expected.tiles(), actual.tiles(), i -> true));
        out.addAll(listDifferences("building", e.buildings(), a.buildings()));
        out.addAll(listDifferences("entity", e.entities(), a.entities()));
        // a unit's AI timers are drawn from the client's Mathf.rand when it builds the controller: not stream content
        out.removeIf(line -> line.startsWith("entity ") && line.contains(" fields.controller.timer.times"));
        check(out, "plans", e.plans(), a.plans());
        check(out, "markers", e.markers(), a.markers());
        check(out, "custom chunks", e.customChunks(), a.customChunks());
        return out;
    }

    interface TileFilter{
        boolean test(int i);
    }

    static List<String> tileDifferences(Tiles e, Tiles a, TileFilter filter){
        var out = new ArrayList<String>();
        if(e.floor().length != a.floor().length) return List.of("tiles: " + e.floor().length + " vs " + a.floor().length);
        int count = 0;
        for(int i = 0; i < e.floor().length; i++){
            if(e.same(a, i) || !filter.test(i)) continue;
            if(count++ < 5) out.add("tile " + e.describe(i) + " vs " + a.describe(i));
        }
        if(count > 5) out.add("... " + count + " tiles differ");
        return out;
    }

    static <T extends Record> List<String> listDifferences(String what, List<T> expected, List<T> actual){
        var out = new ArrayList<String>();
        if(expected.size() != actual.size()) out.add(what + " count: " + expected.size() + " vs " + actual.size());
        for(int i = 0; i < Math.min(expected.size(), actual.size()); i++){
            T e = expected.get(i), a = actual.get(i);
            if(e.equals(a)) continue;
            for(RecordComponent component : e.getClass().getRecordComponents()){
                try{
                    Object x = component.getAccessor().invoke(e), y = component.getAccessor().invoke(a);
                    mapDifferences(out, what + " " + i + " " + label(e) + " " + component.getName(), x, y);
                }catch(ReflectiveOperationException ex){
                    throw new IllegalStateException(ex);
                }
            }
        }
        return out;
    }

    /** The differing keys of two maps and elements of two equally long lists, recursively; other values whole. */
    static void mapDifferences(List<String> out, String path, Object expected, Object actual){
        if(Objects.equals(expected, actual)) return;
        if(expected instanceof Map<?, ?> x && actual instanceof Map<?, ?> y){
            var keys = new LinkedHashSet<Object>(x.keySet());
            keys.addAll(y.keySet());
            for(Object key : keys) mapDifferences(out, path + "." + key, x.get(key), y.get(key));
            return;
        }
        if(expected instanceof List<?> x && actual instanceof List<?> y && x.size() == y.size()){
            for(int i = 0; i < x.size(); i++) mapDifferences(out, path + "[" + i + "]", x.get(i), y.get(i));
            return;
        }
        out.add(path + ": " + clip(expected) + " vs " + clip(actual));
    }

    private static String label(Record record){
        return record instanceof BuildingView b ? b.block() + "@" + b.x() + "," + b.y() : record instanceof EntityView v ? v.className() + "#" + v.id() : "";
    }

    static void check(List<String> out, String what, Object expected, Object actual){
        if(!Objects.equals(expected, actual)) out.add(what + ": " + clip(expected) + " vs " + clip(actual));
    }

    static String clip(Object value){
        String text = String.valueOf(value);
        return text.length() > 300 ? text.substring(0, 300) + "...(" + text.length() + ")" : text;
    }

    private OracleWorlds(){}
}
