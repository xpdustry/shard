package com.xpdustry.shard.server.console;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;

import java.lang.management.*;
import java.util.*;

/**
 * The console's {@code perf}: what the scheduler and the transport did since the last {@code perf}, as one line, and
 * a summary of everything since {@code perf mark}. The load gate reads them (as it read W1.6's dev server): process
 * CPU, tick-start lateness and tick time over every world, ticks dropped, traffic and the transport's refusals.
 * {@code perf profile} splits the window's tick CPU by phase, from every world's {@link TickStats}.
 */
final class Perf{
    private final Server server;
    private final com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    /** The JVM's start: {@code t=} is its uptime, the clock of {@code -Xlog}'s GC lines. */
    private final long start = System.nanoTime() - ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000;
    /** Each slot's dropped ticks at the last report: a world that hibernates takes its count with it. */
    private final Map<WorldScheduler.Slot, Long> droppedSeen = new WeakHashMap<>();
    private long last = System.nanoTime(), lastCpu = os.getProcessCpuTime(), lastUdp, lastTcp, lastBytes;
    // since the mark
    private boolean marked;
    private Histogram windowLate = new Histogram(), windowTick = new Histogram(), windowBoundary = new Histogram();
    private long windowStart, windowCpu, windowDropped;
    private double playerSeconds;
    /** Each slot's ticks and CPU per {@link TickStats#profile()} bucket at the mark. */
    private final Map<WorldScheduler.Slot, Map<String, Long>> profileSeen = new WeakHashMap<>();

    Perf(Server server){
        this.server = server;
    }

    /** One interval's line; it also counts towards the summary once marked. */
    synchronized String report(){
        long now = System.nanoTime(), cpu = os.getProcessCpuTime();
        var late = new Histogram();
        var tick = new Histogram();
        var boundary = new Histogram();
        long dropped = 0;
        for(WorldScheduler.Slot slot : server.scheduler().slots()){
            late.merge(slot.stats().lateness().drain());
            tick.merge(slot.stats().tickTime().drain());
            boundary.merge(slot.stats().boundaryTime().drain());
            long total = slot.stats().dropped();
            dropped += total - droppedSeen.getOrDefault(slot, 0L);
            droppedSeen.put(slot, total);
        }
        long coalesced = 0, mailboxDropped = 0;
        for(Session session : server.sessions().all()){
            coalesced += session.coalesced();
            mailboxDropped += session.fullDropped();
        }
        NetStats net = server.transport().stats();
        long udp = net.udpOut.sum(), tcp = net.tcpOut.sum(), bytes = net.udpBytesOut.sum() + net.tcpBytesOut.sum();
        double wall = (now - last) / 1e9;
        int players = server.host().players();
        String line = String.format(Locale.ROOT,
            "perf t=%.0fs worlds=%d players=%d sessions=%d conns=%d cpu=%.1f%% late[%s] tick[%s] boundary[%s] dropped=%d udpOut/s=%.0f tcpOut/s=%.0f "
                + "outKB/s=%.0f slow=%d spam=%d deadlines=%d rateDropped=%d malformed=%d coalesced=%d mailboxDropped=%d heap=%dMB",
            (now - start) / 1e9, server.host().worlds().size(), players, server.sessions().size(), server.transport().connections(),
            (cpu - lastCpu) / 1e7 / wall, late.summary(), tick.summary(), boundary.summary(), dropped, (udp - lastUdp) / wall, (tcp - lastTcp) / wall,
            (bytes - lastBytes) / 1024.0 / wall, net.slowClients.sum(), net.rateClosed.sum(), net.deadlines.sum(), net.rateDropped.sum(),
            server.flow().malformed.sum(), coalesced, mailboxDropped, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20);
        if(marked){
            windowLate.merge(late);
            windowTick.merge(tick);
            windowBoundary.merge(boundary);
            windowCpu += cpu - lastCpu;
            windowDropped += dropped;
            playerSeconds += players * wall;
        }
        last = now;
        lastCpu = cpu;
        lastUdp = udp;
        lastTcp = tcp;
        lastBytes = bytes;
        return line;
    }

    /** Starts the summary's window now: what came before (a warm-up) is left out. */
    synchronized String mark(){
        report();
        marked = true;
        windowLate = new Histogram();
        windowTick = new Histogram();
        windowBoundary = new Histogram();
        windowStart = last;
        windowCpu = windowDropped = 0;
        playerSeconds = 0;
        profileSeen.clear();
        for(WorldScheduler.Slot slot : server.scheduler().slots()) profileSeen.put(slot, profile(slot.stats()));
        return String.format(Locale.ROOT, "perf mark at t=%.0fs", (last - start) / 1e9);
    }

    private static Map<String, Long> profile(TickStats stats){
        var out = new HashMap<>(stats.profile());
        out.put("ticks", stats.ticks());
        return out;
    }

    /**
     * The tick threads' CPU since the mark by phase ({@code World.tick}'s phases and the boundary work), summed over
     * every world, in microseconds per world tick, largest first. Work a world's host does around its tick (the sync,
     * the mailboxes) is in no phase.
     */
    synchronized String profile(){
        if(!marked) throw new IllegalStateException("no perf mark yet");
        var cpu = new HashMap<String, Long>();
        long ticks = 0;
        for(WorldScheduler.Slot slot : server.scheduler().slots()){
            Map<String, Long> now = profile(slot.stats()), then = profileSeen.getOrDefault(slot, Map.of());
            for(var entry : now.entrySet()){
                long delta = entry.getValue() - then.getOrDefault(entry.getKey(), 0L);
                if(entry.getKey().equals("ticks")) ticks += delta;
                else cpu.merge(entry.getKey(), delta, Long::sum);
            }
        }
        long total = cpu.values().stream().mapToLong(Long::longValue).sum();
        var line = new StringBuilder(String.format(Locale.ROOT, "PROFILE worldTicks=%d phases=%.2fus/tick", ticks, ticks == 0 ? 0 : total / 1e3 / ticks));
        long perTick = Math.max(1, ticks);
        cpu.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> line.append(String.format(Locale.ROOT, " %s=%.2f", e.getKey(), e.getValue() / 1e3 / perTick)));
        return line.toString();
    }

    /** Everything since the mark, as W1.6's {@code SUMMARY} line. */
    synchronized String summary(){
        if(!marked) throw new IllegalStateException("no perf mark yet");
        report();
        double wall = (last - windowStart) / 1e9, cpu = windowCpu / 1e7 / wall, players = playerSeconds / wall;
        NetStats net = server.transport().stats();
        return String.format(Locale.ROOT,
            "SUMMARY seconds=%.0f worlds=%d players=%.0f cpu=%.1f%% cpuPer100p=%.2f%% late[%s] tick[%s] boundary[%s] dropped=%d slow=%d spam=%d "
                + "deadlines=%d",
            wall, server.host().worlds().size(), players, cpu, players > 0 ? cpu * 100 / players : 0, windowLate.summary(), windowTick.summary(),
            windowBoundary.summary(), windowDropped, net.slowClients.sum(), net.rateClosed.sum(), net.deadlines.sum());
    }
}
