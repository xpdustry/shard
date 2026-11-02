package com.xpdustry.shard.server.admin;

/**
 * Vanilla's {@code Packets.AdminAction} ({@code Packets.java:45}): what an in-game admin asks for in
 * {@code adminRequest}. The wire carries the ordinal; never reorder.
 */
public enum AdminAction{
    KICK, BAN, TRACE, WAVE, SWITCH_TEAM;

    private static final AdminAction[] ALL = values();

    public int id(){
        return ordinal();
    }

    public static AdminAction byId(int id){
        if(id < 0 || id >= ALL.length) throw new IllegalArgumentException("no admin action " + id);
        return ALL[id];
    }
}
