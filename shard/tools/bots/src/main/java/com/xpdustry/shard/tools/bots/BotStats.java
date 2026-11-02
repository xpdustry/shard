package com.xpdustry.shard.tools.bots;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Counters shared by every bot of a run, safe from any thread. */
final class BotStats{
    final LongAdder connected = new LongAdder(), joined = new LongAdder(), failed = new LongAdder(), kicked = new LongAdder(),
        dropped = new LongAdder();
    final LongAdder rxUdpBytes = new LongAdder(), rxTcpBytes = new LongAdder(), txBytes = new LongAdder(), worldBytes = new LongAdder(),
        entities = new LongAdder();
    final LongAdder transfersAsked = new LongAdder(), worldDataBegins = new LongAdder(), transfersDone = new LongAdder();
    /** Leaves on purpose ({@code --churn-s}), and entity snapshots a walking bot couldn't read. */
    final LongAdder left = new LongAdder(), unreadSnapshots = new LongAdder();
    final Hist rtt = new Hist(), joinTime = new Hist(), snapshotGap = new Hist();
    private final LongAdder[] count = adders(), wire = adders(), raw = adders();
    private final ConcurrentHashMap<String, LongAdder> reasons = new ConcurrentHashMap<>();

    private static LongAdder[] adders(){
        var out = new LongAdder[256];
        for(int i = 0; i < out.length; i++) out[i] = new LongAdder();
        return out;
    }

    void received(int id, int wireBytes, int rawBytes){
        count[id].increment();
        wire[id].add(wireBytes);
        raw[id].add(rawBytes);
    }

    void reason(String reason){
        reasons.computeIfAbsent(reason.length() > 100 ? reason.substring(0, 100) : reason, key -> new LongAdder()).increment();
    }

    /** Per packet id: count, wire and raw bytes, and the wire rate per joined bot. */
    List<String> packets(double seconds){
        var out = new ArrayList<String>();
        long bots = Math.max(1, joined.sum());
        for(int id = 0; id < 256; id++){
            long n = count[id].sum();
            if(n == 0) continue;
            out.add(String.format(Locale.ROOT, "id=%d count=%d wire=%d raw=%d ratio=%.2f perBotPerSec=%.0fB", id, n, wire[id].sum(), raw[id].sum(),
                (double)wire[id].sum() / Math.max(1, raw[id].sum()), wire[id].sum() / seconds / bots));
        }
        return out;
    }

    /** Close and kick reasons, most frequent first. */
    List<String> reasons(){
        var out = new ArrayList<Map.Entry<String, LongAdder>>(reasons.entrySet());
        out.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        return out.stream().map(e -> e.getValue().sum() + "x " + e.getKey()).toList();
    }
}
