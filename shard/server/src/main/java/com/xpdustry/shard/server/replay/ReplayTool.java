package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.sim.persist.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * {@code shard replay list|verify|export|bake}: the recorded matches in a replay directory, on the build that runs
 * it ({@code shard replay <bundle.zip>} is the crash bundle reader, {@code ReplayCommand}). Exit status: 0 when it
 * did what was asked, 1 when a verification found a difference, 2 for bad arguments or unreadable bundles.
 */
public final class ReplayTool{
    public static final List<String> COMMANDS = List.of("list", "verify", "export", "bake");
    public static final String USAGE = """
        Usage: shard replay list [--dir <replays>]
               shard replay verify <match> [--segment <i>[-<j>]] [--dir <replays>]
               shard replay export <match> [--out <file.sview>] [--split <dir>] [--dir <replays>]
               shard replay bake [<match>...] [--dir <replays>]

          list      every recorded match: its state, length, size, and whether this build can replay it
          verify    make every segment of a match again from its bundle and compare it, and the world's hash at every
                    checkpoint, with what the live server recorded; --segment checks only those (numbered from 0)
          export    write a match's view stream (baking it first if needed) to a file or a split directory
          bake      make and publish the view streams of this build's matches (all unbaked ones by default), so they
                    stay watchable once this build is gone
          --dir     the replay directory (default: replay.directory of --config, or data/replays)
          --config  a server config directory to read replay.directory from
        """;

    /** Whether {@code args} (after {@code replay}) are for this tool rather than the crash bundle reader. */
    public static boolean handles(List<String> args){
        return !args.isEmpty() && COMMANDS.contains(args.getFirst());
    }

    public static int run(List<String> args, PrintStream out, PrintStream err){
        String command = args.getFirst();
        var names = new ArrayList<String>();
        Path dir = null, config = null, file = null, split = null;
        int first = 0, last = Integer.MAX_VALUE;
        try{
            for(int i = 1; i < args.size(); i++){
                String arg = args.get(i);
                switch(arg){
                    case "--dir" -> dir = Path.of(value(args, ++i, arg));
                    case "--config" -> config = Path.of(value(args, ++i, arg));
                    case "--out" -> file = Path.of(value(args, ++i, arg));
                    case "--split" -> split = Path.of(value(args, ++i, arg));
                    case "--segment" -> {
                        String[] range = value(args, ++i, arg).split("-", 2);
                        try{
                            first = Integer.parseInt(range[0]);
                            last = range.length > 1 ? Integer.parseInt(range[1]) : first;
                        }catch(NumberFormatException e){
                            throw new IllegalArgumentException("--segment takes <i> or <i>-<j>");
                        }
                        if(first < 0 || last < first) throw new IllegalArgumentException("--segment takes <i> or <i>-<j>, from 0");
                    }
                    case "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    default -> {
                        if(arg.startsWith("--")) throw new IllegalArgumentException("unexpected " + arg);
                        names.add(arg);
                    }
                }
            }
            if(dir == null){
                String configured = config != null ? ConfigLoader.loadDirectory(config, Map.of()).get(ConfigKeys.REPLAY_DIRECTORY)
                    : ConfigKeys.REPLAY_DIRECTORY.defaultValue();
                dir = config != null ? config.resolve(configured) : Path.of(configured);
            }
            if((command.equals("verify") || command.equals("export")) && names.size() != 1) throw new IllegalArgumentException(command + " takes one match");
            if(command.equals("list") && !names.isEmpty()) throw new IllegalArgumentException("list takes no match");
        }catch(IllegalArgumentException | ConfigException e){
            err.println("shard replay: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
        var store = new ReplayStore(dir);
        try{
            return switch(command){
                case "list" -> list(store, out);
                case "verify" -> verify(store, names.getFirst(), first, last, out);
                case "export" -> export(store, names.getFirst(), file, split, out);
                default -> bake(store, names, out);
            };
        }catch(IOException | CheckpointException | IllegalArgumentException e){
            err.println("shard replay: " + e.getMessage());
            return 2;
        }
    }

    private static int list(ReplayStore store, PrintStream out) throws IOException{
        var listed = new TreeSet<String>();
        out.printf("%-34s %-9s %-22s %-10s %9s %5s %5s %10s  %s%n", "match", "state", "map", "mode", "length", "cps", "segs", "bytes", "view");
        for(var build : store.all().entrySet()){
            boolean ours = build.getKey().equals(BuildInfo.id());
            for(Bundle bundle : build.getValue()){
                if(!bundle.exists()) continue;
                Bundle.Manifest manifest = bundle.manifest();
                listed.add(bundle.match());
                String view = Files.isRegularFile(store.view(bundle.match())) ? "baked" : Files.isDirectory(store.views().resolve(bundle.match())) ? "live" : "-";
                out.printf("%-34s %-9s %-22s %-10s %9s %5d %5d %,10d  %s%s%n", bundle.match(), manifest.state(), clip(manifest.mapName(), 22), manifest.mode(),
                    ReplayStore.duration(manifest.end() - manifest.start()), bundle.checkpoints().size(), bundle.segmentFiles().size(), bundle.bytes(), view,
                    ours ? "" : "  (build " + build.getKey() + ": this build can't replay it)");
            }
        }
        if(Files.isDirectory(store.views())){
            try(var views = Files.list(store.views())){
                for(Path view : views.sorted().toList()){
                    String name = view.getFileName().toString();
                    if(!name.endsWith(".sview") || listed.contains(name.substring(0, name.length() - 6))) continue;
                    out.printf("%-34s %-9s %-22s %-10s %9s %5s %5s %,10d  baked (no bundle)%n", name.substring(0, name.length() - 6), "-", "-", "-", "-", "-", "-",
                        Files.size(view));
                }
            }
        }
        return 0;
    }

    private static int verify(ReplayStore store, String match, int first, int last, PrintStream out) throws IOException{
        Bundle bundle = find(store, match);
        Bundle.Manifest manifest = bundle.manifest();
        out.println(match + ": " + manifest.mapName() + " (" + manifest.mode() + "), ticks " + manifest.start() + "-" + manifest.end() + ", "
            + bundle.checkpoints().size() + " checkpoints, " + bundle.logs().size() + " log files");
        Regenerator.Report report = new Regenerator(bundle).verify(out::println, first, last);
        long identical = report.segments().stream().filter(Regenerator.Made::identical).count();
        double total = report.segments().stream().mapToDouble(Regenerator.Made::millis).sum();
        out.printf("%d of %d segments identical to the live recording; %d live world hashes compared at checkpoints; %.0f ms on average%n", identical,
            report.segments().size(), report.hashesChecked(), total / Math.max(1, report.segments().size()));
        for(String problem : report.problems()) out.println("problem: " + problem);
        out.println(report.ok() ? "verified" : "NOT verified");
        return report.ok() ? 0 : 1;
    }

    private static int export(ReplayStore store, String match, Path file, Path split, PrintStream out) throws IOException{
        Path baked = store.view(match);
        ViewFile view;
        Bundle bundle = store.bundle(match);
        if(Files.isRegularFile(baked) && (!bundle.exists() || bundle.manifest().baked())){
            view = ViewFile.decode(Files.readAllBytes(baked));
        }else{
            store.publish(find(store, match), out::println);
            view = ViewFile.decode(Files.readAllBytes(baked));
        }
        if(file == null && split == null) file = Path.of(match + ".sview");
        if(file != null){
            Bundle.write(file.toAbsolutePath(), view.encode());
            out.println("wrote " + file + " (" + Files.size(file) + " B, " + view.segments().size() + " segments)");
        }
        if(split != null){
            view.split(split);
            out.println("wrote " + split + "/header.json, terrain and " + view.segments().size() + " segments");
        }
        return 0;
    }

    private static int bake(ReplayStore store, List<String> names, PrintStream out) throws IOException{
        var bundles = new ArrayList<Bundle>();
        if(names.isEmpty()){
            for(Bundle bundle : store.all().getOrDefault(BuildInfo.id(), List.of())){
                if(bundle.exists() && !bundle.manifest().baked()) bundles.add(bundle);
            }
        }else{
            for(String name : names) bundles.add(find(store, name));
        }
        if(bundles.isEmpty()) out.println("nothing to bake: every match this build recorded is baked");
        for(Bundle bundle : bundles){
            long bytes = store.publish(bundle, line -> out.println(bundle.match() + ": " + line));
            out.println(bundle.match() + ": baked views/" + bundle.match() + ".sview (" + bytes + " B)" + (bundle.manifest().over() ? "" : ", the match isn't over"));
        }
        return 0;
    }

    private static Bundle find(ReplayStore store, String match) throws IOException{
        Bundle bundle = store.bundle(match);
        if(bundle.exists()) return bundle;
        for(var build : store.all().entrySet()){
            for(Bundle other : build.getValue()){
                if(other.match().equals(match)) throw new IOException(match + " was recorded by build " + build.getKey() + "; this is " + BuildInfo.id());
            }
        }
        throw new IOException("no match " + match + " in " + store.root());
    }

    private static String clip(String text, int length){
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private static String value(List<String> args, int index, String flag){
        if(index >= args.size()) throw new IllegalArgumentException(flag + " needs a value");
        return args.get(index);
    }

    private ReplayTool(){}
}
