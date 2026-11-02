package com.xpdustry.shard.server.config;

import com.xpdustry.shard.server.config.ConfigException.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.xpdustry.shard.server.config.ConfigKeys.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest{
    @TempDir
    Path dir;

    Path file(String name, String text) throws Exception{
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    List<Problem> problems(Path file, Path worlds, Map<String, String> env){
        var error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, worlds, env));
        return error.problems();
    }

    static void assertProblem(List<Problem> problems, String location, String messagePart){
        for(var problem : problems){
            if(problem.location().equals(location) && problem.message().contains(messagePart)) return;
        }
        fail("no problem at " + location + " containing '" + messagePart + "' in:\n" + String.join("\n", problems.stream().map(Problem::toString).toList()));
    }

    @Test
    void nothingGivesTheDefaults() throws Exception{
        var config = ConfigLoader.load(null, null, Map.of("PATH", "/bin", "SHARD_TEST_FORKS", "3"));
        for(var key : ConfigKeys.ALL){
            assertEquals(key.defaultValue(), config.get(key), key.path());
            assertEquals(Config.Layer.DEFAULT, config.source(key).layer());
        }
    }

    @Test
    void layersApplyInOrder() throws Exception{
        Path shard = file("shard.yml", """
            schema-version: 1
            server:
              name: "From file"
              motd: host motd
            net:
              port: 6000
              snapshot-interval: 100ms
            """);
        file("worlds/alpha.yml", """
            net:
              snapshot-interval: 50ms
            server:
              motd: alpha motd
            """);
        file("worlds/beta-2.yml", "chat:\n  show-connect-messages: false\n");
        var env = Map.of(
            "SHARD__NET__PORT", "7000",
            "SHARD__SERVER__MOTD", "env motd",
            "SHARD__WORLDS__ALPHA__NET__SNAPSHOT_INTERVAL", "25ms",
            "SHARD__WORLDS__GAMMA__GAME__VOTEKICK", "false");
        var config = ConfigLoader.load(shard, dir.resolve("worlds"), env);

        // file over default, env over file
        assertEquals("From file", config.get(SERVER_NAME));
        assertEquals(dir.resolve("shard.yml") + ":3:3", config.source(SERVER_NAME).location());
        assertEquals(7000, config.get(NET_PORT));
        assertEquals("env SHARD__NET__PORT", config.source(NET_PORT).location());
        assertEquals("env motd", config.get(SERVER_MOTD));
        assertEquals(Duration.ofMillis(100), config.get(NET_SNAPSHOT_INTERVAL));

        // world file over host, world env over world file
        var alpha = config.world("alpha");
        assertEquals("alpha motd", alpha.get(SERVER_MOTD));
        assertEquals(Config.Layer.WORLD_FILE, alpha.source(SERVER_MOTD).layer());
        assertEquals(Duration.ofMillis(25), alpha.get(NET_SNAPSHOT_INTERVAL));
        assertEquals("env SHARD__WORLDS__ALPHA__NET__SNAPSHOT_INTERVAL", alpha.source(NET_SNAPSHOT_INTERVAL).location());
        assertEquals(7000, alpha.get(NET_PORT), "host keys read through a world view");

        assertFalse(config.world("beta-2").get(CHAT_SHOW_CONNECT_MESSAGES));
        assertTrue(alpha.get(CHAT_SHOW_CONNECT_MESSAGES));
        assertFalse(config.world("gamma").get(GAME_VOTEKICK), "a world env variable needs no world file");
        assertEquals("env motd", config.world("unknown").get(SERVER_MOTD), "a world without overrides sees the host");
        assertEquals(new TreeSet<>(List.of("alpha", "beta-2", "gamma")), config.worldsWithOverrides());
    }

    @Test
    void typesParse() throws Exception{
        Path shard = file("shard.yml", """
            net.bind: 10.1.2.3
            console:
              start-commands: [host, "say hi"]
            log:
              max-file-size: 64MiB
              level: debug
            admin:
              uuid-change-period: 2d
            """);
        var config = ConfigLoader.load(shard, null, Map.of("SHARD__STORAGE__SYNCHRONOUS", "normal"));
        assertEquals(InetAddress.ofLiteral("10.1.2.3"), config.get(NET_BIND));
        assertEquals(List.of("host", "say hi"), config.get(CONSOLE_START_COMMANDS));
        assertEquals(64L << 20, config.get(LOG_MAX_FILE_SIZE));
        assertEquals(LogLevel.DEBUG, config.get(LOG_LEVEL));
        assertEquals(Duration.ofDays(2), config.get(ADMIN_UUID_CHANGE_PERIOD));
        assertEquals(Synchronous.NORMAL, config.get(STORAGE_SYNCHRONOUS));
        var env = ConfigLoader.load(null, null, Map.of("SHARD__CONSOLE__START_COMMANDS", "a, b"));
        assertEquals(List.of("a", "b"), env.get(CONSOLE_START_COMMANDS));
    }

    @Test
    void everyProblemIsReportedWithItsLocation() throws Exception{
        Path shard = file("shard.yml", """
            schema-version: 2
            net:
              prot: 1
              port: 70000
              strict: yes
            server:
              name: a
              name: b
            ops: 7
            ops:
              bind: 0.0.0.0
            storage:
              readers: ~
            game:
              votekick: [true]
            """);
        String at = shard.toString();
        var problems = problems(shard, null, Map.of(
            "SHARD__NET__PORT", "http",
            "SHARD__NOPE__THING", "1",
            "SHARD__WORLDS__ALPHA__NET__PORT", "7000",
            "SHARD__WORLDS__BAD!ID__GAME__VOTEKICK", "true"));
        assertProblem(problems, at + ":1:17", "schema-version 2 is not supported: this build reads 1 (the file is for a newer Shard)");
        assertProblem(problems, at + ":3:3", "unknown key net.prot; did you mean net.port?");
        assertProblem(problems, at + ":4:9", "net.port: expected an integer in [0, 65535], got 70000");
        assertProblem(problems, at + ":5:3", "unknown key net.strict; did you mean game.strict?");
        assertProblem(problems, at + ":8:3", "duplicate key server.name (first set at " + at + ":7:3)");
        assertProblem(problems, at + ":9:1", "ops is a section; expected a mapping of enabled, bind, port");
        assertProblem(problems, at + ":10:1", "duplicate key ops");
        assertProblem(problems, at + ":13:12", "storage.readers: no value");
        assertProblem(problems, at + ":15:13", "game.votekick: expected true or false, got a list");
        assertProblem(problems, "env SHARD__NET__PORT", "expected an integer in [0, 65535], got 'http'");
        assertProblem(problems, "env SHARD__NOPE__THING", "unknown key nope.thing");
        assertProblem(problems, "env SHARD__WORLDS__ALPHA__NET__PORT", "net.port is host-wide");
        assertProblem(problems, "env SHARD__WORLDS__BAD!ID__GAME__VOTEKICK", "bad world id");
        assertEquals(13, problems.size(), problems.toString());
    }

    @Test
    void loopbackOnlyKeysRefuseOtherAddresses() throws Exception{
        for(String address : List.of("0.0.0.0", "::", "10.0.0.1", "localhost")){
            Path shard = file("shard.yml", "ops:\n  bind: \"" + address + "\"\n");
            var problems = problems(shard, null, Map.of());
            assertEquals(1, problems.size(), address);
            assertTrue(problems.getFirst().location().endsWith("shard.yml:2:9"), problems.toString());
        }
        var ok = ConfigLoader.load(file("shard.yml", "ops:\n  bind: \"::1\"\n"), null, Map.of());
        assertEquals(InetAddress.ofLiteral("::1"), ok.get(OPS_BIND));
        assertTrue(ConfigLoader.load(null, null, Map.of("SHARD__OPS__BIND", "127.0.0.2")).get(OPS_BIND).isLoopbackAddress());
    }

    @Test
    void worldFilesTakeWorldKeysOnly() throws Exception{
        Path worlds = dir.resolve("worlds");
        file("worlds/alpha.yml", "net:\n  port: 7000\n");
        file("worlds/Bad_Name.yml", "game:\n  votekick: false\n");
        file("worlds/beta.yaml", "game:\n  votekick: false\n");
        file("worlds/notes.txt", "ignored");
        var problems = problems(null, worlds, Map.of());
        assertProblem(problems, worlds.resolve("alpha.yml") + ":2:3", "net.port is host-wide; set it in shard.yml, not per world");
        assertProblem(problems, worlds.resolve("Bad_Name.yml").toString(), "a world file is named <world-id>.yml");
        assertProblem(problems, worlds.resolve("beta.yaml").toString(), "world files end in .yml");
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void malformedYamlPointsAtTheProblem() throws Exception{
        Path shard = file("shard.yml", "net:\n  port: 1\n bad indent: [\n");
        var problems = problems(shard, null, Map.of());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().location().startsWith(shard + ":"), problems.toString());
        assertTrue(problems.getFirst().message().startsWith("not valid YAML"), problems.toString());

        var root = problems(file("shard.yml", "- a\n- b\n"), null, Map.of());
        assertProblem(root, shard + ":1:1", "expected a mapping of config sections");
    }

    @Test
    void aMissingFileIsAnError(){
        var problems = problems(dir.resolve("absent.yml"), null, Map.of());
        assertProblem(problems, dir.resolve("absent.yml").toString(), "no such file");
    }

    @Test
    void explainRedactsNothingButShowsSources() throws Exception{
        var config = ConfigLoader.load(null, null, Map.of("SHARD__NET__PORT", "7000"));
        assertTrue(config.explain().contains("net.port = 7000  (env SHARD__NET__PORT)"), config.explain().toString());
        assertTrue(config.explain().contains("server.name = \"Server\"  (default)"), config.explain().toString());
    }

    @Test
    void envNamesFollowThePath(){
        assertEquals("SHARD__PERSISTENCE__AUTOSAVE_INTERVAL", PERSISTENCE_AUTOSAVE_INTERVAL.env());
        assertEquals("SHARD__WORLDS__MY_WORLD__PERSISTENCE__AUTOSAVE_INTERVAL", PERSISTENCE_AUTOSAVE_INTERVAL.env("my-world"));
    }
}
