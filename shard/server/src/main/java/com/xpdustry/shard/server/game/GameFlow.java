package com.xpdustry.shard.server.game;

import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Vanilla's {@code ServerControl.gameOverListener} (ServerControl.java:106-131) and {@code play} (1369-1404), per world.
 * On a world's {@link GameEvent.GameOver}, once the tick's Calls (its {@code updateGameOver} among them) are out:
 * <ul>
 *   <li>with a next map from the {@link Rotation}: everyone in the world gets the {@code infoMessage} naming it and
 *   the wait, and {@code game.round-extra-time} later the world is reloaded onto it ({@link Host#replace}), every
 *   player moved over their connection;</li>
 *   <li>without one: the world's players are kicked with {@code gameover} and the world stops, as vanilla's server
 *   kicks everyone and closes.</li>
 * </ul>
 * A game over while a reload waits is ignored ({@code inGameOverWait}), unless the console forced it
 * ({@link #force}), which restarts the wait as vanilla's {@code play} cancels the last task. A world's {@linkplain
 * Host#stop stop} or replacement drops its reload in waiting, and {@link #close} every one: none starts after them.
 */
public final class GameFlow implements HostListener, AutoCloseable{
    private static final Logger LOG = LoggerFactory.getLogger(GameFlow.class);
    /** How long {@link #close} waits for the reloads and stops already under way. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(30);

    private final Host host;
    private final Rotation rotation;
    /** Each world's reload in waiting, which a newer game over replaces. */
    private final ConcurrentHashMap<WorldId, Wait> waiting = new ConcurrentHashMap<>();
    private final Set<HostedWorld> forced = ConcurrentHashMap.newKeySet();
    /** The reloads and stops under way, which {@link #close} waits for. */
    private final Set<CompletableFuture<?>> running = ConcurrentHashMap.newKeySet();
    /** Written under the lock, which {@link #run} holds while it starts a reload or a stop. */
    private volatile boolean closed;
    /** Runs a task after a delay; tests run it at once. */
    private static final BiFunction<Duration, Runnable, ?> AFTER = (delay, task) ->
        CompletableFuture.runAsync(task, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    private volatile BiFunction<Duration, Runnable, ?> after = AFTER;

    public GameFlow(Host host, Rotation rotation){
        this.host = host;
        this.rotation = rotation;
        host.listen(this);
        host.listen(new AutoPauses(host));
    }

    public Rotation rotation(){
        return rotation;
    }

    /**
     * The console's {@code gameover}: ends {@code world}'s game now ({@link ForceGameOver}), even while a reload waits.
     *
     * @return whether the world's inbox took the command
     */
    public CompletableFuture<Boolean> force(HostedWorld world){
        ManagedWorld managed = world.managed();
        if(managed == null) return CompletableFuture.failedFuture(new IllegalStateException(world.id() + " is still loading"));
        forced.add(world);
        return managed.offer(new Submission(new ForceGameOver(), new Cause.Admin("console")));
    }

    /** Whether {@code world} waits to load its next map. */
    public boolean waiting(WorldId world){
        return waiting.containsKey(world);
    }

    @Override
    public void event(HostedWorld world, World state, WorldEvent event){
        if(!(event instanceof GameEvent.GameOver over) || closed) return;
        boolean force = forced.remove(world);
        if(waiting.containsKey(world.id()) && !force) return;
        Rules rules = state.part(WorldRules.class).rules;
        WaveState waves = WaveState.of(state);
        Players players = Players.of(state);
        int online = players == null ? 0 : players.list().size();
        String mapName = capitalize(Names.stripColors(world.map().name()));
        if(rules.waves){
            LOG.info("{}: Game over! Reached wave {} with {} players online on map {}.", world.id(), waves == null ? 0 : waves.wave, online, mapName);
        }else{
            LOG.info("{}: Game over! Team {} is victorious with {} players online on map {}.", world.id(), Team.get(over.winner()).name(), online, mapName);
        }
        MapCatalog.Entry next = rotation.next(world);
        if(next == null){
            waiting.remove(world.id());
            // off the tick thread: stopping waits for this world's final checkpoint
            CompletableFuture.runAsync(() -> run(() -> host.stop(world.id(), KickReason.GAMEOVER)));
            return;
        }
        Duration wait = host.config(world.id()).get(ConfigKeys.GAME_ROUND_EXTRA_TIME);
        world.outbox().send(Audience.ALL, Calls.infoMessage(message(rules.pvp, over.winner(), next, wait)));
        LOG.info("{}: Selected next map to be {}.", world.id(), Names.stripColors(next.name()));
        var token = new Wait(world);
        waiting.put(world.id(), token);
        after.apply(wait, () -> reload(world, next, token));
    }

    /** A reload in waiting for {@code world}, then under way. */
    private static final class Wait{
        final HostedWorld world;
        volatile boolean started;

        Wait(HostedWorld world){
            this.world = world;
        }
    }

    private void reload(HostedWorld world, MapCatalog.Entry next, Wait token){
        CompletableFuture<?> loading = run(() -> {
            if(waiting.get(world.id()) != token) return null;
            token.started = true;
            return host.replace(world, next, world.mode());
        });
        if(loading == null) return;
        // where the world's new game starts: the map its rotation drew, from the seed the server logged at start
        LOG.info("{}: reloading onto {}, the rotation's draw (seed {})", world.id(), Names.stripColors(next.name()), rotation.seed());
        loading.whenComplete((hosted, failure) -> {
            waiting.remove(world.id(), token);
            if(failure != null) LOG.error("{}: loading the next map {} failed", world.id(), next.name(), failure);
        });
    }

    /** Starts a reload or a stop unless closed, and keeps it for {@link #close}; null if none started. */
    private synchronized CompletableFuture<?> run(Supplier<CompletableFuture<?>> task){
        if(closed) return null;
        CompletableFuture<?> started = task.get();
        if(started == null) return null;
        running.add(started);
        started.whenComplete((ignored, failure) -> running.remove(started));
        return started;
    }

    /** A world that retired while its reload waited, stopped or replaced by someone else, is reloaded by no one. */
    @Override
    public void retired(HostedWorld world){
        waiting.computeIfPresent(world.id(), (id, wait) -> wait.world == world && !wait.started ? null : wait);
        forced.remove(world);
    }

    /**
     * The server closes: no game over is handled from now on, no reload in waiting starts, and the reloads and stops
     * under way finish (up to {@link #CLOSE_WAIT}) before the worlds hibernate.
     */
    @Override
    public void close(){
        List<CompletableFuture<?>> under;
        synchronized(this){
            if(closed) return;
            closed = true;
            waiting.clear();
            forced.clear();
            under = List.copyOf(running);
        }
        if(under.isEmpty()) return;
        try{
            CompletableFuture.allOf(under.toArray(CompletableFuture[]::new)).exceptionally(failure -> null).get(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }catch(ExecutionException | TimeoutException e){
            LOG.warn("{} map reloads or world stops still running at close", under.size());
        }
    }

    /** The {@code infoMessage} of a game over with a next map, vanilla's text. */
    static String message(boolean pvp, int winner, MapCatalog.Entry map, Duration wait){
        return (pvp ? "[accent]The " + coloredName(winner) + " team is victorious![]\n" : "[scarlet]Game over![]\n")
            + "\nNext selected map: [accent]" + map.name() + "[white]"
            + (map.author().isEmpty() ? "" : " by[accent] " + map.author() + "[white]") + "."
            + "\nNew game begins in " + wait.toSeconds() + " seconds.";
    }

    /** {@code Team.coloredName()} on a headless server: no emoji, and the name as its own bundle key's fallback. */
    static String coloredName(int team){
        Team t = Team.get(team);
        return "[#" + String.format(Locale.ROOT, "%08x", t.color()) + "]" + t.name() + "[]";
    }

    /** Arc's {@code Strings.capitalize}: {@code _} and {@code -} become spaces, and the letter after one is upper case. */
    static String capitalize(String s){
        var result = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            if(c == '_' || c == '-') result.append(' ');
            else if(i == 0 || s.charAt(i - 1) == '_' || s.charAt(i - 1) == '-') result.append(Character.toUpperCase(c));
            else result.append(c);
        }
        return result.toString();
    }

    /** Runs delayed tasks with {@code after} instead, null for the timer; tests hold the wait. */
    void after(BiFunction<Duration, Runnable, ?> after){
        this.after = after == null ? AFTER : after;
    }
}
