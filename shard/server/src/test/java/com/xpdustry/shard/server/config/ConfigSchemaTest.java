package com.xpdustry.shard.server.config;

import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaTest{
    static final Path GENERATED = Path.of("build/generated-config");

    /** The schemas, reference and example in resources match ConfigKeys. */
    @Test
    void committedDocumentsAreCurrent() throws Exception{
        var stale = new ArrayList<String>();
        Files.createDirectories(GENERATED);
        for(var document : ConfigSchema.documents().entrySet()){
            Files.writeString(GENERATED.resolve(document.getKey()), document.getValue());
            String committed;
            try(var in = ConfigSchema.class.getResourceAsStream(document.getKey())){
                committed = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if(!document.getValue().equals(committed)) stale.add(document.getKey());
        }
        assertTrue(stale.isEmpty(), "stale " + stale + ": copy server/build/generated-config/* to "
            + "server/src/main/resources/com/xpdustry/shard/server/config/");
    }

    @Test
    void theExampleLoadsAsTheDefaults() throws Exception{
        var dir = Files.createTempDirectory("shard-config");
        Path file = Files.writeString(dir.resolve("shard.yml"), ConfigSchema.example());
        var config = ConfigLoader.load(file, null, Map.of());
        for(var key : ConfigKeys.ALL){
            assertEquals(key.defaultValue(), config.get(key), key.path());
            assertEquals(Config.Layer.FILE, config.source(key).layer(), key.path());
        }
    }

    @Test
    void theSchemaHasEveryKey(){
        JsonNode host = JsonMapper.shared().readTree(ConfigSchema.jsonSchema(false));
        JsonNode world = JsonMapper.shared().readTree(ConfigSchema.jsonSchema(true));
        for(var key : ConfigKeys.ALL){
            String[] parts = key.path().split("\\.");
            JsonNode node = host;
            for(String part : parts) node = node.get("properties").get(part);
            assertNotNull(node, key.path());
            assertEquals(key.reload().name().toLowerCase(Locale.ROOT), node.get("x-reload").asString(), key.path());
            assertEquals(key.env(), node.get("x-env").asString(), key.path());
            assertFalse(node.get("description").asString().isEmpty(), key.path());

            JsonNode inWorld = world.get("properties").get(parts[0]);
            inWorld = inWorld == null ? null : inWorld.get("properties").get(parts[1]);
            assertEquals(key.scope() == ConfigKey.Scope.WORLD, inWorld != null, key.path());
        }
        assertFalse(host.get("additionalProperties").asBoolean());
    }

    @Test
    void keysAreNotSections(){
        for(var key : ConfigKeys.ALL){
            for(var other : ConfigKeys.ALL){
                assertFalse(other.path().startsWith(key.path() + "."), other.path() + " is under " + key.path());
            }
        }
    }

    @Test
    void theReferenceListsEveryVanillaKey() throws IOException{
        String reference = ConfigSchema.reference();
        for(var vanilla : VanillaKeys.ALL) assertTrue(reference.contains("| `" + vanilla.name() + "` |"), vanilla.name());
    }
}
