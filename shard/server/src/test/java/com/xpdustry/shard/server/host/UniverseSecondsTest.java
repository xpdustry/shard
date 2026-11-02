package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code game.universe-seconds} is the universe's age the state snapshots send (vanilla's {@code utimei}): the server
 * hands it to its worlds' sync settings at startup (WorldSyncTest has it on the wire).
 */
class UniverseSecondsTest{
    @TempDir
    Path temp;

    @Test
    void theConfiguredAgeReachesTheSyncSettings() throws Exception{
        try(var server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            """), Map.of(ConfigKeys.GAME_UNIVERSE_SECONDS.env(), "86400"), false))){
            assertEquals(86_400, server.host().sync().universeSeconds());
        }
    }
}
