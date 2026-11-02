package com.xpdustry.shard.tools.wirelift;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The entry point of {@code :tools:wirelift}'s tasks.
 * <pre>
 * lift   &lt;jar&gt; &lt;schema.yaml&gt; [reports dir]   lift the jar into the schema (and the layout reports)
 * relift &lt;jar&gt; &lt;schema.yaml&gt;                 lift again, fail with a diff if the committed schema differs
 * gen    &lt;schema.yaml&gt; &lt;source dir&gt; &lt;package&gt;  generate the codecs into the source dir (replacing the package)
 * regen  &lt;schema.yaml&gt; &lt;source dir&gt; &lt;package&gt;  generate again, fail with a diff if the committed sources differ
 * </pre>
 */
public final class Main{
    public static void main(String[] args) throws IOException{
        if(args.length < 3) usage();
        int status = switch(args[0]){
            case "lift" -> lift(Path.of(args[1]), Path.of(args[2]), args.length > 3 ? Path.of(args[3]) : null);
            case "relift" -> relift(Path.of(args[1]), Path.of(args[2]));
            case "gen" -> gen(args, false);
            case "regen" -> gen(args, true);
            default -> usage();
        };
        System.exit(status);
    }

    private static int usage(){
        System.err.println("usage: lift <jar> <schema> [reports] | relift <jar> <schema> | gen|regen <schema> <source dir> <package>");
        System.exit(2);
        return 2;
    }

    private static int lift(Path jar, Path schema, Path reports) throws IOException{
        WireLift lift = WireLift.open(jar);
        Schema lifted = SchemaEmit.emit(lift);
        Files.createDirectories(schema.toAbsolutePath().getParent());
        Files.writeString(schema, lifted.yaml());
        if(reports != null) Reports.write(lift, reports);
        System.out.println("lifted " + lift.classes.size() + " classes of build " + lift.build + ": " + lifted.layouts().size() + " layouts, "
            + lifted.entities().size() + " entity classes, " + lifted.packets().size() + " packets, " + lifted.problems().size() + " problems -> " + schema);
        return lifted.problems().isEmpty() ? 0 : 1;
    }

    private static int relift(Path jar, Path schema) throws IOException{
        String fresh = SchemaEmit.emit(WireLift.open(jar)).yaml();
        String committed = Files.exists(schema) ? Files.readString(schema) : "";
        if(fresh.equals(committed)){
            System.out.println(schema + " matches a fresh lift of " + jar);
            return 0;
        }
        System.err.println(schema + " differs from a fresh lift of " + jar + "; run ./gradlew :tools:wirelift:lift and review the diff.");
        System.err.print(Diff.lines(committed, fresh));
        return 1;
    }

    private static int gen(String[] args, boolean check) throws IOException{
        if(args.length < 4) usage();
        Path schema = Path.of(args[1]), root = Path.of(args[2]);
        String pkg = args[3];
        Map<String, String> files = WireGen.generate(Schema.parse(Files.readString(schema)), pkg);
        Path dir = root.resolve(pkg.replace('.', '/'));
        if(!check){
            if(Files.isDirectory(dir)){
                try(var old = Files.list(dir)){
                    for(Path p : old.toList()) Files.delete(p);
                }
            }
            Files.createDirectories(dir);
            for(var file : files.entrySet()) Files.writeString(dir.resolve(file.getKey()), file.getValue());
            System.out.println("generated " + files.size() + " files into " + dir);
            return 0;
        }
        List<String> stale = new ArrayList<>();
        Set<String> present = new TreeSet<>();
        if(Files.isDirectory(dir)){
            try(var list = Files.list(dir)){
                list.forEach(p -> present.add(p.getFileName().toString()));
            }
        }
        StringBuilder diff = new StringBuilder();
        for(var file : files.entrySet()){
            Path p = dir.resolve(file.getKey());
            String committed = Files.exists(p) ? Files.readString(p) : "";
            if(!committed.equals(file.getValue())){
                stale.add(file.getKey());
                diff.append("--- ").append(file.getKey()).append('\n').append(Diff.lines(committed, file.getValue()));
            }
        }
        present.removeAll(files.keySet());
        stale.addAll(present.stream().map(name -> name + " (not generated)").toList());
        if(stale.isEmpty()){
            System.out.println(dir + " matches what WireGen generates from " + schema);
            return 0;
        }
        System.err.println(dir + " is stale: " + stale + "; run ./gradlew :tools:wirelift:generateWire.");
        System.err.print(diff);
        return 1;
    }

    private Main(){}
}
