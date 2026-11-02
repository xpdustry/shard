package com.xpdustry.shard.server.admin;

import java.util.*;

/**
 * What an admin's {@code trace} shows: vanilla's {@code Administration.TraceInfo} ({@code Administration.java:679}),
 * field for field and in the order {@code TypeIO.writeTraceInfo} writes them ({@code TypeIO.java:1322}), so the wire
 * codec can encode it as is.
 *
 * @param ip the live connection's address
 * @param modded whether the client reported build -1 (vanilla's {@code con.modclient})
 * @param ips every address the player joined from, oldest first
 * @param names every name the player joined with, oldest first
 */
public record TraceInfo(String ip, String uuid, String locale, boolean modded, boolean mobile, int timesJoined,
                        int timesKicked, List<String> ips, List<String> names){
    public TraceInfo{
        ips = List.copyOf(ips);
        names = List.copyOf(names);
    }
}
