package com.xpdustry.shard.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class MainTest{
    record Run(int status, String out, String err){
    }

    static Run run(String... args){
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpPrintsUsageAndSucceeds(){
        Run run = run("--help");
        assertEquals(0, run.status());
        assertTrue(run.out().startsWith("Usage: shard"), run.out());
        assertEquals("", run.err());
    }

    @Test
    void badArgumentsFailWithUsage(@TempDir Path temp){
        for(String[] args : new String[][]{{"--port", "6567"}, {"--help", "extra"}, {"--config"}, {"--config", temp.resolve("missing").toString()}}){
            Run run = run(args);
            assertEquals(2, run.status(), String.join(" ", args));
            assertEquals("", run.out());
            assertTrue(run.err().contains("Usage: shard"), run.err());
        }
    }

    @Test
    void anInvalidConfigFailsBeforeStarting(@TempDir Path temp) throws IOException{
        Files.writeString(temp.resolve("shard.yml"), "net:\n  port: 70000\n");
        Run run = run("--config", temp.toString(), "--no-console");
        assertEquals(2, run.status());
        assertTrue(run.err().contains("net.port: expected an integer in [0, 65535], got 70000"), run.err());
    }
}
