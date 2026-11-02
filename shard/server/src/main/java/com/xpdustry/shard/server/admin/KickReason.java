package com.xpdustry.shard.server.admin;

/**
 * Vanilla's {@code Packets.KickReason} ({@code Packets.java:17}). The wire carries a reason as its {@link #id()},
 * the ordinal, so the order is the protocol: never reorder or insert. {@code KickReasonTest} checks it against
 * the vanilla jar.
 */
public enum KickReason{
    KICK, CLIENT_OUTDATED, SERVER_OUTDATED, BANNED, GAMEOVER(true), RECENT_KICK,
    NAME_IN_USE, ID_IN_USE, NAME_EMPTY, CUSTOM_CLIENT, SERVER_CLOSE, VOTE, TYPE_MISMATCH,
    WHITELIST, PLAYER_LIMIT, SERVER_RESTARTING;

    private static final KickReason[] ALL = values();

    /** The client shows no dialog for a quiet kick. */
    public final boolean quiet;

    KickReason(){
        this(false);
    }

    KickReason(boolean quiet){
        this.quiet = quiet;
    }

    /** The id on the wire. */
    public int id(){
        return ordinal();
    }

    public static KickReason byId(int id){
        if(id < 0 || id >= ALL.length) throw new IllegalArgumentException("no kick reason " + id);
        return ALL[id];
    }

    /** The vanilla constant name ({@code recentKick}), which is also its bundle key {@code server.kicked.<name>}. */
    public String vanillaName(){
        var out = new StringBuilder();
        boolean upper = false;
        for(char c : name().toLowerCase(java.util.Locale.ROOT).toCharArray()){
            if(c == '_'){
                upper = true;
            }else{
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /**
     * Whether this kick keeps the player out for {@code admin.kick-cooldown}: a plain kick, a ban and a vote kick do,
     * the others don't ({@code NetConnection.java:50}).
     */
    public boolean hasCooldown(){
        return this == KICK || this == BANNED || this == VOTE;
    }
}
