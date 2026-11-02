package com.xpdustry.shard.server.game;

import arc.Events;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import mindustry.game.EventType.GameOverEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.state;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP48): {@code checkGameState} in every gamemode against vanilla's. Each case hosts a default map in a mode on
 * both sides (vanilla's {@code host <map> <mode>}, Shard's hosted world with {@code MapLoader.play}), destroys one
 * team's cores or reaches the win wave, and compares, frame by frame for {@value #FRAMES} frames, whether the game
 * is over and every {@code GameOverEvent}'s winner: survival lost on the default team's cores or won at the win wave,
 * attack won on the enemy's cores and lost on one's own, PvP down to its last team, a sandbox that only its cores'
 * loss ends, and the editor, which never ends.
 */
class GameOverOracleTest{
    static final int FRAMES = 120;
    static final Epoch EPOCH = Epoch.vanilla();
    static final List<Integer> VANILLA_WINNERS = new ArrayList<>();

    @BeforeAll
    static void boot(){
        Vanilla.boot();
        Events.on(GameOverEvent.class, event -> VANILLA_WINNERS.add(event.winner.id));
    }

    /** What happens: -1 kills nobody, -2 sets the win wave to the current wave, otherwise the team whose cores die. */
    static List<Arguments> cases(){
        return List.of(
            Arguments.of("fortress", "survival", Team.SHARDED, Team.CRUX),
            Arguments.of("fortress", "survival", -1, -1),
            Arguments.of("fortress", "survival", -2, Team.SHARDED),
            Arguments.of("veins", "attack", Team.CRUX, Team.SHARDED),
            Arguments.of("veins", "attack", Team.SHARDED, Team.CRUX),
            Arguments.of("glacier", "pvp", Team.CRUX, Team.SHARDED),
            Arguments.of("glacier", "pvp", Team.SHARDED, Team.CRUX),
            Arguments.of("passage", "pvp", -1, -1),
            Arguments.of("maze", "sandbox", -1, -1),
            Arguments.of("maze", "sandbox", Team.SHARDED, Team.CRUX),
            Arguments.of("fortress", "editor", Team.SHARDED, -1));
    }

    @ParameterizedTest(name = "{0} {1}: {2} -> {3}")
    @MethodSource("cases")
    void theGameEndsAsVanillas(String map, String mode, int action, int winner) throws IOException{
        // vanilla
        Vanilla.host(map, mindustry.game.Gamemode.valueOf(mode));
        VANILLA_WINNERS.clear();
        if(action >= 0){
            for(var core : state.teams.cores(mindustry.game.Team.get(action)).copy()) core.kill();
        }else if(action == -2){
            state.rules.winWave = state.wave;
        }
        var vanilla = new ArrayList<String>();
        for(int frame = 0; frame < FRAMES; frame++){
            Vanilla.frame();
            vanilla.add(frame + ": over " + state.gameOver + " winners " + VANILLA_WINNERS);
        }

        // Shard, as the host loads a map
        World world = host(map, Gamemode.valueOf(mode.toUpperCase(Locale.ROOT)));
        Observer events = world.observe(64, List.of(GameEvent.class), null);
        var winners = new ArrayList<Integer>();
        if(action >= 0){
            for(Building core : List.copyOf(Teams.of(world).cores(action))) core.kill();
        }else if(action == -2){
            world.part(WorldRules.class).rules.winWave = WaveState.of(world).wave;
        }
        var shard = new ArrayList<String>();
        for(int frame = 0; frame < FRAMES; frame++){
            world.tick();
            for(WorldEvent event : events.drain()) if(event instanceof GameEvent.GameOver over) winners.add(over.winner());
            shard.add(frame + ": over " + WaveState.of(world).gameOver + " winners " + winners);
        }

        assertEquals(String.join("\n", vanilla), String.join("\n", shard), map + " " + mode);
        // the case did what it says
        assertEquals(winner < 0 ? List.of() : List.of(winner), winners);
    }

    /** {@code Host.load} for a default map in {@code mode}. */
    static World host(String map, Gamemode mode) throws IOException{
        byte[] bytes;
        try(InputStream in = GameOverOracleTest.class.getClassLoader().getResourceAsStream("maps/default/" + map + ".msav")){
            bytes = Objects.requireNonNull(in, map).readAllBytes();
        }
        SaveFile save = new MsavReader(EPOCH).read(bytes);
        var loader = new MapLoader(EPOCH, BuildingCodecs.builtIn());
        Rules rules = loader.hostRules(save, mode);
        WorldConfig template = HostedSystems.template(EPOCH, 3, 4096);
        var config = new WorldConfig(template.content(), save.map().width(), save.map().height(), 3, template.systems(), template.kinds(),
            template.inboxCapacity(), template.journal());
        MapLoader.Loaded loaded = loader.loadMap(save, config, rules);
        MapLoader.play(loaded);
        return loaded.world();
    }
}
