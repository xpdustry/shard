package com.xpdustry.shard.server.config;

import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.util.*;

/**
 * Generates the documents that describe {@link ConfigKeys}: JSON Schemas for {@code shard.yml} and for world files
 * (editors validate with them), the Markdown reference with the vanilla mapping, and a commented example with every
 * default. The committed copies live next to this class in resources; {@code ConfigSchemaTest} fails when they are
 * stale.
 */
public final class ConfigSchema{
    public static final String HOST_SCHEMA = "shard.schema.json";
    public static final String WORLD_SCHEMA = "world.schema.json";
    public static final String REFERENCE = "reference.md";
    public static final String EXAMPLE = "shard.example.yml";

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Every generated document, by file name. */
    public static SortedMap<String, String> documents(){
        var documents = new TreeMap<String, String>();
        documents.put(HOST_SCHEMA, jsonSchema(false));
        documents.put(WORLD_SCHEMA, jsonSchema(true));
        documents.put(REFERENCE, reference());
        documents.put(EXAMPLE, example());
        return documents;
    }

    /** The JSON Schema (draft 2020-12) of {@code shard.yml}, or with {@code world} of {@code worlds/<id>.yml}. */
    public static String jsonSchema(boolean world){
        var root = new LinkedHashMap<String, Object>();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", world ? "Shard world overrides (worlds/<id>.yml)" : "Shard host config (shard.yml)");
        root.put("type", "object");
        root.put("additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ConfigLoader.VERSION_KEY, Map.of("const", ConfigLoader.SCHEMA_VERSION));
        root.put("properties", properties);
        for(var key : ConfigKeys.ALL){
            if(world && key.scope() != ConfigKey.Scope.WORLD) continue;
            String[] parts = key.path().split("\\.");
            var level = properties;
            for(int i = 0; i < parts.length - 1; i++) level = section(level, parts[i]);
            level.put(parts[parts.length - 1], property(key, world));
        }
        return JSON.writeValueAsString(root) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> properties, String name){
        var section = (Map<String, Object>)properties.computeIfAbsent(name, n -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("type", "object");
            map.put("additionalProperties", false);
            map.put("properties", new LinkedHashMap<String, Object>());
            return map;
        });
        return (Map<String, Object>)section.get("properties");
    }

    private static Map<String, Object> property(ConfigKey<?> key, boolean world){
        var property = new LinkedHashMap<String, Object>();
        property.put("description", key.description());
        property.putAll(key.type().schema());
        if(!key.secret()) property.put("default", key.type().jsonAny(key.defaultValue()));
        property.put("x-scope", lower(key.scope()));
        property.put("x-reload", lower(key.reload()));
        property.put("x-env", world ? key.env("<world-id>") : key.env());
        return property;
    }

    /** The Markdown reference: every key, then what became of vanilla's settings. */
    public static String reference(){
        var out = new StringBuilder();
        out.append("# Shard config reference\n\n")
            .append("Generated from `ConfigKeys` by `ConfigSchema`; do not edit. Precedence, low to high: defaults, `shard.yml`,\n")
            .append("`SHARD__<SECTION>__<KEY>`, `worlds/<id>.yml`, `SHARD__WORLDS__<ID>__<SECTION>__<KEY>`. World layers may only set\n")
            .append("world-scoped keys. A `live` key applies on `config reload`; a `restart` key is reported as pending until a restart.\n");
        String section = null;
        for(var key : ConfigKeys.ALL){
            String keySection = key.path().substring(0, key.path().indexOf('.'));
            if(!keySection.equals(section)){
                section = keySection;
                out.append("\n## ").append(section).append("\n\n")
                    .append("| Key | Type | Default | Scope | Reload | Description |\n")
                    .append("|---|---|---|---|---|---|\n");
            }
            out.append("| `").append(key.path()).append("` | ").append(cell(key.type().describe()))
                .append(" | `").append(cell(key.display(key.defaultValue()))).append("` | ")
                .append(lower(key.scope())).append(" | ").append(lower(key.reload())).append(" | ")
                .append(cell(key.description())).append(" |\n");
        }
        out.append("\n## Vanilla settings\n\n")
            .append("Every `Administration.Config` key of v160.7 (`Administration.java:534`), plus `playerlimit` and `shufflemode`.\n\n")
            .append("| Vanilla | Shard | Note |\n|---|---|---|\n");
        for(var vanilla : VanillaKeys.ALL){
            out.append("| `").append(vanilla.name()).append("` | ")
                .append(vanilla.isDropped() ? "dropped" : "`" + vanilla.key().path() + "`").append(" | ")
                .append(cell(vanilla.note())).append(" |\n");
        }
        return out.toString();
    }

    /** A {@code shard.yml} that sets every key to its default, with each description as a comment. */
    public static String example(){
        var out = new StringBuilder();
        out.append("# Every Shard config key at its default. Generated by ConfigSchema; see reference.md.\n")
            .append(ConfigLoader.VERSION_KEY).append(": ").append(ConfigLoader.SCHEMA_VERSION).append('\n');
        String section = null;
        for(var key : ConfigKeys.ALL){
            int dot = key.path().indexOf('.');
            String keySection = key.path().substring(0, dot);
            if(!keySection.equals(section)){
                section = keySection;
                out.append('\n').append(section).append(":\n");
            }
            out.append("  # ").append(key.description()).append(" (").append(lower(key.scope())).append(", ")
                .append(lower(key.reload())).append(")\n")
                .append("  ").append(key.path().substring(dot + 1)).append(": ")
                .append(key.secret() ? "\"\"" : key.type().formatAny(key.defaultValue())).append('\n');
        }
        return out.toString();
    }

    private static String cell(String text){
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static String lower(Enum<?> value){
        return value.name().toLowerCase(Locale.ROOT);
    }

    private ConfigSchema(){}
}
