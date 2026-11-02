package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.testkit.*;

/**
 * {@link KindAdapter}s for the transport and distribution kinds (W2.6): their public fields by vanilla's names, as
 * reflected, with the item ids vanilla holds as {@code Item}s projected as names (null for none), and item configs
 * for the kinds vanilla configures with an item.
 */
public final class TransportAdapters{
    private TransportAdapters(){
    }

    static String item(ShardNames names, int id){
        return id < 0 ? null : names.itemName(id);
    }

    /** {@code configure(Item)} or {@code configure(null)}, the only configs these kinds take. */
    static void configureItem(Building building, Config config, ShardNames names){
        switch(config){
            case Config.None none -> building.configureAny(null);
            case Config.Content(String type, String name) when type.equals("item") -> building.configureAny(ContentRef.item(names.item(name)));
            default -> throw new UnsupportedOperationException(building.block().name() + " takes an item config, not " + config);
        }
    }

    @Register
    public static final class ConveyorAdapter implements KindAdapter<ConveyorBuild>{
        @Override
        public KindKey kind(){
            return KindKey.of("conveyor");
        }

        @Override
        public void fields(ConveyorBuild building, Fields out){
            out.reflect(building).put("ids", out.names().itemNames(building.ids));
        }
    }

    @Register
    public static final class ArmoredConveyorAdapter implements KindAdapter<ConveyorBuild>{
        @Override
        public KindKey kind(){
            return KindKey.of("armored-conveyor");
        }

        @Override
        public void fields(ConveyorBuild building, Fields out){
            out.reflect(building).put("ids", out.names().itemNames(building.ids));
        }
    }

    @Register
    public static final class StackConveyorAdapter implements KindAdapter<StackConveyor>{
        @Override
        public KindKey kind(){
            return KindKey.of("stack-conveyor");
        }

        @Override
        public void fields(StackConveyor building, Fields out){
            out.reflect(building).put("lastItem", item(out.names(), building.lastItem));
        }
    }

    @Register
    public static final class DuctAdapter implements KindAdapter<Duct>{
        @Override
        public KindKey kind(){
            return KindKey.of("duct");
        }

        @Override
        public void fields(Duct building, Fields out){
            out.reflect(building).put("current", item(out.names(), building.current));
        }
    }

    @Register
    public static final class OverflowDuctAdapter implements KindAdapter<OverflowDuct>{
        @Override
        public KindKey kind(){
            return KindKey.of("overflow-duct");
        }

        @Override
        public void fields(OverflowDuct building, Fields out){
            out.reflect(building).put("current", item(out.names(), building.current));
        }
    }

    @Register
    public static final class DuctRouterAdapter implements KindAdapter<DuctRouterBuild>{
        @Override
        public KindKey kind(){
            return KindKey.of("duct-router");
        }

        @Override
        public void fields(DuctRouterBuild building, Fields out){
            out.reflect(building).put("current", item(out.names(), building.current)).put("sortItem", item(out.names(), building.sortItem));
        }

        @Override
        public void configure(DuctRouterBuild building, Config config, ShardNames names){
            configureItem(building, config, names);
        }
    }

    @Register
    public static final class StackRouterAdapter implements KindAdapter<DuctRouterBuild>{
        @Override
        public KindKey kind(){
            return KindKey.of("stack-router");
        }

        @Override
        public void fields(DuctRouterBuild building, Fields out){
            out.reflect(building).put("current", item(out.names(), building.current)).put("sortItem", item(out.names(), building.sortItem));
        }

        @Override
        public void configure(DuctRouterBuild building, Config config, ShardNames names){
            configureItem(building, config, names);
        }
    }

    @Register
    public static final class SorterAdapter implements KindAdapter<Sorter>{
        @Override
        public KindKey kind(){
            return KindKey.of("sorter");
        }

        @Override
        public void fields(Sorter building, Fields out){
            out.reflect(building).put("sortItem", item(out.names(), building.sortItem));
        }

        @Override
        public void configure(Sorter building, Config config, ShardNames names){
            configureItem(building, config, names);
        }
    }

    @Register
    public static final class UnloaderAdapter implements KindAdapter<Unloader>{
        @Override
        public KindKey kind(){
            return KindKey.of("unloader");
        }

        @Override
        public void fields(Unloader building, Fields out){
            // possibleBlocks holds pooled stats with no vanilla projection to compare against
            out.reflect(building).remove("possibleBlocks").put("sortItem", item(out.names(), building.sortItem));
        }

        @Override
        public void configure(Unloader building, Config config, ShardNames names){
            configureItem(building, config, names);
        }
    }
}
