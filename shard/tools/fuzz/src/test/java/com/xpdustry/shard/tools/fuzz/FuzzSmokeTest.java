package com.xpdustry.shard.tools.fuzz;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fuzzer's short run in {@code check}: bounded by a message count, not by time. Two worlds, a control client in
 * each, eight fuzzing clients and {@value #MESSAGES} messages, then the replay audit; every invariant must hold.
 */
class FuzzSmokeTest{
    static final int MESSAGES = Integer.getInteger("com.xpdustry.shard.fuzz.smokeMessages", 20_000);

    @TempDir
    Path temp;

    @Test
    void aShortCampaignBreaksNoInvariant() throws Exception{

        var config = FuzzConfig.DEFAULT.withPackets(MESSAGES).withMaps(List.of("maze", "fortress")).withClients(8).withSeed(5).withOut(temp)
            // the full check runs many test JVMs at once: here only a world that
            // all but stops counts, and a campaign on its own VM keeps the strict bounds
            .withTickLeniency(0.1, 15_000);
        Campaign.Result result;
        try(var campaign = new Campaign(config)){
            result = campaign.run();
        }
        System.out.print(result.report());
        assertTrue(result.messages() >= MESSAGES, "the run stopped early at " + result.messages());
        assertTrue(result.report().contains("identical"), "the audit replayed nothing:\n" + result.report());
        assertTrue(result.ok(), result.report());
    }
}
