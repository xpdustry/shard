package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Chat and chat commands (WP49): vanilla's {@code sendChatMessage} path and {@code NetServer}'s client commands, in a
 * host of many worlds.
 *
 * <ol>
 *   <li>{@link #received} takes a {@code sendChatMessage} on the connection's thread: a line over
 *   {@code maxTextLength} is dropped (vanilla's {@code ValidateException}), line breaks are removed, and the line goes
 *   to the session's world as a {@link ChatLine} intent.</li>
 *   <li>The world admits it ({@link ChatLine}): plain lines and {@code /t}'s message run through the
 *   {@link ChatFilters}; a plain line goes out to the world as {@code sendMessage(formatted, raw, sender)}.</li>
 *   <li>After the tick, on the world's thread, a command line runs through the {@link #commands} handler: Arc's
 *   {@link CommandHandler} with prefix {@code /}, and vanilla's invalid-command replies.</li>
 * </ol>
 *
 * <p>The built-in commands are vanilla's, in its order ({@code NetServer.java:346-524}): {@code help}, {@code t},
 * {@code a}, {@code votekick}, {@code vote}, {@code sync}; then Shard's {@code world}. Plain chat, team chat and
 * votes reach the sender's world, as vanilla's {@code Groups.player} is its one world; admin chat reaches the admins
 * of every world, the world's own with the sender named, the others' without (an entity id means nothing there).
 */
public final class Chat implements HostListener{
    private static final Logger LOG = LoggerFactory.getLogger(Chat.class);
    /** The client command prefix. */
    public static final String PREFIX = "/";
    /** {@code /t}'s name: its message is filtered at admission. */
    static final String TEAM = "t";
    /** Vanilla's {@code maxTextLength}. */
    public static final int MAX_TEXT_LENGTH = 150;
    /** {@code /help}'s {@code commandsPerPage}. */
    static final int COMMANDS_PER_PAGE = 6;
    /** Vanilla's {@code /sync} cooldown, 5 s. */
    static final long SYNC_COOLDOWN_NANOS = 5_000_000_000L;
    /** {@code Pal.adminChat}, as {@code Color.toString()} writes it. */
    static final String ADMIN_CHAT = "ff4000ff";

    private final Host host;
    private final AdminService admin;
    private final ConfigService config;
    private final CommandHandler<CommandContext> commands = new CommandHandler<>(PREFIX);
    private final Votekick votekick;
    /** Each session's last {@code /sync}, on the chat's clock. */
    private final ConcurrentHashMap<Long, Long> lastSync = new ConcurrentHashMap<>();
    private volatile NanoClock clock = NanoClock.SYSTEM;
    private volatile boolean quiet;

    public Chat(Host host, AdminService admin, ConfigService config){
        this.host = host;
        this.admin = admin;
        this.config = config;
        this.votekick = new Votekick(this);
        registerCommands();
        host.listen(this);
    }

    /** The client commands: plugins register theirs here, from any thread. */
    public CommandHandler<CommandContext> commands(){
        return commands;
    }

    /** The clock of the cooldowns and the vote's 30 s; tests move a {@link ManualClock}. */
    public void clock(NanoClock clock){
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    NanoClock clock(){
        return clock;
    }

    Host host(){
        return host;
    }

    AdminService admin(){
        return admin;
    }

    /** The settings as {@code world} sees them. */
    Config config(HostedWorld world){
        return config.current().world(world.id().name());
    }

    /** No chat lines in the log (the load test). */
    public void quiet(boolean quiet){
        this.quiet = quiet;
    }

    // ---- the connection's thread

    /** The outcome of a {@code sendChatMessage}, for the connect flow. */
    public enum Received{
        /** Queued for the session's world, or dropped as vanilla drops it. */
        OK,
        /** The session's mailbox is full of reliable intents: it must be kicked. */
        REFUSED
    }

    /**
     * {@code sendChatMessage(message)} from {@code session}: a line over {@code maxTextLength} is dropped, as vanilla's
     * handler throws on it, its line breaks are removed, and it goes to the session's world. Lines from a session
     * that isn't playing are dropped there, as vanilla drops lines from players not yet added.
     */
    public Received received(Session session, String message, long arrivalNanos){
        if(message == null || message.length() > MAX_TEXT_LENGTH) return Received.OK;
        if(!(session.world() instanceof HostedWorld world)) return Received.OK;
        String text = message.replace("\n", "");
        ChatPolicy policy = ChatPolicy.of(config(world));
        return session.offer(new ChatLine(policy, text), arrivalNanos) == Session.Offer.REFUSED ? Received.REFUSED : Received.OK;
    }

    /** The session's connection closed: forget its cooldowns. */
    public void closed(Session session){
        lastSync.remove(session.id());
    }

    // ---- formatting

    /** {@code player.coloredName()}: {@code [#RRGGBBAA]} and the name. */
    public static String coloredName(Player player){
        return "[#" + String.format(Locale.ROOT, "%08X", player.color) + "]" + player.name;
    }

    /** Vanilla's {@code chatFormatter} (NetServer.java:77). */
    public static String format(Player player, String message){
        return "[coral][[" + coloredName(player) + "[coral]]:[white] " + message;
    }

    /** {@code Strings.stripColors(name)}. */
    static String plain(String name){
        return Names.stripColors(name);
    }

    /** {@code team.color.toString()}: lower case, as {@code /t} writes it. */
    static String teamColor(int team){
        return String.format(Locale.ROOT, "%08x", Team.get(team).color());
    }

    // ---- the world's thread

    @Override
    public void event(HostedWorld world, World state, WorldEvent event){
        switch(event){
            case ChatEvent.Said said -> {
                if(!quiet) LOG.info("[{}] {}: {}", world.id(), said.name(), said.message());
            }
            case ChatEvent.Commanded commanded -> command(world, state, commanded);
            default -> {
            }
        }
    }

    @Override
    public void ticked(HostedWorld world, World state){
        votekick.ticked(world, state);
    }

    @Override
    public void retired(HostedWorld world){
        votekick.retired(world);
    }

    Votekick votekick(){
        return votekick;
    }

    private void command(HostedWorld world, World state, ChatEvent.Commanded commanded){
        Session session = host.sessions().get(commanded.session());
        Roster roster = Roster.of(state);
        Player player = roster == null ? null : roster.player(commanded.session());
        // gone since the tick admitted it
        if(session == null || player == null || player.id() != commanded.player() || session.world() != world) return;
        if(config(world).get(ConfigKeys.CHAT_LOG_COMMANDS) && !quiet) LOG.info("<{}: {}>", plain(player.name), commanded.line());
        var context = new CommandContext(world, state, session, player);
        CommandHandler.Response<CommandContext> response;
        try{
            response = commands.handle(commanded.line(), context);
        }catch(RuntimeException e){
            LOG.error("{}: the command '{}' of {} failed", world.id(), commanded.line(), session, e);
            return;
        }
        if(response.type() != CommandHandler.ResponseType.valid){
            String text = invalid(response);
            if(text != null) context.reply(text);
        }
    }

    /** Vanilla's {@code invalidHandler} (NetServer.java:80-103). */
    String invalid(CommandHandler.Response<CommandContext> response){
        return switch(response.type()){
            case manyArguments -> "[scarlet]Too many arguments. Usage:[lightgray] " + response.command().text() + "[gray] " + response.command().paramText();
            case fewArguments -> "[scarlet]Too few arguments. Usage:[lightgray] " + response.command().text() + "[gray] " + response.command().paramText();
            default -> {
                int minDst = 0;
                CommandHandler.Command<CommandContext> closest = null;
                for(CommandHandler.Command<CommandContext> command : commands.commands()){
                    int dst = levenshtein(command.text(), response.runCommand());
                    if(dst < 3 && (closest == null || dst < minDst)){
                        minDst = dst;
                        closest = command;
                    }
                }
                yield closest != null ? "[scarlet]Unknown command. Did you mean \"[lightgray]" + closest.text() + "[]\"?"
                    : "[scarlet]Unknown command. Check [lightgray]/help[scarlet].";
            }
        };
    }

    /** Arc's {@code Strings.levenshtein}. */
    static int levenshtein(String x, String y){
        int[][] dp = new int[x.length() + 1][y.length() + 1];
        for(int i = 0; i <= x.length(); i++){
            for(int j = 0; j <= y.length(); j++){
                if(i == 0) dp[i][j] = j;
                else if(j == 0) dp[i][j] = i;
                else dp[i][j] = Math.min(Math.min(dp[i - 1][j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1), dp[i - 1][j] + 1), dp[i][j - 1] + 1);
            }
        }
        return dp[x.length()][y.length()];
    }

    /** The world's added players with their sessions, in {@code Groups.player} order. */
    static List<Map.Entry<Long, Player>> playing(World state){
        Roster roster = Roster.of(state);
        Players players = Players.of(state);
        if(roster == null || players == null) return List.of();
        var sessions = new IdentityHashMap<Player, Long>();
        roster.players().forEach((session, player) -> sessions.put(player, session));
        var out = new ArrayList<Map.Entry<Long, Player>>();
        for(int i = 0; i < players.list().size(); i++){
            Player player = players.list().get(i);
            Long session = sessions.get(player);
            if(session != null) out.add(Map.entry(session, player));
        }
        return out;
    }

    // ---- the built-in commands

    private void registerCommands(){
        commands.register("help", "[page]", "Lists all commands.", (args, c) -> {
            if(args.length > 0 && !isInt(args[0])){
                c.reply("[scarlet]'page' must be a number.");
                return;
            }
            List<CommandHandler.Command<CommandContext>> list = commands.commands();
            int page = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            int pages = (int)Math.ceil((float)list.size() / COMMANDS_PER_PAGE);
            page--;
            if(page >= pages || page < 0){
                c.reply("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }
            var result = new StringBuilder();
            result.append("[orange]-- Commands Page[lightgray] ").append(page + 1).append("[gray]/[lightgray]").append(pages).append("[orange] --\n\n");
            for(int i = COMMANDS_PER_PAGE * page; i < Math.min(COMMANDS_PER_PAGE * (page + 1), list.size()); i++){
                CommandHandler.Command<CommandContext> command = list.get(i);
                result.append("[orange] /").append(command.text()).append("[white] ").append(command.paramText()).append("[lightgray] - ")
                    .append(command.description()).append("\n");
            }
            c.reply(result.toString());
        });

        // the message was filtered at admission (ChatLine)
        commands.register(TEAM, "<message...>", "Send a message only to your teammates.", (args, c) -> {
            Player player = c.player();
            String raw = "[#" + teamColor(player.team) + "]<T> " + format(player, args[0]);
            c.world().outbox().send(Audience.team(player.team), Calls.chat(raw, args[0], player.id()));
        });

        commands.register("a", "<message...>", "Send a message only to admins.", (args, c) -> {
            if(!c.player().admin){
                c.reply("[scarlet]You must be an admin to use this command.");
                return;
            }
            String raw = "[#" + ADMIN_CHAT + "]<A> " + format(c.player(), args[0]);
            adminChat(c, raw, args[0]);
        });

        commands.register("votekick", "[player] [reason...]", "Vote to kick a player with a valid reason.", votekick::start);
        commands.register("vote", "<y/n/c>", "Vote to kick the current player. Admins can cancel the voting with 'c'.", votekick::vote);

        commands.register("sync", "", "Re-synchronize world state.", (args, c) -> {
            long now = clock.nanoTime();
            Long last = lastSync.get(c.session().id());
            if(last != null && now - last < SYNC_COOLDOWN_NANOS){
                c.reply("[scarlet]You may only /sync every 5 seconds.");
                return;
            }
            lastSync.put(c.session().id(), now);
            c.world().resync(c.session());
        });

        commands.register("world", "[name]", "Lists the worlds, or moves you to one.", (args, c) -> {
            if(args.length == 0){
                c.reply("[lightgray]Worlds: " + String.join(", ", host.worlds().stream().map(w -> w.id().name()).toList())
                    + ". [accent]/world <name>[] moves you.");
                return;
            }
            HostedWorld target = host.world(args[0]).orElse(null);
            if(target == null){
                // the load bots name worlds by position
                try{
                    int index = Integer.parseInt(args[0]);
                    List<HostedWorld> all = host.worlds();
                    if(index >= 0 && index < all.size()) target = all.get(index);
                }catch(NumberFormatException ignored){
                }
            }
            if(target == null) c.reply("[scarlet]No world " + args[0] + ".");
            else if(c.world() == target) c.reply("[lightgray]You are already in " + target.id() + ".");
            else if(!host.transfer(c.session(), target)) c.reply("[scarlet]Can't move to " + target.id() + " now.");
        });
    }

    /** {@code Groups.player.each(Player::admin, a -> a.sendMessage(raw, player, message))}, over every world. */
    private void adminChat(CommandContext c, String raw, String message){
        Frame own = Calls.chat(raw, message, c.player().id()), elsewhere = null;
        for(Map.Entry<Long, Player> entry : playing(c.state())){
            if(entry.getValue().admin) c.world().outbox().send(Audience.only(entry.getKey()), own);
        }
        for(HostedWorld world : host.worlds()){
            if(world == c.world()) continue;
            for(Session session : host.sessions().all()){
                if(!session.admin() || session.world() != world || session.state() != SessionState.IN_WORLD) continue;
                if(elsewhere == null) elsewhere = Calls.chat(raw, message, -1);
                world.outbox().offer(Audience.only(session.id()), elsewhere);
            }
        }
    }

    /** {@code Strings.canParseInt}. */
    static boolean isInt(String text){
        try{
            Integer.parseInt(text);
            return true;
        }catch(NumberFormatException e){
            return false;
        }
    }

    /** {@code player.kick(reason, duration)} for every session of {@code uuid} on the server: the cooldown, then the kick. */
    void kick(String uuid, KickReason reason, Duration ban){
        for(Session session : host.sessions().all()){
            if(session.state() == SessionState.CLOSED || !session.identity().uuid().equals(uuid)) continue;
            admin.kicked(uuid, session.conn().remote().getAddress().getHostAddress(), ban);
            session.conn().closeAfter(Calls.kick(reason), "kicked: " + reason.vanillaName());
            LOG.info("{} kicked: {}", session, reason.vanillaName());
        }
    }
}
