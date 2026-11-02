package com.xpdustry.shard.server.chat;

import java.util.*;

/**
 * Arc's {@code CommandHandler} (arc-core {@code arc/util/CommandHandler.java}), rule for rule: a prefix, commands by
 * name in registration order, and the same parse of a line into arguments with the same {@link ResponseType}s. A
 * parameter list is {@code <required>} names before {@code [optional]} ones, and the last may be variadic
 * ({@code <message...>}), taking the rest of the line unsplit. Registering a name again replaces the command and moves
 * it to the end of the list, as Arc does.
 *
 * <p>Thread-safe: registration swaps an immutable snapshot that handling reads, so plugins may register from any
 * thread while worlds run commands.
 *
 * @param <T> what each run gets besides its arguments (vanilla's {@code Player})
 */
public final class CommandHandler<T>{
    private final String prefix;
    private volatile Snapshot<T> snapshot = new Snapshot<>(Map.of(), List.of());

    private record Snapshot<T>(Map<String, Command<T>> byName, List<Command<T>> ordered){
    }

    public CommandHandler(String prefix){
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    public String prefix(){
        return prefix;
    }

    /** What a command does with its arguments. */
    @FunctionalInterface
    public interface Runner<T>{
        void accept(String[] args, T parameter);
    }

    /** {@code CommandHandler.CommandParam}. */
    public record Param(String name, boolean optional, boolean variadic){
    }

    /** {@code CommandHandler.Command}: its name ({@code text}), its parameter text and description, and its runner. */
    public record Command<T>(String text, String paramText, String description, List<Param> params, Runner<T> runner){
        public Command{
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(paramText, "paramText");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(runner, "runner");
            params = List.copyOf(params);
        }
    }

    /** {@code CommandHandler.ResponseType}. */
    public enum ResponseType{
        noCommand, unknownCommand, fewArguments, manyArguments, valid
    }

    /**
     * {@code CommandHandler.CommandResponse}.
     *
     * @param command the command, for {@code fewArguments}, {@code manyArguments} and {@code valid}
     * @param runCommand the name the line asked for, or null for {@code noCommand}
     */
    public record Response<T>(ResponseType type, Command<T> command, String runCommand){
    }

    /**
     * Registers {@code text} with Arc's parameter syntax.
     *
     * @throws IllegalArgumentException for Arc's malformed parameter texts
     */
    public synchronized Command<T> register(String text, String paramText, String description, Runner<T> runner){
        var command = new Command<>(text, paramText, description, params(paramText), runner);
        var byName = new HashMap<>(snapshot.byName());
        var ordered = new ArrayList<>(snapshot.ordered());
        ordered.removeIf(c -> c.text().equals(text));
        byName.put(text, command);
        ordered.add(command);
        snapshot = new Snapshot<>(Map.copyOf(byName), List.copyOf(ordered));
        return command;
    }

    public synchronized void remove(String text){
        if(!snapshot.byName().containsKey(text)) return;
        var byName = new HashMap<>(snapshot.byName());
        var ordered = new ArrayList<>(snapshot.ordered());
        byName.remove(text);
        ordered.removeIf(c -> c.text().equals(text));
        snapshot = new Snapshot<>(Map.copyOf(byName), List.copyOf(ordered));
    }

    /** {@code getCommandList()}: in registration order. */
    public List<Command<T>> commands(){
        return snapshot.ordered();
    }

    public Command<T> command(String text){
        return snapshot.byName().get(text);
    }

    /** {@code handleMessage(message, params)}: parses the line and, if it is valid, runs its command. */
    public Response<T> handle(String message, T parameter){
        Parsed<T> parsed = parse(message);
        if(parsed.response().type() == ResponseType.valid) parsed.response().command().runner().accept(parsed.args(), parameter);
        return parsed.response();
    }

    /** A line's response and, when valid, its arguments: {@link #handle} without running anything. */
    public record Parsed<T>(Response<T> response, String[] args){
    }

    public Parsed<T> parse(String message){
        if(message == null || !message.startsWith(prefix)) return new Parsed<>(new Response<>(ResponseType.noCommand, null, null), null);
        message = message.substring(prefix.length());
        String commandstr = message.contains(" ") ? message.substring(0, message.indexOf(" ")) : message;
        String argstr = message.contains(" ") ? message.substring(commandstr.length() + 1) : "";
        var result = new ArrayList<String>();
        Command<T> command = snapshot.byName().get(commandstr);
        if(command == null) return new Parsed<>(new Response<>(ResponseType.unknownCommand, null, commandstr), null);
        List<Param> params = command.params();
        int index = 0;
        boolean satisfied = false;
        while(true){
            if(index >= params.size() && !argstr.isEmpty()){
                return new Parsed<>(new Response<>(ResponseType.manyArguments, command, commandstr), null);
            }else if(argstr.isEmpty()) break;
            if(params.get(index).optional() || index >= params.size() - 1 || params.get(index + 1).optional()){
                satisfied = true;
            }
            if(params.get(index).variadic()){
                result.add(argstr);
                break;
            }
            int next = argstr.indexOf(" ");
            if(next == -1){
                if(!satisfied) return new Parsed<>(new Response<>(ResponseType.fewArguments, command, commandstr), null);
                result.add(argstr);
                break;
            }else{
                String arg = argstr.substring(0, next);
                argstr = argstr.substring(arg.length() + 1);
                result.add(arg);
            }
            index++;
        }
        if(!satisfied && !params.isEmpty() && !params.getFirst().optional()){
            return new Parsed<>(new Response<>(ResponseType.fewArguments, command, commandstr), null);
        }
        return new Parsed<>(new Response<>(ResponseType.valid, command, commandstr), result.toArray(String[]::new));
    }

    /** {@code new Command(...)}'s parameter parse, with its exceptions. */
    static List<Param> params(String paramText){
        if(paramText.isEmpty()) return List.of();
        String[] psplit = paramText.split(" ");
        var params = new ArrayList<Param>(psplit.length);
        boolean hadOptional = false;
        for(int i = 0; i < psplit.length; i++){
            String param = psplit[i];
            if(param.length() <= 2) throw new IllegalArgumentException("Malformed param '" + param + "'");
            char l = param.charAt(0), r = param.charAt(param.length() - 1);
            boolean optional, variadic = false;
            if(l == '<' && r == '>'){
                if(hadOptional) throw new IllegalArgumentException("Can't have non-optional param after optional param!");
                optional = false;
            }else if(l == '[' && r == ']'){
                optional = true;
            }else{
                throw new IllegalArgumentException("Malformed param '" + param + "'");
            }
            if(optional) hadOptional = true;
            String fname = param.substring(1, param.length() - 1);
            if(fname.endsWith("...")){
                if(i != psplit.length - 1) throw new IllegalArgumentException("A variadic parameter should be the last parameter!");
                fname = fname.substring(0, fname.length() - 3);
                variadic = true;
            }
            params.add(new Param(fname, optional, variadic));
        }
        return params;
    }
}
