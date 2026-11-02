package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The console's {@code actions} and {@code input} commands: an admin's deny rules on a world, as logged admin
 * commands, and what admission saw of each player's input, which the walk gate (WP26) reads.
 */
public final class IntentConsole{
    public static final String ACTIONS_PARAMS = "<world> [list | deny <name> <type,...|all> [message...] | allow <name>]";
    public static final String INPUT_PARAMS = "<world>";

    private IntentConsole(){
    }

    /** {@code actions}: list, add or remove a world's deny rules. The result is one line per rule, or what changed. */
    public static String actions(Host host, String[] args){
        if(args.length < 1) throw new IllegalArgumentException("usage: actions " + ACTIONS_PARAMS);
        HostedWorld world = world(host, args[0]);
        String verb = args.length < 2 ? "list" : args[1];
        Cause cause = new Cause.Admin("console");
        return switch(verb){
            case "list" -> await(world.inspect(w -> {
                ActionRules rules = ActionRules.of(w);
                if(rules == null || rules.rules().isEmpty()) return "actions " + world.id() + ": no rules";
                var out = new StringJoiner("\n");
                for(ActionRules.Rule rule : rules.rules().values()) out.add("rule " + rule.name() + " denies " + names(rule.types()) + " message=\"" + rule.message() + "\"");
                return out.toString();
            }));
            case "deny" -> {
                if(args.length < 4) throw new IllegalArgumentException("usage: actions <world> deny <name> <type,...|all> [message...]");
                var types = new ArrayList<ActionType>();
                for(String name : args[3].split(",")){
                    if(name.equals("all")){
                        types.addAll(List.of(ActionType.values()));
                        continue;
                    }
                    ActionType type = ActionType.named(name);
                    if(type == null) throw new IllegalArgumentException("no action type " + name + "; vanilla's are " + names(List.of(ActionType.values())));
                    types.add(type);
                }
                String message = String.join(" ", Arrays.asList(args).subList(4, args.length));
                var rule = ActionRules.Rule.deny(args[2], message, types.toArray(ActionType[]::new));
                submit(world, new ActionRules.Put(rule), cause);
                yield "actions " + world.id() + ": rule " + rule.name() + " denies " + names(rule.types());
            }
            case "allow" -> {
                if(args.length != 3) throw new IllegalArgumentException("usage: actions <world> allow <name>");
                submit(world, new ActionRules.Remove(args[2]), cause);
                yield "actions " + world.id() + ": rule " + args[2] + " removed";
            }
            default -> throw new IllegalArgumentException("usage: actions " + ACTIONS_PARAMS);
        };
    }

    /**
     * {@code input}: each player's applied snapshots, the farthest its unit was from the client's report before and
     * after applying it (in tiles), the moves cut to the unit's speed, and the {@code setPosition} corrections sent.
     */
    public static String input(Host host, String[] args){
        if(args.length != 1) throw new IllegalArgumentException("usage: input " + INPUT_PARAMS);
        HostedWorld world = world(host, args[0]);
        return await(world.inspect(w -> {
            Roster roster = Roster.of(w);
            var out = new StringJoiner("\n");
            out.add("input " + world.id() + ": " + roster.size() + " players");
            for(Player player : roster.players().values()){
                PlayerInput input = player.attachment(PlayerInput.KEY);
                if(input == null){
                    out.add("player " + player.name + " snapshots=0");
                    continue;
                }
                out.add(String.format(Locale.ROOT, "player %s snapshots=%d lag=%.2f deviation=%.2f clamped=%d corrections=%d", player.name,
                    input.snapshots, input.maxLag / 8f, input.maxDeviation / 8f, input.clamped, input.corrections));
            }
            return out.toString();
        }));
    }

    private static HostedWorld world(Host host, String name){
        return host.world(name).orElseThrow(() -> new IllegalArgumentException("no world " + name));
    }

    private static void submit(HostedWorld world, Command command, Cause cause){
        if(world.managed() == null) throw new IllegalStateException(world.id() + " is still loading");
        if(!await(world.managed().offer(new Submission(command, cause)))) throw new IllegalStateException(world.id() + " refused the command");
    }

    private static String names(List<ActionType> types){
        var out = new StringJoiner(",");
        for(ActionType type : types) out.add(type.vanillaName());
        return out.toString();
    }

    private static <T> T await(CompletableFuture<T> future){
        try{
            return future.get(60, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }catch(ExecutionException e){
            throw new IllegalStateException(String.valueOf(e.getCause().getMessage()), e.getCause());
        }catch(TimeoutException e){
            throw new IllegalStateException("the world didn't answer in 60 s");
        }
    }
}
