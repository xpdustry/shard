package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding F3 (W5.5; owner {@code sim.blocks.turret}): reading a turret's block unit through a client's {@code Unit}
 * reference creates it ({@code TurretBuild.blockUnit}, made lazily; vanilla makes it at construction,
 * {@code Turret.java:297}), and the turret's hash includes it when present. So a packet the server refuses moves the
 * world hash: two forks of fortress, one of which read the block unit as a refused {@code unitControl} or
 * {@code requestUnitPayload} at a duo does ({@code Acting.unit} -> {@code BlockUnits.at}), hash apart at the next
 * hash tick and stay apart. The turrets behave the same: {@code updateTile} reads the unit only while a player holds it.
 *
 * <p>Replay doesn't catch it: refused inputs are logged with their verdicts and replayed, so the replay makes the unit
 * too and the hashes match. Fails until the sim owner makes creation or the hash neutral, so {@code check} skips it:
 * {@code scripts/vm-gradle.sh :tools:fuzz:test -Pfindings=true}.
 */
@EnabledIfSystemProperty(named = "com.xpdustry.shard.fuzz.findings", matches = "true")
class BlockUnitHashFindingTest{
    @TempDir
    Path temp;

    @Test
    void readingABlockUnitByReferenceLeavesTheHashAlone() throws Exception{
        try(Server server = Campaign.startServer(temp.resolve("server"), List.of("fortress"), temp.resolve("server.log"), temp.resolve("replays"))){
            HostedWorld fortress = server.host().world("fortress").orElseThrow();
            String result = fortress.inspect(live -> {
                World read = live.fork(), untouched = live.fork();
                int pos = -1;
                for(int i = 0; i < read.builds().size() && pos < 0; i++){
                    if(read.builds().get(i) instanceof TurretBuild turret) pos = Point2.pack(turret.tileX(), turret.tileY());
                }
                assertTrue(pos >= 0, "fortress has a turret");
                BlockUnits.at(read, pos);
                // to the next hash tick (a hosted world hashes every 300), then one more
                long next = (read.ticks() / 300 + 1) * 300;
                var out = new StringBuilder();
                for(long stop : new long[]{next, next + 300}){
                    while(read.ticks() < stop){
                        read.tick();
                        untouched.tick();
                    }
                    WorldHash a = read.hash(), b = untouched.hash();
                    if(a.value() != b.value()) out.append("tick ").append(stop).append(": hashes differ, first in ").append(a.firstDifference(b).orElse("the total")).append('\n');
                }
                return out.toString();
            }).get(Campaign.Hang.SECONDS, TimeUnit.SECONDS);
            assertEquals("", result);
        }
    }
}
