package com.xpdustry.shard.server.console;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.connect.Admission;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.BuildInfo;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The server's stdin console (WP51): vanilla's {@code ServerControl} commands, adapted to a host of many worlds
 * ({@link AdminCommands} has the moderation ones), and the inspections the rig reads ({@code tile}, {@code players}).
 * Output goes to its own stream, one result per command, so scripts can match it apart from the log.
 * {@code package-info.java} maps all 43 vanilla commands; the ones Shard has no use for answer with the reason.
 */
public final class Console{
    /** How long a command waits for a world (a map load, an inspection) before it reports a timeout. */
    static final Duration WAIT = Duration.ofSeconds(60);

    private final Server server;
    private final PrintStream out;
    private final Runnable exit;
    private final TreeMap<String, Command> commands = new TreeMap<>();
    /** The command line {@code yes} runs: the last unknown command, corrected. */
    private String suggested;
    private final Perf perf;
    private final com.xpdustry.shard.server.game.Saves saves;

    private record Command(String params, String help, Consumer<String[]> run){
    }

    /** @param exit what {@code exit} does once the server is closed (the process exits) */
    public Console(Server server, PrintStream out, Runnable exit){
        this.server = server;
        this.out = out;
        this.exit = exit;
        perf = new Perf(server);
        saves = new com.xpdustry.shard.server.game.Saves(server.host(),
            java.nio.file.Path.of(server.config().current().get(ConfigKeys.PERSISTENCE_SAVES_DIRECTORY)));
        register("help", "[command]", "Display the command list, or get help for a specific command.", this::help);
        register("version", "", "Displays server version info.", this::version);
        register("status", "", "Shows the worlds, their ticks and players.", this::status);
        register("players", "[world]", "Lists the players: id, name, uuid, world, state.", this::players);
        register("maps", "[all|custom|default]", "Display the maps that can be hosted. Displays only custom maps by default.", this::maps);
        register("host", "[map] [mode] [id]", "Hosts a map as a new world. Default: a random map, survival.", this::host);
        register("stop", "[world]", "Stops a world, or every world: their players are kicked and the worlds saved.", this::stop);
        register("recover", "<world>", "Restores a failed world from its last checkpoint; it takes players again.", this::recover);
        new AdminCommands(server, out).register(this);
        register("runwave", "[world]", "Trigger the next wave: in every world, or one.", this::runwave);
        register("fillitems", "[world] [team]", "Fill a team's core with items (default: sharded), in every world or one.", this::fillitems);
        register("config", "[name|reload] [value...]", "Show the server settings, by vanilla or Shard name; reload re-reads shard.yml.", this::config);
        register("playerlimit", "[off|number]", "Show the player limit (net.player-limit).", this::playerlimit);
        register("dumpsettings", "", "Print every setting and where its value comes from.", args -> {
            for(String line : server.config().current().explain()) out.println(line);
        });
        refuse("reloadassets", "Shard's content is the build's epoch, fixed when it is compiled; there are no asset files to reload.");
        refuse("reloadmaps", "the map catalog is read at startup; restart to rescan the maps directory (WP48's map registry).");
        refuse("mods", "Shard runs no mods; plugins use its own API (WP52).");
        refuse("mod", "Shard runs no mods; plugins use its own API (WP52).");
        refuse("js", "Shard has no script engine; inspect a world with tile, status and the ops API.");
        register("pause", "[world] <on/off>", "Pause or unpause the game: every world, or one.", this::pause);
        refuse("rules", "a world's rules come from its map and shard.yml (WP46); there are no global rule overrides to edit.");
        register("save", "<slot> [world]", "Save a world's game state to a slot (persistence.saves-directory).", this::save);
        register("load", "<slot> [id]", "Load a save from a slot, as a new world (the slot's name by default).", this::load);
        register("saves", "", "List all saves in the save directory.", this::saves);
        refuse("loadautosave", "each world restarts from its own last checkpoint; a failed one comes back with recover <world>.");
        register("gameover", "[world]", "Force a game over: every world, or one.", this::gameover);
        register("nextmap", "[world] <map...>", "Set the map a world (or every world) plays after its next game over. Overrides shuffling.", this::nextmap);
        register("shuffle", "<world> [none|all|custom|builtin]", "Show or set how a world picks its next map.", this::shuffle);
        register("say", "<message...>", "Sends a message to every player in every world.", this::say);
        register("tile", "<world> <x> <y>", "Prints a tile: floor, overlay, block, rotation and team.", this::tile);
        register("gc", "", "Triggers a garbage collection. Testing only.", this::gc);
        register("actions", IntentConsole.ACTIONS_PARAMS, "Lists, adds or removes a world's action deny rules.", args -> out.println(IntentConsole.actions(server.host(), args)));
        register("input", IntentConsole.INPUT_PARAMS, "Shows each player's movement checks: snapshots, lag, deviation, corrections.", args -> out.println(IntentConsole.input(server.host(), args)));
        register("perf", "[mark|summary|profile]", "CPU, tick lateness and traffic since the last perf; mark starts a summary window, profile splits its tick CPU by phase.", args -> {
            String line = switch(args.length == 0 ? "" : args[0]){
                case "" -> perf.report();
                case "mark" -> perf.mark();
                case "summary" -> perf.summary();
                case "profile" -> perf.profile();
                default -> throw new IllegalArgumentException("usage: perf [mark|summary|profile]");
            };
            out.println(line);
        });
        register("yes", "", "Runs the last suggested incorrect command.", args -> {
            if(suggested == null) out.println("There is nothing to say yes to.");
            else run(suggested);
        });
        register("exit", "", "Shuts the server down and exits.", args -> {
            out.println("Shutting down server.");
            server.close();
            exit.run();
        });
    }

    void register(String name, String params, String help, Consumer<String[]> run){
        commands.put(name, new Command(params, help, run));
    }

    /** A vanilla command Shard doesn't have: it answers with the reason. */
    private void refuse(String name, String reason){
        register(name, "", "Not in Shard: " + reason, args -> out.println(name + " isn't available in Shard: " + reason));
    }

    /** Reads commands from {@code in} until it ends or the server closes. Blocks. */
    public void read(InputStream in){
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try{
            for(String line; !server.closed() && (line = reader.readLine()) != null; ) run(line);
        }catch(IOException e){
            out.println("console: stdin failed: " + e.getMessage());
        }
    }

    /** Runs one command line. */
    public void run(String line){
        String text = line.trim();
        if(text.isEmpty()) return;
        String[] words = text.split("\\s+");
        Command command = commands.get(words[0]);
        if(command == null){
            // vanilla's handleCommandString: the closest command within 2 edits
            String closest = null;
            int best = 0;
            for(String name : commands.keySet()){
                int distance = levenshtein(name, words[0]);
                if(distance < 3 && (closest == null || distance < best)){
                    best = distance;
                    closest = name;
                }
            }
            if(closest != null && !closest.equals("yes")){
                out.println("Command not found. Did you mean \"" + closest + "\"?");
                suggested = text.replace(words[0], closest);
            }else{
                out.println("Invalid command. Type 'help' for help.");
            }
            return;
        }
        if(!words[0].equals("yes")) suggested = null;
        try{
            command.run().accept(Arrays.copyOfRange(words, 1, words.length));
        }catch(RuntimeException e){
            out.println(words[0] + ": " + Server.cause(e));
        }
    }

    private void help(String[] args){
        if(args.length > 0){
            String name = args[0].toLowerCase(Locale.ROOT);
            Command command = commands.get(name);
            if(command == null){
                out.println("Command " + args[0] + " not found!");
                return;
            }
            out.println(name + ":");
            out.println(line(name, command));
            return;
        }
        out.println("Commands:");
        commands.forEach((name, command) -> out.println(line(name, command)));
    }

    private static String line(String name, Command command){
        return "  " + name + (command.params().isEmpty() ? "" : " " + command.params()) + " - " + command.help();
    }

    private void version(String[] args){
        // v160.7's version.properties: number 8, modifier release, build 160.7
        out.println("Version: Mindustry 8-release " + Admission.VERSION_TYPE + " / build " + Admission.BUILD + ".7 (Shard " + BuildInfo.id() + ")");
        out.println("Java Version: " + Runtime.version());
    }

    private void gc(String[] args){
        Runtime runtime = Runtime.getRuntime();
        long before = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        System.gc();
        long after = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        out.println((before - after) + " MB collected. Memory usage now at " + after + " MB.");
    }

    private void say(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: say <message...>");
        if(server.host().worlds().isEmpty()){
            out.println("Not hosting. Host a game first.");
            return;
        }
        String text = String.join(" ", args);
        var frame = Calls.message("[scarlet][[Server]:[] " + text);
        for(HostedWorld world : server.host().worlds()) world.outbox().offer(Audience.ALL, frame);
        out.println("Server: " + text);
    }

    /** Vanilla's {@code pause <on/off>}: in every world, or one ({@code pause <world> <on/off>}). */
    private void pause(String[] args){
        if(args.length == 0 || args.length > 2) throw new IllegalArgumentException("usage: pause [world] <on/off>");
        Host host = server.host();
        List<HostedWorld> targets = args.length == 1 ? host.worlds()
            : List.of(host.world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0])));
        if(targets.isEmpty()){
            out.println("Cannot pause without a game running.");
            return;
        }
        boolean pause = args[args.length - 1].equals("on");
        for(HostedWorld world : targets){
            ManagedWorld managed = world.managed();
            if(managed == null || !await(managed.offer(new Submission(new com.xpdustry.shard.server.game.SetPause(pause), new Cause.Admin("console"))))){
                throw new IllegalStateException(world.id() + " refused the pause");
            }
            out.println((pause ? "Game paused: " : "Game unpaused: ") + world.id() + ".");
        }
    }

    /** Vanilla's {@code gameover}: {@code GameOverEvent(waveTeam)} in a world, or in every world. */
    private void gameover(String[] args){
        Host host = server.host();
        List<HostedWorld> targets = args.length == 0 ? host.worlds()
            : List.of(host.world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0])));
        if(targets.isEmpty()){
            out.println("Not playing a map.");
            return;
        }
        for(HostedWorld world : targets){
            if(!await(server.game().force(world))) throw new IllegalStateException(world.id() + " refused the game over");
            out.println("Core destroyed: " + world.id() + ".");
        }
    }

    /**
     * Vanilla's {@code nextmap <mapname...>}: for one world ({@code nextmap <world> <map...>}), or, as vanilla's own
     * syntax reads when the first word names no world, for every world.
     */
    private void nextmap(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: nextmap [world] <map...>");
        HostedWorld only = args.length > 1 ? server.host().world(args[0]).orElse(null) : null;
        List<HostedWorld> targets = only != null ? List.of(only) : server.host().worlds();
        String name = String.join(" ", only != null ? Arrays.copyOfRange(args, 1, args.length) : args);
        MapCatalog.Entry map = server.host().maps().find(name).orElse(null);
        if(map == null){
            out.println("No map '" + name + "' found.");
            return;
        }
        for(HostedWorld world : targets){
            server.game().rotation().next(world.id(), map);
            out.println("Next map of " + world.id() + " set to '" + Names.stripColors(map.name()) + "'.");
        }
    }

    /** Vanilla's {@code shuffle [mode]}, for one world; it lasts until the server restarts ({@code game.shuffle} stays). */
    private void shuffle(String[] args){
        if(args.length == 0) throw new IllegalArgumentException("usage: shuffle <world> [none|all|custom|builtin]");
        HostedWorld world = server.host().world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0]));
        if(args.length == 1){
            out.println("Shuffle mode of " + world.id() + " currently set to '" + server.game().rotation().shuffle(world.id()).name().toLowerCase(Locale.ROOT) + "'.");
            return;
        }
        ConfigKeys.Shuffle mode;
        try{
            mode = ConfigKeys.Shuffle.valueOf(args[1].toUpperCase(Locale.ROOT));
        }catch(IllegalArgumentException e){
            out.println("Invalid shuffle mode.");
            return;
        }
        server.game().rotation().shuffle(world.id(), mode);
        out.println("Shuffle mode of " + world.id() + " set to '" + args[1].toLowerCase(Locale.ROOT) + "'.");
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

    private void status(String[] args){
        List<HostedWorld> worlds = server.host().worlds();
        out.println("Status: " + worlds.size() + " worlds, " + server.host().players() + " players, " + server.sessions().size() + " sessions, "
            + server.transport().connections() + " connections, " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 << 20)
            + " MB used");
        for(HostedWorld world : worlds){
            ManagedWorld managed = world.managed();
            WorldStatus status = managed == null ? null : managed.status();
            out.println("  " + world.describe() + (status == null || status.stats().isEmpty() ? "" : " [" + status.stats() + "]")
                + (status == null || status.error() == null ? "" : " error: " + status.error()));
        }
    }

    private void players(String[] args){
        String only = args.length > 0 ? args[0] : null;
        int count = 0;
        for(Session session : server.sessions().all()){
            String world = session.world() == null ? "-" : session.world().name();
            if(only != null && !only.equals(world)) continue;
            Identity identity = session.identity();
            out.println("  #" + session.id() + " " + identity.plainName() + " uuid=" + identity.uuid() + " world=" + world + " state="
                + session.state().name().toLowerCase(Locale.ROOT) + (session.admin() ? " admin" : ""));
            count++;
        }
        out.println(count == 0 ? "No players connected." : "Players: " + count);
    }

    /** Vanilla's {@code maps [all/custom/default]}: custom maps unless asked otherwise. */
    private void maps(String[] args){
        String which = args.length == 0 ? "custom" : args[0];
        boolean custom = which.equals("custom") || which.equals("all"), builtIn = which.equals("default") || which.equals("all");
        var maps = server.host().maps().all().stream().filter(map -> map.custom() ? custom : builtIn).toList();
        if(maps.isEmpty()){
            out.println(server.host().maps().all().isEmpty() ? "No maps found." : "No custom maps loaded. To display built-in maps, use the \"all\" argument.");
            return;
        }
        out.println("Maps:");
        for(MapCatalog.Entry map : maps){
            out.println("  " + map.file() + ": " + map.name() + (map.custom() ? " (custom)" : ""));
        }
        out.println("Maps: " + maps.size());
    }

    private void host(String[] args){
        Host host = server.host();
        String entry;
        if(args.length == 0){
            var free = host.maps().all().stream().filter(map -> host.world(MapCatalog.defaultId(map)).isEmpty()).toList();
            if(free.isEmpty()) throw new IllegalArgumentException("no map left to host; name one");
            MapCatalog.Entry map = free.get(new Random().nextInt(free.size()));
            out.println("Randomized next map to be " + map.name() + ".");
            entry = map.file();
        }else{
            entry = (args.length > 2 ? args[2] + "=" : "") + args[0] + (args.length > 1 ? " " + args[1] : "");
        }
        Host.Spec spec = host.spec(entry);
        out.println("Loading map...");
        HostedWorld world = await(host.host(spec));
        out.println("Map loaded: " + world.describe());
    }

    private void stop(String[] args){
        Host host = server.host();
        List<HostedWorld> targets = args.length == 0 ? host.worlds()
            : List.of(host.world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0])));
        if(targets.isEmpty()){
            out.println("Not hosting. Host a game first.");
            return;
        }
        for(HostedWorld world : targets){
            await(host.stop(world.id(), KickReason.SERVER_CLOSE));
            out.println("Stopped " + world.id() + ".");
        }
    }

    /** Vanilla's {@code save <slot>}: one world, named unless it is the only one; vanilla's {@code embedAssets} has no use. */
    private void save(String[] args){
        if(args.length == 0 || args.length > 2) throw new IllegalArgumentException("usage: save <slot> [world]");
        List<HostedWorld> worlds = server.host().worlds();
        HostedWorld world;
        if(args.length == 2) world = server.host().world(args[1]).orElseThrow(() -> new IllegalArgumentException("no world " + args[1]));
        else if(worlds.isEmpty()){
            out.println("Not hosting. Host a game first.");
            return;
        }else if(worlds.size() > 1) throw new IllegalArgumentException("several worlds are hosted: save <slot> <world>");
        else world = worlds.getFirst();
        java.nio.file.Path file = await(saves.save(world, args[0]));
        out.println("Saved " + world.id() + " to " + file + ".");
    }

    /** Vanilla's {@code load <slot>}: the save hosted as a new world, which must not be hosted already. */
    private void load(String[] args){
        if(args.length == 0 || args.length > 2) throw new IllegalArgumentException("usage: load <slot> [id]");
        HostedWorld world = await(saves.load(args[0], args.length > 1 ? args[1] : null));
        out.println("Save loaded: " + world.describe());
    }

    /** Vanilla's {@code saves}. */
    private void saves(String[] args){
        out.println("Save files: ");
        try{
            for(String slot : saves.list()) out.println("| " + slot);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private void recover(String[] args){
        if(args.length != 1) throw new IllegalArgumentException("usage: recover <world>");
        HostedWorld world = server.host().world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0]));
        ManagedWorld managed = world.managed();
        if(managed == null || managed.lifecycle() != Lifecycle.FAILED) throw new IllegalStateException(args[0] + " hasn't failed");
        await(managed.recover());
        out.println("Recovered " + world.describe() + ".");
    }

    /** Vanilla's {@code runwave}: {@code logic.runWave()}, through each world's inbox. */
    private void runwave(String[] args){
        List<HostedWorld> targets = targets(args.length == 0 ? null : args[0]);
        if(targets.isEmpty()){
            out.println("Not hosting. Host a game first.");
            return;
        }
        for(HostedWorld world : targets){
            submit(world, new Waves.SkipWave());
            out.println("Wave spawned" + (targets.size() > 1 || args.length > 0 ? " in " + world.id() : "") + ".");
        }
    }

    /** Vanilla's {@code fillitems [team]}: {@code fillitems [world] [team]}, every world when the first word names none. */
    private void fillitems(String[] args){
        HostedWorld only = args.length > 0 ? server.host().world(args[0]).orElse(null) : null;
        List<HostedWorld> targets = only != null ? List.of(only) : server.host().worlds();
        String[] rest = only != null ? Arrays.copyOfRange(args, 1, args.length) : args;
        if(targets.isEmpty()){
            out.println("Not playing. Host first.");
            return;
        }
        Team team = rest.length == 0 ? Team.get(Team.SHARDED) : Team.values().stream().filter(t -> t.name().equals(rest[0])).findFirst().orElse(null);
        if(team == null){
            out.println("No team with that name found.");
            return;
        }
        for(HostedWorld world : targets){
            boolean cores = await(world.inspect(w -> Teams.of(w) != null && !Teams.of(w).cores(team.id()).isEmpty()));
            if(!cores){
                out.println("That team has no cores.");
                continue;
            }
            submit(world, new FillItems(team.id()));
            out.println("Core filled.");
        }
    }

    /** Vanilla's {@code config}, over {@code shard.yml}: read any setting by its vanilla or Shard name; {@code reload} applies the file. */
    private void config(String[] args){
        Config current = server.config().current();
        if(args.length == 0){
            out.println("All config values:");
            for(VanillaKeys vanilla : VanillaKeys.ALL){
                if(vanilla.isDropped()){
                    out.println("| " + vanilla.name() + ": dropped: " + vanilla.note());
                }else{
                    out.println("| " + vanilla.name() + " (" + vanilla.key().path() + "): " + vanilla.key().display(current.get(vanilla.key())));
                    out.println("| | " + vanilla.key().description() + (vanilla.note().isEmpty() ? "" : " (" + vanilla.note() + ")"));
                }
                out.println("|");
            }
            return;
        }
        if(args[0].equals("reload")){
            ConfigService.Reloaded reloaded;
            try{
                reloaded = server.config().reload();
            }catch(ConfigException e){
                out.println("The config has errors; nothing changed: " + e.getMessage());
                return;
            }
            out.println(reloaded.applied().isEmpty() ? "Config reloaded: nothing changed." : "Config reloaded: " + reloaded.applied().size() + " changes applied.");
            for(var change : reloaded.applied()) out.println("  " + change);
            for(var change : reloaded.pending()) out.println("  after a restart: " + change);
            return;
        }
        String name = args[0];
        VanillaKeys vanilla = VanillaKeys.ALL.stream().filter(k -> k.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        ConfigKey<?> key = vanilla != null ? vanilla.key() : ConfigKeys.ALL.stream().filter(k -> k.path().equals(name)).findFirst().orElse(null);
        if(vanilla != null && vanilla.isDropped()){
            out.println("'" + vanilla.name() + "' is dropped in Shard: " + vanilla.note() + ".");
            return;
        }
        if(key == null){
            out.println("Unknown config: '" + name + "'. Run the command with no arguments to get a list of valid configs.");
            return;
        }
        if(args.length == 1){
            out.println("'" + name + "' is currently " + key.display(current.get(key)) + ".");
            return;
        }
        out.println("Shard reads its settings from shard.yml: set " + key.path() + " there (" + key.env() + " in the environment), then run 'config reload'.");
    }

    private void playerlimit(String[] args){
        if(args.length > 0){
            out.println("Set net.player-limit in shard.yml (0 is off), then run 'config reload'.");
            return;
        }
        int limit = server.config().current().get(ConfigKeys.NET_PLAYER_LIMIT);
        out.println("Player limit is currently " + (limit == 0 ? "off" : limit) + ".");
    }

    /** One world by id, or every world. */
    private List<HostedWorld> targets(String id){
        if(id == null) return server.host().worlds();
        return List.of(server.host().world(id).orElseThrow(() -> new IllegalArgumentException("no world " + id)));
    }

    /** Offers an admin command to a world's inbox, which applies it at the next tick's admission. */
    private static void submit(HostedWorld world, com.xpdustry.shard.sim.world.Command command){
        if(world.managed() == null) throw new IllegalStateException(world.id() + " is still loading");
        if(!await(world.managed().offer(new Submission(command, new Cause.Admin(AdminCommands.ACTOR))))) throw new IllegalStateException(world.id() + " refused the command");
    }

    /** A live session by id ({@code #3} or {@code 3}), by its name as sent, or by its plain name ignoring case. */
    static Session find(Server server, String query){
        String id = query.startsWith("#") ? query.substring(1) : query;
        for(Session session : server.sessions().all()){
            if(session.state() != SessionState.CLOSED && String.valueOf(session.id()).equals(id)) return session;
        }
        return findByName(server, query);
    }

    /** A live session by its name as sent or its plain name, ignoring case (vanilla's {@code name().equalsIgnoreCase}). */
    static Session findByName(Server server, String name){
        for(Session session : server.sessions().all()){
            if(session.state() == SessionState.CLOSED) continue;
            if(session.identity().name().equalsIgnoreCase(name) || session.identity().plainName().equalsIgnoreCase(name)) return session;
        }
        return null;
    }

    /** The address a session is connected from, canonical. */
    static String address(Session session){
        return Addresses.canonical(session.conn().remote().getAddress().getHostAddress());
    }

    private void tile(String[] args){
        if(args.length != 3) throw new IllegalArgumentException("usage: tile <world> <x> <y>");
        HostedWorld world = server.host().world(args[0]).orElseThrow(() -> new IllegalArgumentException("no world " + args[0]));
        int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
        String line = await(world.inspect(w -> describeTile(w, x, y)));
        out.println(line);
    }

    /** {@code tile <world> <x> <y>}'s line: what the rig compares with the client's view. */
    static String describeTile(World world, int x, int y){
        var tiles = world.tiles();
        if(x < 0 || y < 0 || x >= tiles.width() || y >= tiles.height()){
            throw new IllegalArgumentException("(" + x + ", " + y + ") is outside the " + tiles.width() + "x" + tiles.height() + " map");
        }
        int pos = tiles.pos(x, y);
        Building building = world.buildings().at(pos);
        var content = world.content();
        return "tile " + x + "," + y + " floor=" + content.block(tiles.floor(pos)).name() + " overlay=" + content.block(tiles.overlay(pos)).name()
            + " block=" + content.block(tiles.block(pos)).name() + " rotation=" + (building == null ? 0 : building.rotation)
            + " team=" + (building == null ? tiles.team(pos) : building.team)
            + (building == null || building.tile() == pos ? "" : " center=" + tiles.x(building.tile()) + "," + tiles.y(building.tile()));
    }

    static <T> T await(CompletableFuture<T> future){
        try{
            return future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }catch(ExecutionException e){
            throw new IllegalStateException(Server.cause(e.getCause()), e.getCause());
        }catch(TimeoutException e){
            throw new IllegalStateException("no answer within " + WAIT.toSeconds() + " s");
        }
    }
}
