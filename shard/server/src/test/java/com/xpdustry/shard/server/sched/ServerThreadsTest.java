package com.xpdustry.shard.server.sched;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ServerThreads.Sizes}. */
class ServerThreadsTest{
    @Test
    void aSmallMachineStillGetsTwoIoCpuThreads(){
        // with one, a slow encode holds every other world's joins and checkpoints (StaleEncodeTest on 4 cores)
        assertEquals(2, ServerThreads.Sizes.forCores(1).ioCpu());
        assertEquals(2, ServerThreads.Sizes.forCores(4).ioCpu());
        assertEquals(4, ServerThreads.Sizes.forCores(16).ioCpu());
        assertEquals(1, ServerThreads.Sizes.forCores(1).tick());
    }
}
