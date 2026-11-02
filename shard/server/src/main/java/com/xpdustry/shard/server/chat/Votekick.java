package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Vanilla's {@code /votekick} and {@code /vote} (NetServer.java:391-508, {@code VoteSession} 1435-1475), one vote at a
 * time per world, among the world's players. A vote needs three players in the world, a reason and a target on the
 * starter's team who isn't an admin; the starter votes yes, and each starter waits {@link #COOLDOWN} between votes.
 * It passes at {@link #required} votes, which bans the target's uuid for {@link #BAN}, and fails after
 * {@link #DURATION}. A player's vote counts by uuid and by address, so a second client from one address changes its
 * vote rather than adding one.
 *
 * <p>World state is read on the world's thread; the votes and cooldowns are the server's, as vanilla's are, and time
 * is the chat's clock.
 */
final class Votekick{
    /** {@code kickDuration}: 60 minutes. */
    static final Duration BAN = Duration.ofSeconds(60 * 60);
    /** {@code voteDuration}: 30 s. */
    static final Duration DURATION = Duration.ofSeconds(30);
    /** {@code voteCooldown}: 5 minutes. */
    static final Duration COOLDOWN = Duration.ofSeconds(60 * 5);

    private final Chat chat;
    /**
     * Each world's vote in progress: {@code currentlyKicking}. Read and written on that world's thread, and dropped
     * once it retired.
     */
    private final ConcurrentHashMap<HostedWorld, Vote> votes = new ConcurrentHashMap<>();
    /** When each uuid last started a vote, on the chat's clock: the {@code cooldowns} Timekeepers. */
    private final ConcurrentHashMap<String, Long> started = new ConcurrentHashMap<>();

    /** {@code VoteSession}. */
    private static final class Vote{
        final long target;
        final String uuid, name;
        final int team;
        final long deadline;
        /** By uuid and by address, as vanilla's {@code voted} map keys both. */
        final HashMap<String, Integer> voted = new HashMap<>();
        int votes;

        Vote(long target, String uuid, String name, int team, long deadline){
            this.target = target;
            this.uuid = uuid;
            this.name = name;
            this.team = team;
            this.deadline = deadline;
        }
    }

    Votekick(Chat chat){
        this.chat = chat;
    }

    /** {@code votesRequired()}. */
    static int required(int players){
        return 2 + (players > 4 ? 1 : 0);
    }

    /** {@code /votekick [player] [reason...]}. */
    void start(String[] args, CommandContext c){
        if(!chat.config(c.world()).get(ConfigKeys.GAME_VOTEKICK)){
            c.reply("[scarlet]Vote-kick is disabled on this server.");
            return;
        }
        List<Map.Entry<Long, Player>> players = Chat.playing(c.state());
        if(players.size() < 3){
            c.reply("[scarlet]At least 3 players are needed to start a votekick.");
            return;
        }
        if(votes.containsKey(c.world())){
            c.reply("[scarlet]A vote is already in progress.");
            return;
        }
        Player player = c.player();
        if(args.length == 0){
            var builder = new StringBuilder();
            builder.append("[orange]Players to kick: \n");
            for(Map.Entry<Long, Player> entry : players){
                Player p = entry.getValue();
                if(!p.admin && p != player) builder.append("[lightgray] ").append(p.name).append("[accent] (#").append(p.id()).append(")\n");
            }
            c.reply(builder.toString());
        }else if(args.length == 1){
            c.reply("[orange]You need a valid reason to kick the player. Add a reason after the player name.");
        }else{
            Map.Entry<Long, Player> found = null;
            if(args[0].length() > 1 && args[0].startsWith("#") && Chat.isInt(args[0].substring(1))){
                int id = Integer.parseInt(args[0].substring(1));
                found = players.stream().filter(e -> e.getValue().id() == id).findFirst().orElse(null);
            }else{
                found = players.stream().filter(e -> e.getValue().name.equalsIgnoreCase(args[0])).findFirst().orElse(null);
            }
            if(found == null){
                c.reply("[scarlet]No player [orange]'" + args[0] + "'[scarlet] found.");
                return;
            }
            Player target = found.getValue();
            var targetSession = chat.host().sessions().get(found.getKey());
            if(target == player){
                c.reply("[scarlet]You can't vote to kick yourself.");
            }else if(target.admin){
                c.reply("[scarlet]Did you really expect to be able to kick an admin?");
            }else if(target.team != player.team){
                c.reply("[scarlet]Only players on your team can be kicked.");
            }else if(targetSession != null){
                String uuid = c.session().identity().uuid();
                long now = chat.clock().nanoTime();
                Long last = started.get(uuid);
                // Timekeeper.get(): more than the interval since the last reset, and a new one has never been reset
                if(last != null && now - last <= COOLDOWN.toNanos()){
                    c.reply("[scarlet]You must wait " + COOLDOWN.toSeconds() / 60 + " minutes between votekicks.");
                    return;
                }
                var session = new Vote(found.getKey(), targetSession.identity().uuid(), target.name, target.team, now + DURATION.toNanos());
                votes.put(c.world(), session);
                vote(c, session, player, 1, players.size());
                c.broadcast("[lightgray]Reason:[orange] " + args[1] + "[lightgray].");
                started.put(uuid, now);
            }
        }
    }

    /** {@code /vote <y/n/c>}. */
    void vote(String[] args, CommandContext c){
        Vote session = votes.get(c.world());
        if(session == null){
            c.reply("[scarlet]Nobody is being voted on.");
            return;
        }
        Player player = c.player();
        if(player.admin && args[0].equalsIgnoreCase("c")){
            c.broadcast("[lightgray]Vote canceled by admin[orange] " + player.name + "[lightgray].");
            votes.remove(c.world(), session);
            return;
        }
        int sign = switch(args[0].toLowerCase(Locale.ROOT)){
            case "y", "yes" -> 1;
            case "n", "no" -> -1;
            default -> 0;
        };
        String uuid = c.session().identity().uuid(), address = address(c.session());
        if(session.voted.getOrDefault(uuid, 2) == sign || session.voted.getOrDefault(address, 2) == sign){
            c.reply("[scarlet]You've already voted " + args[0].toLowerCase(Locale.ROOT) + ". Sit down.");
            return;
        }
        if(session.target == c.session().id()){
            c.reply("[scarlet]You can't vote on your own trial.");
            return;
        }
        if(session.team != player.team){
            c.reply("[scarlet]You can't vote for other teams.");
            return;
        }
        if(sign == 0){
            c.reply("[scarlet]Vote either 'y' (yes) or 'n' (no).");
            return;
        }
        vote(c, session, player, sign, Chat.playing(c.state()).size());
    }

    /** {@code VoteSession.vote(player, d)}, then {@code checkPass()}. */
    private void vote(CommandContext c, Vote session, Player player, int d, int players){
        String uuid = c.session().identity().uuid(), address = address(c.session());
        // a quirk kept: the earlier votes by uuid and by address are OR-ed, so 1 | -1 takes -1 back
        int lastVote = session.voted.getOrDefault(uuid, 0) | session.voted.getOrDefault(address, 0);
        session.votes -= lastVote;
        session.votes += d;
        session.voted.put(uuid, d);
        session.voted.put(address, d);
        c.broadcast("[lightgray]" + player.name + "[lightgray] has voted on kicking[orange] " + session.name + "[lightgray].[accent] (" + session.votes
            + "/" + required(players) + ")\n[lightgray]Type[orange] /vote <y/n>[] to agree.");
        checkPass(c.world(), session, players);
    }

    private boolean checkPass(HostedWorld world, Vote session, int players){
        if(session.votes < required(players)) return false;
        world.outbox().send(Audience.ALL, Calls.message("[orange]Vote passed.[scarlet] " + session.name + "[orange] will be banned from the server for "
            + BAN.toMinutes() + " minutes."));
        votes.remove(world, session);
        chat.kick(session.uuid, KickReason.VOTE, BAN);
        return true;
    }

    /** The vote's {@code Timer.schedule(..., voteDuration)}: checked after each of the world's ticks. */
    void ticked(HostedWorld world, World state){
        Vote session = votes.get(world);
        if(session == null || chat.clock().nanoTime() - session.deadline < 0) return;
        if(!checkPass(world, session, Chat.playing(state).size())){
            world.outbox().send(Audience.ALL, Calls.message("[lightgray]Vote failed. Not enough votes to kick[orange] " + session.name + "[lightgray]."));
            votes.remove(world, session);
        }
    }

    /** {@code world} retired: its vote goes with it, which its ticks would never end now. */
    void retired(HostedWorld world){
        votes.remove(world);
    }

    /** Whether {@code world} has a vote in progress. */
    boolean voting(HostedWorld world){
        return votes.containsKey(world);
    }

    /** {@code admins.getInfo(uuid).lastIP}: the address this session came from. */
    private static String address(Session session){
        return session.conn().remote().getAddress().getHostAddress();
    }
}
