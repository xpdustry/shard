package com.xpdustry.shard.server.console;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.store.Bans;
import com.xpdustry.shard.server.store.Players.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

import static com.xpdustry.shard.server.console.Console.await;

/**
 * Vanilla's moderation commands ({@code ServerControl.java:857}-{@code 1300}) over the {@link AdminService}: kick, ban,
 * bans, unban, pardon, admin, admins, whitelist, subnet-ban, name-ban, dos-ban, info and search. Their lines are
 * vanilla's, so a script reads both servers alike; what Shard adds (ban ids, CIDR blocks, last seen) comes after.
 * Everything they change is in the store and holds for every world.
 */
final class AdminCommands{
    static final String ACTOR = "console";
    /** Vanilla's dotted prefix form of a subnet: one to three whole octets and a dot. */
    private static final Pattern VANILLA_PREFIX = Pattern.compile("(\\d{1,3}\\.){1,3}");

    private final Server server;
    private final PrintStream out;

    AdminCommands(Server server, PrintStream out){
        this.server = server;
        this.out = out;
    }

    void register(Console console){
        console.register("kick", "<name|session-id...>", "Kick a person by name.", this::kick);
        console.register("ban", "<id|name|ip> <uuid|name|ip...>", "Ban a person.", this::ban);
        console.register("bans", "", "List all banned IPs and IDs.", this::bans);
        console.register("unban", "<ip|ID>", "Completely unban a person by IP or ID.", this::unban);
        console.register("pardon", "<ID>", "Pardons a votekicked player by ID and allows them to join again.", this::pardon);
        console.register("admin", "<add|remove> <name|ID...>", "Make an online user admin, or a known one by ID.", this::admin);
        console.register("admins", "", "List all admins.", this::admins);
        console.register("whitelist", "[add|remove] [ID]", "Add/remove players from the whitelist using their ID.", this::whitelist);
        console.register("subnet-ban", "[add|remove] [cidr]", "Ban a subnet, as a CIDR block (10.0.0.0/8); vanilla's 10.0. prefixes are read as one.", this::subnetBan);
        console.register("name-ban", "[add|remove|clear] [regex]", "Ban a name by case-insensitive regex.", this::nameBan);
        console.register("dos-ban", "[add|remove] [ip]", "Add or remove a DOS ban, until the server restarts.", this::dosBan);
        console.register("info", "<IP|UUID|name...>", "Find player info(s): every name and IP a player has had.", this::info);
        console.register("search", "<name...>", "Search players who have used part of a name.", this::search);
    }

    private AdminService admin(){
        return server.admin();
    }

    /** Vanilla's {@code kick}: a broadcast in the player's world, then the kick and its cooldown. */
    private void kick(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: kick <name|session-id...>");
        if(server.host().worlds().isEmpty()){
            out.println("Not hosting a game yet. Calm down.");
            return;
        }
        Session session = Console.find(server, String.join(" ", args));
        if(session == null){
            out.println("Nobody with that name could be found...");
            return;
        }
        announce(session, "[scarlet]" + session.identity().name() + "[scarlet] has been kicked by the server.");
        kickSession(session, KickReason.KICK, "kicked by the console");
        out.println("It is done.");
    }

    /**
     * Vanilla's {@code ban id|name|ip}, then every online player the ban reaches is told and kicked with
     * {@code banned}. A ban by name bans the player's UUID and address together: vanilla's {@code banPlayer} bans the
     * address only when the UUID was banned already ({@code banPlayerID(uuid) || banPlayerIP(ip)},
     * {@code Administration.java:218}).
     */
    private void ban(String[] args){
        if(args.length < 2) throw new IllegalArgumentException("usage: ban <id|name|ip> <uuid|name|ip...>");
        String target = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Predicate<Session> reached;
        switch(args[0]){
            case "id" -> {
                await(admin().ban(Bans.Kind.UUID, target, "", null, ACTOR));
                reached = session -> session.identity().uuid().equals(target);
            }
            case "name" -> {
                Session session = Console.findByName(server, target);
                if(session == null){
                    out.println("No matches found.");
                    return;
                }
                String uuid = session.identity().uuid();
                await(admin().banPlayerAt(uuid, Console.address(session), "", null, ACTOR));
                reached = other -> other.identity().uuid().equals(uuid);
            }
            case "ip" -> {
                String canonical = AdminService.canonicalTarget(Bans.Kind.ADDRESS, target);
                await(admin().ban(Bans.Kind.ADDRESS, canonical, "", null, ACTOR));
                reached = session -> Console.address(session).equals(canonical);
            }
            default -> {
                out.println("Invalid type.");
                return;
            }
        }
        out.println("Banned.");
        for(Session session : live()){
            if(!reached.test(session)) continue;
            announce(session, "[scarlet]" + session.identity().name() + " has been banned.");
            kickSession(session, KickReason.BANNED, "banned by the console");
        }
    }

    /** Vanilla's {@code bans}: UUID bans, then address bans, each with the player's last name; then Shard's ids. */
    private void bans(String[] args){
        var bans = await(admin().activeBanDetails());
        var uuids = bans.stream().filter(b -> b.ban().kind() == Bans.Kind.UUID).toList();
        var addresses = bans.stream().filter(b -> b.ban().kind() == Bans.Kind.ADDRESS).toList();
        var subnets = bans.stream().filter(b -> b.ban().kind() == Bans.Kind.SUBNET).toList();
        if(uuids.isEmpty()){
            out.println("No ID-banned players have been found.");
        }else{
            out.println("Banned players [ID]:");
            for(var ban : uuids) out.println(" " + ban.ban().target() + " / Last known name: '" + lastName(ban.player()) + "'" + suffix(ban.ban()));
        }
        if(addresses.isEmpty()){
            out.println("No IP-banned players have been found.");
        }else{
            out.println("Banned players [IP]:");
            for(var ban : addresses){
                out.println(ban.player() == null ? "  '" + ban.ban().target() + "' (No known name or info)" + suffix(ban.ban())
                    : "  '" + ban.ban().target() + "' / Last known name: '" + lastName(ban.player()) + "' / ID: '" + ban.player().uuid() + "'" + suffix(ban.ban()));
            }
        }
        if(!subnets.isEmpty()){
            out.println("Banned subnets:");
            for(var ban : subnets) out.println("  " + ban.ban().target() + suffix(ban.ban()));
        }
    }

    /** Vanilla's {@code unban <ip/ID>}: an address first, then a UUID. */
    private void unban(String[] args){
        if(args.length != 1) throw new IllegalArgumentException("usage: unban <ip|ID>");
        int lifted = 0;
        if(Addresses.isAddress(args[0])) lifted = await(admin().unbanAddress(args[0], ACTOR));
        if(lifted == 0) lifted = await(admin().unbanPlayer(args[0], ACTOR));
        out.println(lifted > 0 ? "Unbanned player: " + args[0] : "That IP/ID is not banned!");
    }

    private void pardon(String[] args){
        if(args.length != 1) throw new IllegalArgumentException("usage: pardon <ID>");
        var player = await(admin().pardonPlayer(args[0], ACTOR));
        out.println(player.isPresent() ? "Pardoned player: " + lastName(player.get()) : "That ID can't be found.");
    }

    /**
     * Vanilla's {@code admin add|remove <name/ID...>}: an online player by name, colors and case ignored, or a known
     * one by UUID. An online player's flag changes at once, in the session and in their world.
     */
    private void admin(String[] args){
        if(args.length < 2 || !(args[0].equals("add") || args[0].equals("remove"))){
            out.println("Second parameter must be either 'add' or 'remove'.");
            return;
        }
        boolean add = args[0].equals("add");
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Session online = Console.findByName(server, Names.stripColors(query));
        String uuid = online != null ? online.identity().uuid() : query;
        if(online == null){
            for(Session session : live()) if(session.identity().uuid().equals(query)) online = session;
        }
        var known = await(admin().player(uuid));
        if(known.isEmpty()){
            out.println("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
            return;
        }
        if(add) await(admin().grantAdmin(uuid, online == null ? null : online.identity().usid(), ACTOR));
        else await(admin().revokeAdmin(uuid));
        if(online != null) setAdmin(online, add);
        out.println("Changed admin status of player: " + lastName(known.get().player()));
    }

    private void admins(String[] args){
        var admins = await(admin().admins());
        if(admins.isEmpty()){
            out.println("No admins have been found.");
            return;
        }
        out.println("Admins:");
        for(var grant : admins){
            out.println("  " + lastName(grant.player()) + " /  ID: '" + grant.player().uuid() + "' / IP: '" + orUnknown(grant.player().lastAddress()) + "'");
        }
    }

    private void whitelist(String[] args){
        if(args.length == 0){
            var whitelist = await(admin().whitelisted());
            if(whitelist.isEmpty()){
                out.println("No whitelisted players found.");
                return;
            }
            out.println("Whitelist:");
            for(var grant : whitelist) out.println("- Name: " + lastName(grant.player()) + " / UUID: " + grant.player().uuid());
            return;
        }
        if(args.length != 2){
            out.println("Incorrect usage. Provide an ID to add or remove.");
            return;
        }
        var known = await(admin().player(args[1]));
        if(known.isEmpty()){
            out.println("Player ID not found. You must use the ID displayed when a player joins a server.");
            return;
        }
        String name = lastName(known.get().player());
        switch(args[0]){
            case "add" -> {
                await(admin().whitelist(args[1], ACTOR));
                out.println("Player '" + name + "' has been whitelisted.");
            }
            case "remove" -> {
                await(admin().unwhitelist(args[1]));
                out.println("Player '" + name + "' has been un-whitelisted.");
            }
            default -> out.println("Incorrect usage. Provide add/remove as the second argument.");
        }
    }

    private void subnetBan(String[] args){
        if(args.length == 0){
            var subnets = await(admin().activeBans()).stream().filter(b -> b.kind() == Bans.Kind.SUBNET).toList();
            out.println("Subnets banned: " + (subnets.isEmpty() ? "<none>" : ""));
            for(var ban : subnets) out.println("\t" + ban.target());
            return;
        }
        if(args.length == 1){
            out.println("You must provide a subnet to add or remove.");
            return;
        }
        String cidr = subnet(args[1]);
        boolean banned = await(admin().activeBans()).stream().anyMatch(b -> b.kind() == Bans.Kind.SUBNET && b.target().equals(cidr));
        switch(args[0]){
            case "add" -> {
                if(banned){
                    out.println("That subnet is already banned.");
                    return;
                }
                await(admin().ban(Bans.Kind.SUBNET, cidr, "", null, ACTOR));
                out.println("Banned " + cidr);
            }
            case "remove" -> {
                if(!banned){
                    out.println("That subnet isn't banned.");
                    return;
                }
                await(admin().unban(Bans.Kind.SUBNET, cidr, ACTOR));
                out.println("Unbanned " + cidr);
            }
            default -> out.println("Incorrect usage. Provide add/remove as the second argument.");
        }
    }

    /** A CIDR block, or vanilla's prefix of whole octets ({@code 192.168.}) as the block it means. */
    static String subnet(String text){
        if(VANILLA_PREFIX.matcher(text).matches()){
            String[] octets = text.split("\\.");
            var address = new StringJoiner(".");
            for(int i = 0; i < 4; i++) address.add(i < octets.length ? octets[i] : "0");
            return Subnet.parse(address + "/" + octets.length * 8, false).toString();
        }
        return Subnet.parse(text, false).toString();
    }

    private void nameBan(String[] args){
        if(args.length == 0){
            var names = await(admin().nameBans());
            out.println("Name regexes banned: " + (names.isEmpty() ? "<none>" : ""));
            for(var name : names) out.println("\t" + name.pattern());
            return;
        }
        if(args.length == 1){
            if(args[0].equals("clear")) out.println("Name regexes unbanned: " + await(admin().clearNameBans()));
            else out.println("You must provide a name regex to add or remove.");
            return;
        }
        switch(args[0]){
            case "add" -> {
                boolean added;
                try{
                    added = await(admin().banName(args[1], ACTOR));
                }catch(IllegalStateException e){
                    out.println(e.getMessage());
                    return;
                }
                out.println(added ? "Banned names by regex: " + args[1] : "That name regex is already banned.");
            }
            case "remove" -> out.println(await(admin().unbanName(args[1])) ? "Unbanned regex: " + args[1] : "That name isn't banned.");
            default -> out.println("Incorrect usage. Provide add/remove as the second argument.");
        }
    }

    private void dosBan(String[] args){
        if(args.length == 0){
            var bans = admin().dosBans();
            out.println("DOS bans: " + (bans.isEmpty() ? "<none>" : ""));
            for(String address : bans) out.println("\t" + address);
            return;
        }
        if(args.length == 1){
            out.println("Expected either zero or two parameters, but only got one parameter.");
            return;
        }
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "add" -> {
                admin().dosBan(args[1]);
                out.println("Dos banned: " + args[1]);
            }
            case "remove" -> {
                admin().dosUnban(args[1]);
                out.println("Removed dos ban: " + args[1]);
            }
            default -> out.println("Unrecognized action: " + args[0]);
        }
    }

    /** Vanilla's {@code info}, then when each player was last seen. */
    private void info(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: info <IP|UUID|name...>");
        var players = await(admin().info(String.join(" ", args)));
        if(players.isEmpty()){
            out.println("Nobody with that name could be found.");
            return;
        }
        out.println("Players found: " + players.size());
        int i = 0;
        for(Detail detail : players){
            Player player = detail.player();
            out.println("[" + i++ + "] Trace info for player '" + lastName(player) + "' / UUID " + player.uuid() + " / RAW " + player.lastName());
            out.println("  all names used: " + detail.names());
            out.println("  IP: " + orUnknown(player.lastAddress()));
            out.println("  all IPs used: " + detail.addresses());
            out.println("  times joined: " + player.timesJoined());
            out.println("  times kicked: " + player.timesKicked());
            out.println("  last seen: " + Instant.ofEpochMilli(player.lastSeen()));
        }
    }

    private void search(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: search <name...>");
        var players = await(admin().search(String.join(" ", args)));
        if(players.isEmpty()){
            out.println("Nobody with that name could be found.");
            return;
        }
        out.println("Players found: " + players.size());
        int i = 0;
        for(Player player : players) out.println("- [" + i++ + "] '" + lastName(player) + "' / " + player.uuid());
    }

    // helpers

    private List<Session> live(){
        return server.sessions().all().stream().filter(session -> session.state() != SessionState.CLOSED).toList();
    }

    /** Kicks a session with {@code reason}, and its cooldown, as vanilla's {@code con.kick(reason)}. */
    private void kickSession(Session session, KickReason reason, String why){
        session.conn().closeAfter(Calls.kick(reason), why);
        admin().kicked(session.identity().uuid(), Console.address(session), reason, ACTOR);
    }

    /** A chat line to everyone in the session's world, as vanilla's {@code Call.sendMessage} on its one world. */
    private static void announce(Session session, String text){
        if(session.world() instanceof HostedWorld world) world.outbox().offer(Audience.ALL, Calls.message(text));
    }

    /** The online player's admin flag: the session's, and its player's through a logged command. */
    private static void setAdmin(Session session, boolean admin){
        session.admin(admin);
        if(session.world() instanceof HostedWorld world && world.managed() != null){
            world.managed().offer(new Submission(new SetAdmin(session.id(), admin), new Cause.Admin(ACTOR)));
        }
    }

    private static String suffix(Bans.Ban ban){
        return " (#" + ban.id() + (ban.reason().isEmpty() ? "" : ", " + ban.reason()) + (ban.expiresAt() == null ? "" : ", until " + Instant.ofEpochMilli(ban.expiresAt())) + ")";
    }

    /** {@code PlayerInfo.plainLastName()}. */
    static String lastName(Player player){
        return player == null ? "<unknown>" : Names.stripColors(player.lastName());
    }

    private static String orUnknown(String text){
        return text == null ? "<unknown>" : text;
    }
}
