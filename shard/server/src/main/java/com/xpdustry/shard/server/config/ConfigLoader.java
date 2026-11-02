package com.xpdustry.shard.server.config;

import com.xpdustry.shard.server.config.Config.*;
import com.xpdustry.shard.server.config.ConfigException.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.*;
import org.snakeyaml.engine.v2.exceptions.*;
import org.snakeyaml.engine.v2.nodes.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads the config layers ({@link Config} lists them) into one {@link Config}, or fails with every problem it found,
 * each with its {@code file:line:column} or environment variable. Unknown keys, duplicate keys, values of the wrong
 * type, host keys in world layers and unknown {@code SHARD__} variables are all errors.
 */
public final class ConfigLoader{
    public static final String ENV_PREFIX = "SHARD__";
    /** The {@code schema-version} this build reads. A file may omit it. */
    public static final int SCHEMA_VERSION = 1;
    static final String VERSION_KEY = "schema-version";
    static final int MAX_FILE_CHARS = 1 << 20;
    /** Plain scalars YAML 1.2's core schema reads as null. */
    static final Set<String> NULLS = Set.of("", "~", "null", "Null", "NULL");

    private final List<Problem> problems = new ArrayList<>();

    private ConfigLoader(){
    }

    /**
     * Loads a config.
     *
     * @param file {@code shard.yml}; null for none. A path that doesn't exist is an error.
     * @param worldsDir the directory of {@code <world-id>.yml} overrides; null or missing for none
     * @param env the environment, normally {@link System#getenv()}; only {@code SHARD__} names are read
     */
    public static Config load(Path file, Path worldsDir, Map<String, String> env) throws ConfigException{
        return new ConfigLoader().run(file, worldsDir, env);
    }

    /** Loads {@code shard.yml} and {@code worlds/} from a directory, both optional. */
    public static Config loadDirectory(Path dir, Map<String, String> env) throws ConfigException{
        Path file = dir.resolve("shard.yml");
        return load(Files.exists(file) ? file : null, dir.resolve("worlds"), env);
    }

    private Config run(Path file, Path worldsDir, Map<String, String> env) throws ConfigException{
        var host = new LinkedHashMap<ConfigKey<?>, Entry>();
        for(var key : ConfigKeys.ALL) host.put(key, new Entry(key.defaultValue(), Source.DEFAULT));
        var worlds = new TreeMap<String, Map<ConfigKey<?>, Entry>>();

        if(file != null){
            if(Files.isRegularFile(file)){
                readFile(file, file.toString(), Layer.FILE, host, false);
            }else{
                problems.add(new Problem(file.toString(), "no such file"));
            }
        }

        var sortedEnv = new TreeMap<>(env);
        for(var variable : sortedEnv.entrySet()){
            if(variable.getKey().startsWith(ENV_PREFIX)) readEnv(variable.getKey(), variable.getValue(), host, worlds);
        }

        if(worldsDir != null && Files.isDirectory(worldsDir)){
            readWorldFiles(worldsDir, worlds);
        }

        if(!problems.isEmpty()) throw new ConfigException(problems);
        var frozenWorlds = new TreeMap<String, Map<ConfigKey<?>, Entry>>();
        worlds.forEach((id, entries) -> frozenWorlds.put(id, Collections.unmodifiableMap(entries)));
        return new Config(Collections.unmodifiableMap(host), Collections.unmodifiableMap(frozenWorlds));
    }

    private void readWorldFiles(Path dir, TreeMap<String, Map<ConfigKey<?>, Entry>> worlds){
        List<Path> files;
        try(var stream = Files.list(dir)){
            files = stream.sorted().toList();
        }catch(IOException e){
            problems.add(new Problem(dir.toString(), "cannot list: " + e.getMessage()));
            return;
        }
        // read every file before the environment overrides land on top
        var fromFiles = new TreeMap<String, Map<ConfigKey<?>, Entry>>();
        for(Path file : files){
            String name = file.getFileName().toString();
            if(name.endsWith(".yaml")){
                problems.add(new Problem(file.toString(), "world files end in .yml"));
                continue;
            }
            if(!name.endsWith(".yml") || !Files.isRegularFile(file)) continue;
            String id = name.substring(0, name.length() - ".yml".length());
            if(!ConfigKeys.WORLD_ID.matcher(id).matches()){
                problems.add(new Problem(file.toString(), "a world file is named <world-id>.yml, with an id of lower-case letters, digits and dashes"));
                continue;
            }
            var entries = new LinkedHashMap<ConfigKey<?>, Entry>();
            readFile(file, file.toString(), Layer.WORLD_FILE, entries, true);
            fromFiles.put(id, entries);
        }
        // the world environment layer is above the world files: re-apply it
        fromFiles.forEach((id, entries) -> {
            var envEntries = worlds.get(id);
            if(envEntries != null) entries.putAll(envEntries);
            worlds.put(id, entries);
        });
    }

    // YAML

    private void readFile(Path file, String label, Layer layer, Map<ConfigKey<?>, Entry> into, boolean world){
        String text;
        try{
            text = Files.readString(file, StandardCharsets.UTF_8);
        }catch(IOException e){
            problems.add(new Problem(label, "cannot read: " + e.getMessage()));
            return;
        }
        readYaml(text, label, layer, into, world);
    }

    void readYaml(String text, String label, Layer layer, Map<ConfigKey<?>, Entry> into, boolean world){
        if(text.length() > MAX_FILE_CHARS){
            problems.add(new Problem(label, "larger than " + MAX_FILE_CHARS + " characters"));
            return;
        }
        var settings = LoadSettings.builder()
            .setLabel(label)
            .setAllowDuplicateKeys(true) // found below, with both locations
            .setMaxAliasesForCollections(16)
            .setCodePointLimit(MAX_FILE_CHARS)
            .build();
        Optional<Node> root;
        try{
            root = new Compose(settings).composeString(text);
        }catch(MarkedYamlEngineException e){
            String where = e.getProblemMark().map(mark -> at(label, mark)).orElse(label);
            problems.add(new Problem(where, "not valid YAML: " + e.getProblem()));
            return;
        }catch(YamlEngineException e){
            problems.add(new Problem(label, "not valid YAML: " + e.getMessage()));
            return;
        }
        if(root.isEmpty()) return;
        if(!(root.get() instanceof MappingNode mapping)){
            problems.add(new Problem(at(label, root.get()), "expected a mapping of config sections"));
            return;
        }
        walk(mapping, "", label, layer, into, world, new HashMap<>());
    }

    private void walk(MappingNode mapping, String prefix, String label, Layer layer, Map<ConfigKey<?>, Entry> into,
                      boolean world, Map<String, String> seen){
        for(NodeTuple tuple : mapping.getValue()){
            if(!(tuple.getKeyNode() instanceof ScalarNode keyNode)){
                problems.add(new Problem(at(label, tuple.getKeyNode()), "keys are plain strings"));
                continue;
            }
            String path = prefix.isEmpty() ? keyNode.getValue() : prefix + "." + keyNode.getValue();
            String where = at(label, keyNode);
            String first = seen.putIfAbsent(path, where);
            if(first != null){
                problems.add(new Problem(where, "duplicate key " + path + " (first set at " + first + ")"));
                continue;
            }
            Node value = tuple.getValueNode();

            if(path.equals(VERSION_KEY)){
                readVersion(value, label);
                continue;
            }
            ConfigKey<?> key = ConfigKeys.byPath(path);
            if(key == null){
                if(isSection(path)){
                    if(value instanceof MappingNode section){
                        walk(section, path, label, layer, into, world, seen);
                    }else{
                        problems.add(new Problem(where, path + " is a section; expected a mapping of " + String.join(", ", sectionKeys(path))));
                    }
                }else{
                    problems.add(new Problem(where, "unknown key " + path + suggest(path)));
                }
                continue;
            }
            if(world && key.scope() == ConfigKey.Scope.HOST){
                problems.add(new Problem(where, key.path() + " is host-wide; set it in shard.yml, not per world"));
                continue;
            }
            Object parsed = parse(key, value, at(label, value));
            if(parsed != null) into.put(key, new Entry(parsed, new Source(layer, where)));
        }
    }

    private Object parse(ConfigKey<?> key, Node value, String where){
        try{
            return switch(value){
                case ScalarNode scalar when scalar.isPlain() && NULLS.contains(scalar.getValue()) ->
                    throw new IllegalArgumentException("no value; write \"\" for an empty string");
                case ScalarNode scalar -> key.type().parse(scalar.getValue());
                case SequenceNode sequence -> {
                    var items = new ArrayList<String>();
                    for(Node item : sequence.getValue()){
                        if(!(item instanceof ScalarNode scalar)) throw new IllegalArgumentException("list items are plain strings");
                        items.add(scalar.getValue());
                    }
                    yield key.type().parseItems(items);
                }
                default -> throw new IllegalArgumentException("expected " + key.type().describe() + ", got a mapping");
            };
        }catch(IllegalArgumentException e){
            problems.add(new Problem(where, key.path() + ": " + e.getMessage()));
            return null;
        }
    }

    private void readVersion(Node value, String label){
        if(value instanceof ScalarNode scalar){
            try{
                int version = Integer.parseInt(scalar.getValue());
                if(version == SCHEMA_VERSION) return;
                problems.add(new Problem(at(label, value), VERSION_KEY + " " + version + " is not supported: this build reads "
                    + SCHEMA_VERSION + (version > SCHEMA_VERSION ? " (the file is for a newer Shard)" : "")));
                return;
            }catch(NumberFormatException ignored){
            }
        }
        problems.add(new Problem(at(label, value), VERSION_KEY + " must be the integer " + SCHEMA_VERSION));
    }

    // environment

    private void readEnv(String name, String text, Map<ConfigKey<?>, Entry> host, Map<String, Map<ConfigKey<?>, Entry>> worlds){
        String where = "env " + name;
        String rest = name.substring(ENV_PREFIX.length());
        String world = null;
        if(rest.startsWith("WORLDS__")){
            rest = rest.substring("WORLDS__".length());
            int split = rest.indexOf("__");
            if(split <= 0){
                problems.add(new Problem(where, "expected SHARD__WORLDS__<WORLD_ID>__<SECTION>__<KEY>"));
                return;
            }
            world = rest.substring(0, split).toLowerCase(Locale.ROOT).replace('_', '-');
            rest = rest.substring(split + 2);
            if(!ConfigKeys.WORLD_ID.matcher(world).matches()){
                problems.add(new Problem(where, "bad world id " + world));
                return;
            }
        }
        ConfigKey<?> key = null;
        for(var candidate : ConfigKeys.ALL){
            if(candidate.envSuffix().equals(rest)) key = candidate;
        }
        if(key == null){
            String path = rest.toLowerCase(Locale.ROOT).replace("__", ".").replace('_', '-');
            problems.add(new Problem(where, "unknown key " + path + suggest(path)));
            return;
        }
        if(world != null && key.scope() == ConfigKey.Scope.HOST){
            problems.add(new Problem(where, key.path() + " is host-wide; it has no per-world variable"));
            return;
        }
        try{
            var entry = new Entry(key.type().parse(text), new Source(world == null ? Layer.ENV : Layer.WORLD_ENV, where));
            (world == null ? host : worlds.computeIfAbsent(world, id -> new LinkedHashMap<>())).put(key, entry);
        }catch(IllegalArgumentException e){
            problems.add(new Problem(where, key.path() + ": " + e.getMessage()));
        }
    }

    /** A world id as it appears in an environment variable name. */
    static String envWorld(String world){
        return world.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    // helpers

    private static boolean isSection(String path){
        for(var key : ConfigKeys.ALL) if(key.path().startsWith(path + ".")) return true;
        return false;
    }

    private static List<String> sectionKeys(String section){
        var keys = new ArrayList<String>();
        for(var key : ConfigKeys.ALL){
            if(key.path().startsWith(section + ".")) keys.add(key.path().substring(section.length() + 1));
        }
        return keys;
    }

    /**
     * {@code "; did you mean x?"}: a key with the same name in another section, else the nearest key within three
     * edits, else nothing.
     */
    static String suggest(String path){
        String name = path.substring(path.lastIndexOf('.') + 1);
        for(var key : ConfigKeys.ALL){
            if(key.path().substring(key.path().lastIndexOf('.') + 1).equals(name)) return "; did you mean " + key.path() + "?";
        }
        String best = null;
        int bestDistance = 4;
        for(var key : ConfigKeys.ALL){
            int distance = distance(path, key.path());
            if(distance < bestDistance){
                bestDistance = distance;
                best = key.path();
            }
        }
        return best == null ? "" : "; did you mean " + best + "?";
    }

    private static int distance(String a, String b){
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for(int j = 0; j <= b.length(); j++) previous[j] = j;
        for(int i = 1; i <= a.length(); i++){
            current[0] = i;
            for(int j = 1; j <= b.length(); j++){
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String at(String label, Node node){
        return node.getStartMark().map(mark -> at(label, mark)).orElse(label);
    }

    private static String at(String label, Mark mark){
        return label + ":" + (mark.getLine() + 1) + ":" + (mark.getColumn() + 1);
    }
}
