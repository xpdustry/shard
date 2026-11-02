package com.xpdustry.shard.server.config;

import com.xpdustry.shard.server.config.ConfigKey.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.xpdustry.shard.server.config.ConfigKeys.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest{
    @TempDir
    Path dir;

    ConfigService service() throws Exception{
        return new ConfigService(() -> ConfigLoader.loadDirectory(dir, Map.of()));
    }

    void write(String name, String text) throws Exception{
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    @Test
    void liveChangesApplyAndRestartChangesWait() throws Exception{
        write("shard.yml", "server:\n  motd: one\nnet:\n  port: 6567\n");
        var service = service();
        var heard = new ArrayList<ConfigService.Reloaded>();
        service.subscribe(heard::add);
        assertEquals(1, service.generation());

        write("shard.yml", "server:\n  motd: two\nnet:\n  port: 7000\nlog:\n  level: debug\n");
        var reloaded = service.reload();

        assertEquals(2, reloaded.generation());
        assertEquals("two", service.current().get(SERVER_MOTD), "live: applied");
        assertEquals(LogLevel.DEBUG, service.current().get(LOG_LEVEL), "live: applied");
        assertEquals(6567, service.current().get(NET_PORT), "restart: the running value stays");
        assertEquals(List.of(SERVER_MOTD, LOG_LEVEL), reloaded.applied().stream().map(ConfigService.Change::key).toList());
        assertEquals(1, reloaded.pending().size());
        var pending = reloaded.pending().getFirst();
        assertEquals(NET_PORT, pending.key());
        assertEquals(6567, pending.before());
        assertEquals(7000, pending.after());
        assertEquals("net.port 6567 -> 7000", pending.toString());
        assertEquals(List.of(reloaded), heard);

        // reverting the file clears the pending change; nothing else changed, so no new generation or event
        write("shard.yml", "server:\n  motd: two\nnet:\n  port: 6567\nlog:\n  level: debug\n");
        var reverted = service.reload();
        assertEquals(2, reverted.generation());
        assertTrue(reverted.applied().isEmpty());
        assertTrue(reverted.pending().isEmpty());
        assertTrue(service.pending().isEmpty());
        assertEquals(1, heard.size());
    }

    @Test
    void aBadCandidateChangesNothing() throws Exception{
        write("shard.yml", "server:\n  motd: one\n");
        var service = service();
        var before = service.current();
        write("shard.yml", "server:\n  motd: two\n  nmae: x\n");
        var error = assertThrows(ConfigException.class, service::reload);
        assertEquals(1, error.problems().size());
        assertTrue(error.problems().getFirst().location().endsWith("shard.yml:3:3"), error.getMessage());
        assertSame(before, service.current());
        assertEquals(1, service.generation());
    }

    @Test
    void worldOverridesReloadPerWorld() throws Exception{
        write("worlds/alpha.yml", "server:\n  motd: alpha one\n");
        var service = service();
        assertEquals("alpha one", service.current().world("alpha").get(SERVER_MOTD));

        write("worlds/alpha.yml", "server:\n  motd: alpha two\npersistence:\n  autosave-interval: 30s\n");
        write("worlds/beta.yml", "game:\n  votekick: false\n");
        var reloaded = service.reload();
        var alpha = service.current().world("alpha");
        assertEquals("alpha two", alpha.get(SERVER_MOTD));
        assertEquals(Duration.ofSeconds(30), alpha.get(PERSISTENCE_AUTOSAVE_INTERVAL));
        assertFalse(service.current().world("beta").get(GAME_VOTEKICK));
        assertEquals("", service.current().get(SERVER_MOTD), "the host value is untouched");
        assertEquals(List.of("worlds.alpha: server.motd \"alpha one\" -> \"alpha two\"",
                "worlds.alpha: persistence.autosave-interval 1m -> 30s", "worlds.beta: game.votekick true -> false"),
            reloaded.applied().stream().map(Object::toString).toList());

        Files.delete(dir.resolve("worlds/alpha.yml"));
        service.reload();
        assertEquals("", service.current().world("alpha").get(SERVER_MOTD), "a removed override falls back to the host");
    }

    @Test
    void theReloadPolicyCoversEveryKey(){
        // keys that open sockets, files or threads at startup can't change live, nor the universe's age the worlds' sync
        // settings take then, nor whether the startup worlds resume and where and how long replays are kept and offered
        var restart = new TreeSet<String>();
        for(var key : ConfigKeys.ALL) if(key.reload() == Reload.RESTART) restart.add(key.path());
        assertEquals(new TreeSet<>(List.of("net.bind", "net.port", "storage.database", "storage.synchronous", "storage.writer-queue",
            "storage.readers", "storage.busy-timeout", "ops.enabled", "ops.bind", "ops.port", "log.format", "log.file",
            "log.max-file-size", "console.start-commands", "host.worlds", "maps.vanilla-jar", "maps.directory", "persistence.directory", "persistence.saves-directory",
            "game.universe-seconds", "persistence.resume", "replay.directory", "replay.keep-bundles", "replay.keep-views",
            "replay.in-game")), restart);
    }
}
