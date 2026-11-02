package com.xpdustry.shard.tools.wirelift;

import com.xpdustry.shard.tools.wirelift.WireLift.*;

import java.io.*;
import java.lang.classfile.*;
import java.nio.file.*;
import java.util.*;

import static com.xpdustry.shard.tools.wirelift.WireLift.*;

/**
 * Human-readable layout reports next to the schema, for porting the codecs the schema does not cover (building bodies,
 * modules, payloads). Not committed; {@code ./gradlew :tools:wirelift:lift} writes them to {@code build/wirelift/}.
 * <ul>
 * <li>{@code packets.tsv}: id, class, handler, direction, priority, reliability, write and read orders;
 * <li>{@code entities.txt}: every generated entity class with its {@code writeSync} and save {@code write} bodies;
 * <li>{@code buildings.txt}: every {@code Building} subclass that overrides an IO method, with a shape verdict;
 * <li>{@code typeio.txt}: every {@code TypeIO} reader and writer;
 * <li>{@code others.txt}: other bodies the wire depends on (modules, payloads, buffers, map sections).
 * </ul>
 */
final class Reports{
    private static final List<String[]> IO_METHODS = List.of(
        new String[]{"version", "()B"},
        new String[]{"write", WRITE},
        new String[]{"read", "(L" + R + ";B)V"},
        new String[]{"writeAll", WRITE},
        new String[]{"readAll", "(L" + R + ";B)V"},
        new String[]{"writeSync", WRITE},
        new String[]{"readSync", "(L" + R + ";B)V"});

    private final WireLift lift;

    private Reports(WireLift lift){
        this.lift = lift;
    }

    static void write(WireLift lift, Path dir) throws IOException{
        Files.createDirectories(dir);
        var reports = new Reports(lift);
        reports.packets(dir.resolve("packets.tsv"));
        reports.entities(dir.resolve("entities.txt"));
        reports.buildings(dir.resolve("buildings.txt"));
        reports.typeIO(dir.resolve("typeio.txt"));
        reports.others(dir.resolve("others.txt"));
    }

    private void packets(Path file) throws IOException{
        try(var out = new PrintWriter(Files.newBufferedWriter(file))){
            out.println("id\tclass\thandler\tdir\tpriority\treliable\tsends\twrite_order\tread_order");
            for(PacketLift p : lift.packets()){
                out.println(p.id() + "\t" + p.simpleName() + "\t" + p.handler() + "\t" + p.direction() + "\t" + p.priority() + "\t"
                    + p.reliable() + "\t" + p.sends() + "\t" + p.write() + "\t" + p.read());
            }
        }
    }

    private void entities(Path file) throws IOException{
        try(var out = new PrintWriter(Files.newBufferedWriter(file))){
            for(var e : lift.classes.entrySet()){
                String cls = e.getKey();
                ClassModel model = e.getValue();
                if(!cls.startsWith("mindustry/gen/") || !concrete(model) || lift.method(cls, "classId", "()I") == null) continue;
                out.println("=== " + simple(cls) + " classId=" + constReturn(model, "classId", "?") + " extends "
                    + simple(lift.superOf(cls)) + " serialize=" + constReturn(model, "serialize", "?"));
                MethodModel sync = lift.resolve(cls, "writeSync", WRITE), save = lift.method(cls, "write", WRITE);
                out.println("  writeSync" + (sync != null && lift.method(cls, "writeSync", WRITE) == null ? " (inherited)" : "") + ":");
                if(sync != null) out.println(indent(render(lift.lift(lift.owner(cls, "writeSync", WRITE), sync)), "    "));
                out.println("  write (save):");
                if(save != null) out.println(indent(render(lift.lift(cls, save)), "    "));
            }
        }
    }

    private void buildings(Path file) throws IOException{
        List<String> builds = lift.classes.keySet().stream().filter(c -> !c.equals(BUILDING) && lift.isSubclass(c, BUILDING)).toList();
        int overriding = 0, straight = 0;
        StringBuilder body = new StringBuilder();
        StringBuilder summary = new StringBuilder("class\tsuper\tversion\twrite_shape\twrite_layout\tread\tsource\n");
        for(String cls : builds){
            ClassModel model = lift.classes.get(cls);
            List<String> overridden = new ArrayList<>();
            for(String[] io : IO_METHODS) if(lift.method(cls, io[0], io[1]) != null) overridden.add(io[0]);
            if(overridden.isEmpty()) continue;
            overriding++;
            String version = lift.method(cls, "version", "()B") != null ? constReturn(model, "version", "?") : "(inherits " + inheritedVersion(cls) + ")";
            String parent = simple(lift.superOf(cls));
            body.append("=== ").append(simple(cls)).append(" extends ").append(parent)
                .append("  version=").append(version).append("  overrides=").append(overridden).append('\n');
            String shape = "-", ops = "-", source = "-";
            MethodModel write = lift.method(cls, "write", WRITE);
            if(write != null){
                Lifted l = lift.lift(cls, write);
                shape = l.shape();
                if(shape.equals("straight")) straight++;
                ops = compact(l);
                source = l.source();
                body.append("  write @ ").append(source).append("  [").append(shape).append("]\n").append(indent(render(l), "    ")).append('\n');
            }
            for(String[] io : IO_METHODS){
                if(io[0].equals("write") || io[0].equals("version")) continue;
                MethodModel m = lift.method(cls, io[0], io[1]);
                if(m == null) continue;
                Lifted l = lift.lift(cls, m);
                body.append("  ").append(io[0]).append(" @ ").append(l.source()).append("  [").append(l.shape()).append("]\n")
                    .append(indent(render(l), "    ")).append('\n');
            }
            MethodModel read = lift.method(cls, "read", "(L" + R + ";B)V");
            summary.append(simple(cls)).append('\t').append(parent).append('\t').append(version).append('\t').append(shape).append('\t')
                .append(ops).append('\t').append(read != null ? compact(lift.lift(cls, read)) : "-").append('\t').append(source).append('\n');
        }
        Files.writeString(file, "# " + builds.size() + " Building subclasses, " + overriding + " override an IO method, " + straight
            + " of whose write() bodies are straight-line\n\n" + summary + "\n" + body);
    }

    private String inheritedVersion(String cls){
        for(String c = lift.superOf(cls); c != null; c = lift.superOf(c)){
            if(lift.method(c, "version", "()B") != null) return simple(c) + "=" + constReturn(lift.classes.get(c), "version", "?");
        }
        return "0";
    }

    private void typeIO(Path file) throws IOException{
        String cls = "mindustry/io/TypeIO";
        try(var out = new PrintWriter(Files.newBufferedWriter(file))){
            for(MethodModel m : lift.classes.get(cls).methods()){
                String name = m.methodName().stringValue();
                if(!(name.startsWith("write") || name.startsWith("read"))) continue;
                Lifted l = lift.lift(cls, m);
                out.println("=== " + name + m.methodType().stringValue() + " @ " + l.source() + " [" + l.shape() + "]");
                out.println(indent(render(l), "    "));
            }
        }
    }

    private void others(Path file) throws IOException{
        String[][] targets = {
            {"mindustry/world/modules/ItemModule", "write"}, {"mindustry/world/modules/LiquidModule", "write"},
            {"mindustry/world/modules/PowerModule", "write"}, {"mindustry/world/blocks/payloads/BuildPayload", "write"},
            {"mindustry/world/blocks/payloads/UnitPayload", "write"}, {"mindustry/world/blocks/payloads/Payload", "write"},
            {"mindustry/world/blocks/ItemBuffer", "write"}, {"mindustry/world/DirectionalItemBuffer", "write"},
            {"mindustry/world/blocks/distribution/DirectionalItemBuffer", "write"}, {"mindustry/entities/units/StatusEntry", "write"},
            {"mindustry/game/MapObjectives", "write"}, {"mindustry/world/blocks/payloads/PayloadBlock$PayloadBlockBuild", "write"},
            {"mindustry/logic/LExecutor", "write"}, {"mindustry/world/blocks/logic/LogicBlock", "compress"},
            {"mindustry/world/blocks/logic/CanvasBlock$CanvasBuild", "write"}, {"mindustry/io/SaveVersion", "writeMap"},
            {"mindustry/io/SaveVersion", "writeTeamBlocks"},
        };
        try(var out = new PrintWriter(Files.newBufferedWriter(file))){
            for(String[] t : targets){
                ClassModel model = lift.classes.get(t[0]);
                if(model == null){
                    out.println("=== " + t[0] + " (absent)");
                    continue;
                }
                for(MethodModel m : model.methods()){
                    if(!m.methodName().stringValue().equals(t[1])) continue;
                    Lifted l = lift.lift(t[0], m);
                    out.println("=== " + simple(t[0]) + "." + t[1] + m.methodType().stringValue() + " @ " + l.source() + " [" + l.shape() + "]");
                    out.println(indent(render(l), "    "));
                }
            }
        }
    }
}
