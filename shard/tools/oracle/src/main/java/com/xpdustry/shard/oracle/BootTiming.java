package com.xpdustry.shard.oracle;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.*;
import java.nio.file.*;

/**
 * Where an oracle JVM's time goes (W4.12): with {@code -Dcom.xpdustry.shard.oracle.timing=<file>}, each JVM that boots
 * vanilla appends one tab-separated line when it exits: the test class that booted it, then epoch milliseconds for the
 * JVM's start, the boot's start and end, and the exit. Off (and free) without the property.
 */
final class BootTiming{
    private BootTiming(){
    }

    static void booted(long bootStart, long bootEnd){
        String file = System.getProperty("com.xpdustry.shard.oracle.timing");
        if(file == null || file.isEmpty()) return;
        String caller = StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
            .filter(name -> !name.startsWith("com.xpdustry.shard.oracle.")).findFirst().orElse("?"));
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String line = caller + "\t" + jvmStart + "\t" + bootStart + "\t" + bootEnd + "\t" + System.currentTimeMillis() + "\n";
            try{
                Files.writeString(Path.of(file), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }catch(IOException e){
                System.err.println("oracle timing: " + e);
            }
        }));
    }
}
