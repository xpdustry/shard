package com.xpdustry.shard.server.connect;

import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.wire.*;

import java.util.*;
import java.util.function.*;

/**
 * Vanilla's connect handler checks ({@code NetServer.java:158-300}), in its order, for one {@code ConnectPacket}. The
 * admin model's checks ({@link AdminService#admit}: address and subnet bans, the UUID change limit, a missing UUID or
 * USID, UUID bans, kick cooldowns, the player limit and the whitelist) run first; then mods, the version type, the
 * strict-mode duplicates, the name and the version. Every {@link KickReason} a connect can earn comes from here.
 *
 * <p>Two differences in order. A second connect packet on a connection is refused ({@code idInUse}) before the admin
 * checks, where vanilla runs the address ban and UUID change checks first: a repeated packet doesn't count as another
 * UUID. And the whitelist, the last of the admin model's checks, comes before the mod check, where vanilla checks mods
 * first: a modded stranger hears {@code whitelist}, not the mod list.
 *
 * <p>The checks read a snapshot of the live sessions, so two connects at once can both pass them. They are vanilla's
 * order and answers; the connect flow's {@link Ledger} then takes the place atomically.
 */
public final class Admission{
    /** Vanilla's {@code Version.type} for a release build. */
    public static final String VERSION_TYPE = "official";
    /** v160.7's {@code Version.build}. */
    public static final int BUILD = 160;

    private final AdminService admin;
    private final Supplier<Config> config;

    public Admission(AdminService admin, Supplier<Config> config){
        this.admin = admin;
        this.config = config;
    }

    /** What to do with a connect packet. */
    public sealed interface Verdict{
        /**
         * Let it join, as {@code identity}, an admin or not.
         *
         * @param indexVersion the moderation index the admin model decided with ({@link AdminService#isCurrent})
         */
        record Admit(Identity identity, boolean admin, long indexVersion) implements Verdict{
        }

        /** {@code kick(reason)}. {@code detail} is for the log. */
        record Kick(KickReason reason, String detail) implements Verdict{
        }

        /** {@code kick(text)}: vanilla's incompatible mods message. */
        record KickText(String text, String detail) implements Verdict{
        }

        /** Close without answering: a banned address. */
        record Ignore(String detail) implements Verdict{
        }
    }

    /**
     * Checks one connect packet.
     *
     * @param address the connection's address, as the admin model reads it
     * @param begun whether this connection sent a connect packet before
     * @param live the identities of the other sessions, for the strict-mode duplicate checks
     * @param playersOnline what {@code net.player-limit} counts
     */
    public Verdict check(Bootstrap.Connect connect, String address, boolean begun, Collection<Identity> live, int playersOnline){
        if(begun) return new Verdict.Kick(KickReason.ID_IN_USE, "a second connect packet");
        String uuid = connect.uuidString();
        Config host = config.get();
        switch(admin.admit(new AdminService.Connect(uuid, connect.usid(), connect.name(), address, playersOnline))){
            case AdminService.Decision.Ignore ignore -> {
                return new Verdict.Ignore(ignore.detail());
            }
            case AdminService.Decision.Kick kick -> {
                return new Verdict.Kick(kick.reason(), kick.detail());
            }
            case AdminService.Decision.Admit admit -> {
                Verdict rest = afterAdmin(connect, uuid, live, host);
                return rest != null ? rest : new Verdict.Admit(Identity.of(connect), admit.admin(), admit.indexVersion());
            }
        }
    }

    private static Verdict afterAdmin(Bootstrap.Connect connect, String uuid, Collection<Identity> live, Config host){
        // Shard runs no mods: every mod the client has is one too many (Mods.getIncompatibility)
        var mods = connect.mods().stream().filter(Objects::nonNull).toList();
        if(!mods.isEmpty()){
            return new Verdict.KickText("[accent]Incompatible mods![]\n\nUnnecessary mods:[lightgray]\n> " + String.join("\n> ", mods),
                "incompatible mods " + mods);
        }

        String type = connect.versionType();
        boolean custom = connect.build() == Bootstrap.CUSTOM_BUILD;
        if(type == null || ((custom || !type.equals(VERSION_TYPE)) && !host.get(ConfigKeys.NET_ALLOW_CUSTOM_CLIENTS))){
            return VERSION_TYPE.equals(type)
                ? new Verdict.Kick(KickReason.CUSTOM_CLIENT, "custom build " + connect.build())
                : new Verdict.Kick(KickReason.TYPE_MISMATCH, "version type " + type);
        }

        if(host.get(ConfigKeys.GAME_STRICT)){
            String plain = Names.stripColors(connect.name() == null ? "" : connect.name()).trim();
            for(Identity other : live){
                if(Names.stripColors(other.name()).trim().equalsIgnoreCase(plain)) return new Verdict.Kick(KickReason.NAME_IN_USE, "name " + plain);
            }
            for(Identity other : live){
                if(other.uuid().equals(uuid) || Objects.equals(other.usid(), connect.usid())) return new Verdict.Kick(KickReason.ID_IN_USE, "uuid " + uuid);
            }
        }

        if(Names.fix(connect.name()).trim().isEmpty()) return new Verdict.Kick(KickReason.NAME_EMPTY, "empty name");

        if(connect.build() != BUILD && !custom){
            return connect.build() > BUILD
                ? new Verdict.Kick(KickReason.SERVER_OUTDATED, "client build " + connect.build())
                : new Verdict.Kick(KickReason.CLIENT_OUTDATED, "client build " + connect.build());
        }
        return null;
    }
}
