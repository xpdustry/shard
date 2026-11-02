package com.xpdustry.shard.net;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Token buckets on a fake clock. The transport-level behaviour is in {@link TransportTest}. */
class RateLimitsTest{
    static final long SECOND = 1_000_000_000L;

    @Test
    void aBucketAllowsItsBurstThenItsRate(){
        var bucket = new TokenBucket(300, 100, 0);
        int taken = 0;
        while(bucket.tryTake(0)) taken++;
        assertEquals(300, taken, "the burst");
        assertFalse(bucket.tryTake(SECOND / 200), "half a token later");
        assertTrue(bucket.tryTake(SECOND / 100), "one token later");
        assertFalse(bucket.tryTake(SECOND / 100));
        // three seconds refill 300 tokens, never more than the burst
        taken = 0;
        while(bucket.tryTake(10 * SECOND)) taken++;
        assertEquals(300, taken);
    }

    @Test
    void vanillasBudgetHoldsOverAnyThreeSeconds(){
        var bucket = RateLimits.VANILLA_SPAM.bucket(0);
        // a client sending 120 per second for 30 s stays under 300 per 3 s plus the burst, so it is cut off
        int taken = 0, refused = 0;
        for(long t = 0; t < 30 * SECOND; t += SECOND / 120){
            if(bucket.tryTake(t)) taken++;
            else refused++;
        }
        assertTrue(refused > 0);
        assertTrue(taken <= 300 + 100 * 30 + 1, "taken " + taken);
        // a client at 100 per second never is
        bucket = RateLimits.VANILLA_SPAM.bucket(0);
        for(long t = 0; t < 30 * SECOND; t += SECOND / 100) assertTrue(bucket.tryTake(t));
    }

    @Test
    void timeGoingBackwardsDoesNotRefill(){
        var bucket = new TokenBucket(1, 1, 5 * SECOND);
        assertTrue(bucket.tryTake(5 * SECOND));
        assertFalse(bucket.tryTake(SECOND));
        assertEquals(0, bucket.available(SECOND), 1e-9);
    }

    @Test
    void perIdLimitsAreSetByTheServer(){
        var limit = new RateLimits.Limit(5, 1, RateLimits.Action.DROP);
        RateLimits limits = RateLimits.vanilla().with(105, limit);
        assertSame(limit, limits.of(105));
        assertNull(limits.of(29));
        assertNull(RateLimits.vanilla().of(105), "with() copies");
        assertThrows(IllegalArgumentException.class, () -> new RateLimits.Limit(0, 1, RateLimits.Action.DROP));
    }
}
