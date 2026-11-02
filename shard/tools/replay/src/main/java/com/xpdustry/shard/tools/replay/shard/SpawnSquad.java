package com.xpdustry.shard.tools.replay.shard;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Spawns {@code count} flying units by the core at {@code core}, flying the {@link Skirmish} script against the
 * nearest other team's core. A command, so it enters through the inbox and the {@link CommandLog}, and replaying the
 * log from a checkpoint spawns the same squads (the choices draw from the world's {@link Rand}).
 */
public record SpawnSquad(int core, int count) implements Command.Script{
    static final String[] TYPES = {"flare", "horizon", "zenith", "flare", "poly", "mega"};

    @Override
    public Verdict apply(World world, Cause cause){
        Building home = world.buildings().at(core);
        if(home == null) return Verdict.rejected("no core at " + core);
        Building[] enemy = {null};
        world.buildings().each(build -> {
            if(build.team != home.team && build.block().name().startsWith("core-")
                && (enemy[0] == null || dst(build, home) < dst(enemy[0], home))) enemy[0] = build;
        });
        if(enemy[0] == null) return Verdict.rejected("no enemy core");
        Rand rand = world.rand();
        ContentEpoch content = world.content();
        for(int i = 0; i < count; i++){
            UnitDef type = content.unit(content.id(ContentType.UNIT, TYPES[rand.random(0, TYPES.length - 1)]));
            float angle = rand.random(360f);
            Unit unit = Unit.spawn(world, type, home.team, home.x() + Angles.trnsx(angle, 30f), home.y() + Angles.trnsy(angle, 30f), angle);
            unit.controller(new Skirmish(home.tile(), enemy[0].tile(), rand.random(0, 1 << 20)));
        }
        return Verdict.ADMITTED;
    }

    private static float dst(Building a, Building b){
        return Mathf.dst(a.x(), a.y(), b.x(), b.y());
    }
}
