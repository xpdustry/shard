package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.production.*;
import com.xpdustry.shard.testkit.*;

import java.util.*;

/**
 * {@link KindAdapter}s for the production kinds (W3.3): their public fields as reflected, with the item and liquid ids
 * vanilla holds as {@code Item}s and {@code Liquid}s projected as names (null for none), and a beam drill's facing
 * tiles and beam starts as vanilla's {@code Tile}s and {@code Point2}s project.
 */
public final class ProductionAdapters{
    private ProductionAdapters(){
    }

    static String item(ShardNames names, int id){
        return id < 0 ? null : names.itemName(id);
    }

    static String liquid(ShardNames names, int id){
        return id < 0 ? null : names.liquidName(id);
    }

    @Register
    public static final class DrillAdapter implements KindAdapter<Drill>{
        @Override
        public KindKey kind(){
            return KindKey.of("drill");
        }

        @Override
        public void fields(Drill building, Fields out){
            out.reflect(building).put("dominantItem", item(out.names(), building.dominantItem));
        }
    }

    @Register
    public static final class BurstDrillAdapter implements KindAdapter<BurstDrill>{
        @Override
        public KindKey kind(){
            return KindKey.of("burst-drill");
        }

        @Override
        public void fields(BurstDrill building, Fields out){
            out.reflect(building).put("dominantItem", item(out.names(), building.dominantItem));
        }
    }

    @Register
    public static final class BeamDrillAdapter implements KindAdapter<BeamDrill>{
        @Override
        public KindKey kind(){
            return KindKey.of("beam-drill");
        }

        @Override
        public void fields(BeamDrill building, Fields out){
            var tiles = building.world().tiles();
            var facing = new ArrayList<String>();
            for(int tile : building.facing) facing.add(tile == -1 ? null : Values.building(tiles.x(tile), tiles.y(tile)));
            List<List<Integer>> lasers = null;
            if(building.lasers != null){
                lasers = new ArrayList<>();
                for(int laser : building.lasers) lasers.add(List.of((int)Point2.x(laser), (int)Point2.y(laser)));
            }
            out.reflect(building).put("lastItem", item(out.names(), building.lastItem)).put("facing", facing).put("lasers", lasers);
        }
    }

    @Register
    public static final class PumpAdapter implements KindAdapter<Pump>{
        @Override
        public KindKey kind(){
            return KindKey.of("pump");
        }

        @Override
        public void fields(Pump building, Fields out){
            out.reflect(building).put("liquidDrop", liquid(out.names(), building.liquidDrop));
        }
    }

    @Register
    public static final class SolidPumpAdapter implements KindAdapter<SolidPump>{
        @Override
        public KindKey kind(){
            return KindKey.of("solid-pump");
        }

        @Override
        public void fields(SolidPump building, Fields out){
            out.reflect(building).put("liquidDrop", liquid(out.names(), building.liquidDrop));
        }
    }

    @Register
    public static final class FrackerAdapter implements KindAdapter<Fracker>{
        @Override
        public KindKey kind(){
            return KindKey.of("fracker");
        }

        @Override
        public void fields(Fracker building, Fields out){
            out.reflect(building).put("liquidDrop", liquid(out.names(), building.liquidDrop));
        }
    }
}
