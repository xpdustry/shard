package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.testkit.*;

import java.util.*;

/**
 * {@link KindAdapter}s for the unit factory and the reconstructor (W4.1): their public fields by vanilla's names, as
 * reflected, with the command, which vanilla holds as a {@code UnitCommand}, projected as its name (null for none).
 * The factory takes vanilla's configs: a plan index, a unit type, or none (which clears the command).
 */
public final class UnitBlockAdapters{
    private UnitBlockAdapters(){
    }

    static String command(Building building, int command){
        List<String> commands = building.world().content().unitCommands();
        return command < 0 || command >= commands.size() ? null : commands.get(command);
    }

    @Register
    public static final class UnitFactoryAdapter implements KindAdapter<UnitFactory>{
        @Override
        public KindKey kind(){
            return KindKey.of("unit-factory");
        }

        @Override
        public void fields(UnitFactory building, Fields out){
            out.reflect(building).put("command", command(building, building.command));
        }

        @Override
        public void configure(UnitFactory building, Config config, ShardNames names){
            switch(config){
                case Config.None none -> building.configureAny(null);
                case Config.Int(int value) -> building.configureAny(value);
                case Config.Content(String type, String name) when type.equals("unit") ->
                    building.configureAny(new ContentRef(ContentType.UNIT, building.world().content().id(ContentType.UNIT, name)));
                default -> throw new UnsupportedOperationException(building.block().name() + " takes a plan or a unit type, not " + config);
            }
        }
    }

    @Register
    public static final class ReconstructorAdapter implements KindAdapter<Reconstructor>{
        @Override
        public KindKey kind(){
            return KindKey.of("reconstructor");
        }

        @Override
        public void fields(Reconstructor building, Fields out){
            out.reflect(building).put("command", command(building, building.command));
        }
    }
}
