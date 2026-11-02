package com.xpdustry.shard.tools.bots;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The load generator: starts N {@link Bot}s against a server, ramps them in, prints a report line every
 * {@code --report} seconds and a summary at the end, with what each packet id cost per bot.
 *
 * <pre>shard-bots --host 127.0.0.1 --port 6567 --bots 1000 --duration 120 --chat-s 30</pre>
 */
public final class LoadGen{
    static final String USAGE = """
        Usage: shard-bots [options]
          --host <ip>          server address (default 127.0.0.1)
          --port <n>           server port, TCP and UDP (default 6567)
          --bots <n>           bots to start (default 10)
          --ramp-ms <ms>       delay between bot starts (default 20)
          --duration <s>       run time after the ramp starts (default 60)
          --report <s>         seconds between report lines (default 10)
          --snapshot-ms <ms>   clientSnapshot period (default 66, as the desktop client)
          --chat-s <s>         chat once every this many seconds, 0 = never (default 0)
          --transfer-s <s>     ask for /world <random> about this often, 0 = never (default 0)
          --worlds <n>         how many worlds /world picks from (default 1)
          --churn-s <s>        each bot leaves after about this many seconds and joins again, 0 = never (default 0)
          --walk               drive the bot's unit: walk, shoot, and build and break conveyors near its spawn
          --name <prefix>      bot names are <prefix><index> (default bot)
          --offset <n>         first bot index, to run several generators (default 0)
          --expect-all         exit 1 unless every bot joined and none was dropped or kicked
          --trace              print every packet
        """;

    public static void main(String[] argv) throws Exception{
        var args = new TreeMap<String, String>();
        for(int i = 0; i < argv.length; i++){
            if(!argv[i].startsWith("--")) fail("unexpected argument " + argv[i]);
            String key = argv[i].substring(2);
            if(key.equals("help")){
                System.out.print(USAGE);
                return;
            }
            args.put(key, i + 1 < argv.length && !argv[i + 1].startsWith("--") ? argv[++i] : "true");
        }
        var server = new InetSocketAddress(args.getOrDefault("host", "127.0.0.1"), number(args, "port", 6567));
        int bots = number(args, "bots", 10), ramp = number(args, "ramp-ms", 20), duration = number(args, "duration", 60);
        int report = number(args, "report", 10), offset = number(args, "offset", 0);
        var plan = new Bot.Plan(server, args.getOrDefault("name", "bot"), number(args, "snapshot-ms", 66), number(args, "chat-s", 0),
            number(args, "transfer-s", 0), number(args, "worlds", 1), args.containsKey("trace"), number(args, "churn-s", 0), args.containsKey("walk"));
        var stats = new BotStats();
        var all = new CopyOnWriteArrayList<Bot>();
        long start = System.nanoTime();
        Thread.ofVirtual().name("ramp").start(() -> {
            for(int i = 0; i < bots; i++){
                var bot = new Bot(offset + i, plan, stats);
                all.add(bot);
                Thread.ofVirtual().name("bot-" + (offset + i)).start(bot::run);
                try{
                    if(ramp > 0) Thread.sleep(ramp);
                }catch(InterruptedException e){
                    return;
                }
            }
        });

        long last = start, lastUdp = 0, lastTcp = 0, lastTx = 0, lastEntities = 0;
        while(System.nanoTime() - start < duration * 1_000_000_000L){
            Thread.sleep(Math.min(report * 1000L, Math.max(1, (start + duration * 1_000_000_000L - System.nanoTime()) / 1_000_000)));
            long now = System.nanoTime(), udp = stats.rxUdpBytes.sum(), tcp = stats.rxTcpBytes.sum(), tx = stats.txBytes.sum(),
                entities = stats.entities.sum();
            double wall = (now - last) / 1e9;
            long joined = Math.max(1, stats.joined.sum());
            System.out.printf(Locale.ROOT, "bots t=%.0fs connected=%d joined=%d failed=%d dropped=%d kicked=%d transfers=%d/%d rx/bot: udp=%.0f B/s "
                    + "tcp=%.0f B/s tx/bot=%.0f B/s entities/bot/s=%.0f rtt[%s] gap>20ms[%s]%n",
                (now - start) / 1e9, stats.connected.sum(), stats.joined.sum(), stats.failed.sum(), stats.dropped.sum(), stats.kicked.sum(),
                stats.transfersDone.sum(), stats.transfersAsked.sum(), (udp - lastUdp) / wall / joined, (tcp - lastTcp) / wall / joined,
                (tx - lastTx) / wall / joined, (entities - lastEntities) / wall / joined, stats.rtt.drain().summary(), stats.snapshotGap.drain().summary());
            last = now;
            lastUdp = udp;
            lastTcp = tcp;
            lastTx = tx;
            lastEntities = entities;
        }
        for(Bot bot : all) bot.stop();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "BOT SUMMARY bots=%d joined=%d failed=%d dropped=%d kicked=%d transfers=%d/%d join[%s] worldKB/bot=%.1f%n", bots,
            stats.joined.sum(), stats.failed.sum(), stats.dropped.sum(), stats.kicked.sum(), stats.transfersDone.sum(), stats.transfersAsked.sum(),
            stats.joinTime.summary(), stats.worldBytes.sum() / 1024.0 / Math.max(1, stats.joined.sum() + stats.transfersDone.sum()));
        if(stats.left.sum() > 0 || stats.unreadSnapshots.sum() > 0){
            System.out.printf(Locale.ROOT, "BOT CHURN left=%d rejoined=%d unreadSnapshots=%d%n", stats.left.sum(), stats.joined.sum() - bots,
                stats.unreadSnapshots.sum());
        }
        System.out.println("per packet id (all bots, whole run):");
        for(String line : stats.packets(seconds)) System.out.println("  " + line);
        for(String line : stats.reasons()) System.out.println("  close: " + line);
        boolean ok = stats.joined.sum() == bots && stats.dropped.sum() == 0 && stats.kicked.sum() == 0;
        System.exit(args.containsKey("expect-all") && !ok ? 1 : 0);
    }

    private static int number(Map<String, String> args, String key, int fallback){
        String value = args.get(key);
        if(value == null) return fallback;
        try{
            return Integer.parseInt(value);
        }catch(NumberFormatException e){
            fail("--" + key + " needs a number, not " + value);
            return fallback;
        }
    }

    private static void fail(String message){
        System.err.println("shard-bots: " + message);
        System.err.print(USAGE);
        System.exit(2);
    }

    private LoadGen(){
    }
}
