package com.xpdustry.shard.sim.persist;

import java.io.*;
import java.nio.charset.*;

/**
 * The id of the running build, which checkpoints record and {@code shard replay} compares: replaying a log is only
 * exact on the build that wrote it. It is the first line of the resource {@code META-INF/shard/build-id} (the
 * release build writes it), or {@code dev} when there is none.
 */
public final class BuildInfo{
    private static final String ID = read();

    public static String id(){
        return ID;
    }

    private static String read(){
        try(InputStream in = BuildInfo.class.getClassLoader().getResourceAsStream("META-INF/shard/build-id")){
            if(in == null) return "dev";
            String line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            return line == null || line.isBlank() ? "dev" : line.strip();
        }catch(IOException e){
            return "dev";
        }
    }

    private BuildInfo(){}
}
