package com.xpdustry.shard.oracle.dump;

import arc.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.io.*;
import com.xpdustry.shard.oracle.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Writes the raw vanilla dumps ({@code ./gradlew :tools:oracle:dump}). Argument: the output directory, normally
 * {@code shard/data/vanilla-v160.7}. Output is deterministic: two runs give the same bytes.
 */
public final class Dump{
    /** The dumps are committed; past this total, split or trim them. */
    static final long sizeLimit = 30L * 1024 * 1024;

    public static void main(String[] args) throws Exception{
        if(args.length != 1) throw new IllegalArgumentException("usage: Dump <output dir>");
        Path out = Path.of(args[0]).toAbsolutePath();
        Vanilla.boot();

        Path contentDir = out.resolve("content");
        if(Files.isDirectory(contentDir)){
            try(var files = Files.list(contentDir)){
                for(Path p : files.toList()) if(p.toString().endsWith(".json")) Files.delete(p);
            }
        }
        Files.createDirectories(contentDir);

        Map<String, Long> sizes = new LinkedHashMap<>();
        for(ContentType type : ContentType.all){
            if(content.getBy(type).isEmpty()) continue;
            write(out, "content/" + type.name() + ".json", ContentDump.dump(type), sizes);
        }
        write(out, "content-ids.json", ContentDump.ids(), sizes);
        write(out, "packets.json", PacketDump.dump(), sizes);
        write(out, "entities.json", EntityDump.entities(), sizes);
        write(out, "buildings.json", EntityDump.buildings(), sizes);
        write(out, "rules-default.json", rules(), sizes);
        write(out, "maps.json", maps(), sizes);

        long total = sizes.values().stream().mapToLong(Long::longValue).sum();
        Files.writeString(out.resolve("README.md"), readme(sizes, total));
        sizes.forEach((name, size) -> System.out.printf("%10s  %s%n", kib(size), name));
        System.out.printf("%10s  total%n", kib(total));
        if(total > sizeLimit){
            throw new IllegalStateException("dump is " + kib(total) + ", over the " + kib(sizeLimit) + " limit; lower ContentDump.depth or split");
        }
    }

    static void write(Path out, String name, Object value, Map<String, Long> sizes) throws IOException{
        Path file = out.resolve(name);
        Json.write(file, value);
        sizes.put(name, Files.size(file));
    }

    /** rules-default.json: {@code JsonIO.write(new Rules())} as vanilla writes it, parsed, and every field. */
    static Object rules(){
        Rules rules = new Rules();
        String json = JsonIO.write(rules);
        return Views.map(
            "note", "jsonIO is the exact string JsonIO.write(new Rules()) produces (what a world stream's rules section holds, "
                + "for default rules); parsed is the same as JSON; fields is every field of new Rules() by reflection.",
            "jsonIO", json,
            "parsed", Oracle.parseArcJson(json),
            "fields", Views.fields(rules, null, 6));
    }

    /** maps.json: the default (custom game) maps, then every other map in the jar (sectors, hidden maps), by path. */
    static Object maps() throws Exception{
        List<Object> defaults = new ArrayList<>();
        for(mindustry.maps.Map map : maps.all().copy().sort(Comparator.comparing(m -> m.file.name()))){
            defaults.add(map(map, "maps/" + map.file.name()));
        }

        Set<String> defaultFiles = new HashSet<>();
        for(mindustry.maps.Map map : maps.all()) defaultFiles.add(map.file.name());
        List<String> paths = new ArrayList<>();
        Path jar = Path.of(content.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        try(var zip = new ZipFile(jar.toFile())){
            for(var e : Collections.list(zip.entries())){
                String name = e.getName();
                if(!name.startsWith("maps/") || !name.endsWith("." + mapExtension)) continue;
                if(name.startsWith("maps/default/") || defaultFiles.contains(name.substring(5))) continue;
                paths.add(name);
            }
        }
        Collections.sort(paths);
        List<Object> internal = new ArrayList<>();
        for(String path : paths){
            internal.add(map(MapIO.createMap(Core.files.internal(path), false), path));
        }
        return Views.map(
            "note", "default: the maps a server's 'host' command and maps.all() offer. internal: every other .msav in the jar "
                + "(sector maps; load with Vanilla.host(\"serpulo/groundZero\")). tags are the map file's own tags, sorted; "
                + "'rules' is parsed from its JSON.",
            "default", defaults,
            "internal", internal);
    }

    static Object map(mindustry.maps.Map map, String path){
        TreeMap<String, Object> tags = new TreeMap<>();
        for(var e : map.tags.entries()){
            tags.put(e.key, e.key.equals("rules") ? Oracle.parseArcJson(e.value) : e.value);
        }
        return Views.map(
            "path", path,
            "file", map.file.nameWithoutExtension(),
            "name", map.name(),
            "author", map.author(),
            "width", map.width,
            "height", map.height,
            "version", map.version,
            "build", map.build,
            "teams", map.teams.size,
            "spawns", map.spawns,
            "tags", tags);
    }

    static String kib(long bytes){
        return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0) : String.format(Locale.ROOT, "%.2f MiB", bytes / 1024.0 / 1024.0);
    }

    static String readme(Map<String, Long> sizes, long total){
        StringBuilder files = new StringBuilder();
        sizes.forEach((name, size) -> files.append("| `").append(name).append("` | ").append(kib(size)).append(" |\n"));
        return """
            # Vanilla v%s.%s raw dumps

            Generated by `./gradlew :tools:oracle:dump` (`shard/tools/oracle`, class `com.xpdustry.shard.oracle.dump.Dump`) from the
            vanilla server jar. Do not edit by hand: regenerate. Output is deterministic, so a diff after regenerating
            means vanilla's data or the dumper changed.

            | File | What |
            |---|---|
            | `content/<type>.json` | Every content object of one `ContentType`, in id order: `id`, `name`, `class`, and `fields` (every field, public and not, inherited first, by reflection) |
            | `content-ids.json` | Names in id order per content type (the content header's order) |
            | `packets.json` | Packet ids, classes, priority, the side that accepts and handles them, body fields in order, and the `Call` methods that send them |
            | `entities.json` | Entity class ids, their names, and the field order of `writeSync` (snapshots) and `write` (world stream, saves) |
            | `buildings.json` | Building class and `version()` per block, which classes override which serializer, and their `write` field order |
            | `rules-default.json` | `JsonIO.write(new Rules())`, parsed, and all `Rules` fields |
            | `maps.json` | Built-in maps (default and sector/hidden) with size, teams, spawns and tags |

            ## Conventions in `fields`

            - `{"$ref": "block:conveyor"}`: another content object (unnamed content such as bullets: `"bullet:12"`).
            - `{"$static": "Fx.none"}`: a well-known static (`Fx`, `Sounds`, `Musics`, `Interp`, `Blending`, ...).
            - `{"$lambda": "mindustry.content.Blocks", "$type": "arc.func.Cons", ...}`: a lambda: where it was written, its
              interface, and its captured values.
            - `{"$class": ..., ...fields}`: any other object; `"$depth": true` when it is past the depth limit (%d), and
              `"$skipped": true` for graphics, audio and UI objects (null on a headless server anyway).
            - `{"$same": "path"}`: an object already shown at that path (shared references and cycles).
            - Colors are `"#rrggbbaa"`; `Vec2`, `Point2` and `Rect` are lists; unordered sets and maps are sorted.
            - Bytecode-derived layouts (`packets.json`, `entities.json`, `buildings.json`) list `ops` in write order:
              `field`, `codec` (the `Writes`/`TypeIO` call), and `loop` / `if` for repeated and conditional writes.

            ## Sizes

            | File | Size |
            |---|---|
            %s| **total** | **%s** |
            """.formatted(Vanilla.build, Vanilla.revision, ContentDump.depth, files, kib(total));
    }

    private Dump(){}
}
