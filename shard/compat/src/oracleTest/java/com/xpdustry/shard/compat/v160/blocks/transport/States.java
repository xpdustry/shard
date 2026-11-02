package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.distribution.Router;
import com.xpdustry.shard.sim.blocks.sandbox.ItemSource;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.transport.BlockRun.*;

/**
 * A building's state as one line, the same for vanilla's building and Shard's: the base (items, enabled, whether it
 * is in the update list, sleeping), then the kind's fields by vanilla's names. Floats print exactly.
 */
final class States{
    private States(){
    }

    static String vanilla(mindustry.gen.Building build, TileGrid tiles){
        var out = new StringBuilder(build.block.name).append(" (").append(build.tile.x).append(", ").append(build.tile.y).append(")")
            .append(" items ").append(items(id -> build.items == null ? 0 : build.items.get(id)))
            .append(" enabled ").append(build.enabled).append(" added ").append(build.isAdded())
            .append(" efficiency ").append(build.efficiency);
        switch(build){
            case mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c -> {
                out.append(" len ").append(c.len).append(" slots");
                for(int i = 0; i < c.len; i++) out.append(' ').append(c.ids[i] == null ? -1 : c.ids[i].id).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                out.append(" next ").append(pos(c.next)).append(" nextc ").append(pos(c.nextc)).append(" aligned ").append(c.aligned)
                    .append(" mid ").append(c.mid).append(" minitem ").append(c.minitem).append(" blendbits ").append(c.blendbits)
                    .append(" clogHeat ").append(c.clogHeat);
            }
            case mindustry.world.blocks.distribution.StackConveyor.StackConveyorBuild s -> out.append(" state ").append(s.state)
                .append(" link ").append(s.link).append(" cooldown ").append(s.cooldown).append(" lastItem ").append(s.lastItem == null ? -1 : s.lastItem.id);
            case mindustry.world.blocks.distribution.Router.RouterBuild r -> out.append(" last ").append(r.lastItem == null ? -1 : r.lastItem.id)
                .append(" time ").append(r.time);
            case mindustry.world.blocks.sandbox.ItemSource.ItemSourceBuild s -> out.append(" counter ").append(s.counter)
                .append(" output ").append(s.outputItem == null ? -1 : s.outputItem.id);
            case mindustry.world.blocks.distribution.Duct.DuctBuild d -> out.append(" progress ").append(d.progress)
                .append(" current ").append(id(d.current)).append(" recDir ").append(d.recDir).append(" next ").append(pos(d.next));
            case mindustry.world.blocks.distribution.DuctRouter.DuctRouterBuild d -> out.append(" sortItem ").append(id(d.sortItem))
                .append(" progress ").append(d.progress).append(" current ").append(id(d.current)).append(" cdump ").append(d.cdump)
                .append(d instanceof mindustry.world.blocks.distribution.StackRouter.StackRouterBuild s ? " unloading " + s.unloading : "");
            case mindustry.world.blocks.distribution.OverflowDuct.OverflowDuctBuild d -> out.append(" progress ").append(d.progress)
                .append(" current ").append(id(d.current)).append(" cdump ").append(d.cdump);
            case mindustry.world.blocks.distribution.DuctBridge.DuctBridgeBuild d -> {
                out.append(" progress ").append(d.progress).append(" lastLink ").append(pos(d.lastLink)).append(" occupied");
                for(var bridge : d.occupied) out.append(' ').append(pos(bridge));
            }
            case mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild b -> {
                out.append(" link ").append(point(b.link)).append(" incoming");
                for(int i = 0; i < b.incoming.size; i++) out.append(' ').append(point(b.incoming.items[i]));
                out.append(" warmup ").append(b.warmup).append(" time ").append(b.time).append(" timeSpeed ").append(b.timeSpeed)
                    .append(" wasMoved ").append(b.wasMoved).append(" moved ").append(b.moved).append(" hadValidLink ").append(b.hadValidLink)
                    .append(" transportCounter ").append(b.transportCounter).append(" timers ").append(Arrays.toString(b.timer.getTimes()))
                    .append(" dumpAccum ").append(Buffers.dumpAccum(b)).append(" cdump ").append(b.cdump);
                if(b instanceof mindustry.world.blocks.distribution.BufferedItemBridge.BufferedItemBridgeBuild buffered){
                    out.append(" buffer ").append(Buffers.describe(buffered));
                }
            }
            case mindustry.world.blocks.distribution.MassDriver.MassDriverBuild d -> {
                out.append(" link ").append(point(d.link)).append(" rotation ").append(d.rotation).append(" reloadCounter ").append(d.reloadCounter)
                    .append(" state ").append(d.state.ordinal()).append(" waiting");
                for(var shooter : d.waitingShooters.orderedItems()) out.append(' ').append(pos(shooter));
                out.append(" dumpAccum ").append(Buffers.dumpAccum(d)).append(" cdump ").append(d.cdump);
            }
            case mindustry.world.blocks.distribution.Junction.JunctionBuild j -> {
                out.append(" indexes ").append(Arrays.toString(j.buffer.indexes)).append(" buffers");
                for(long[] buffer : j.buffer.buffers) out.append(' ').append(Arrays.toString(buffer));
            }
            case mindustry.world.blocks.distribution.Sorter.SorterBuild s -> out.append(" sortItem ")
                .append(s.sortItem == null ? -1 : s.sortItem.id).append(" rotation ").append(s.rotation);
            case mindustry.world.blocks.distribution.OverflowGate.OverflowGateBuild g -> out.append(" rotation ").append(g.rotation);
            case mindustry.world.blocks.distribution.DirectionalUnloader.DirectionalUnloaderBuild u -> out.append(" unloadTimer ").append(u.unloadTimer)
                .append(" unloadItem ").append(id(u.unloadItem)).append(" offset ").append(u.offset);
            case mindustry.world.blocks.storage.Unloader.UnloaderBuild u -> {
                out.append(" unloadTimer ").append(u.unloadTimer).append(" rotations ").append(u.rotations)
                    .append(" sortItem ").append(u.sortItem == null ? -1 : u.sortItem.id).append(" possible");
                for(var stat : u.possibleBlocks) out.append(' ').append(Stats.describe(stat));
            }
            default -> {
            }
        }
        return out.toString();
    }

    static String shard(Building build){
        var out = new StringBuilder(build.block().name()).append(" (").append(build.tileX()).append(", ").append(build.tileY()).append(")")
            .append(" items ").append(items(id -> build.items == null ? 0 : build.items.get(id)))
            .append(" enabled ").append(build.enabled).append(" added ").append(build.isAdded())
            .append(" efficiency ").append(build.efficiency);
        switch(build){
            case ConveyorBuild c -> {
                out.append(" len ").append(c.len).append(" slots");
                for(int i = 0; i < c.len; i++) out.append(' ').append(c.ids[i]).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                out.append(" next ").append(pos(c.next)).append(" nextc ").append(pos(c.nextc)).append(" aligned ").append(c.aligned)
                    .append(" mid ").append(c.mid).append(" minitem ").append(c.minitem).append(" blendbits ").append(c.blendbits)
                    .append(" clogHeat ").append(c.clogHeat);
            }
            case StackConveyor s -> out.append(" state ").append(s.state).append(" link ").append(s.link).append(" cooldown ").append(s.cooldown)
                .append(" lastItem ").append(s.lastItem);
            case Router r -> out.append(" last ").append(r.lastItem).append(" time ").append(r.time);
            case ItemSource s -> out.append(" counter ").append(s.counter).append(" output ").append(s.outputItem);
            case Duct d -> out.append(" progress ").append(d.progress).append(" current ").append(d.current)
                .append(" recDir ").append(d.recDir).append(" next ").append(pos(d.next));
            case DuctRouterBuild d -> out.append(" sortItem ").append(d.sortItem).append(" progress ").append(d.progress)
                .append(" current ").append(d.current).append(" cdump ").append(d.cdump)
                .append(d instanceof StackRouter s ? " unloading " + s.unloading : "");
            case OverflowDuct d -> out.append(" progress ").append(d.progress).append(" current ").append(d.current).append(" cdump ").append(d.cdump);
            case DuctBridge d -> {
                out.append(" progress ").append(d.progress).append(" lastLink ").append(pos(d.lastLink)).append(" occupied");
                for(var bridge : d.occupied) out.append(' ').append(pos(bridge));
            }
            case com.xpdustry.shard.sim.blocks.distribution.ItemBridgeBuild b -> {
                out.append(" link ").append(point(b.link)).append(" incoming");
                for(int i = 0; i < b.incoming.size; i++) out.append(' ').append(point(b.incoming.items[i]));
                out.append(" warmup ").append(b.warmup).append(" time ").append(b.time).append(" timeSpeed ").append(b.timeSpeed)
                    .append(" wasMoved ").append(b.wasMoved).append(" moved ").append(b.moved).append(" hadValidLink ").append(b.hadValidLink)
                    .append(" transportCounter ").append(b.transportCounter).append(" timers ").append(Arrays.toString(b.timer))
                    .append(" dumpAccum ").append(b.dumpAccum).append(" cdump ").append(b.cdump);
                if(b instanceof com.xpdustry.shard.sim.blocks.distribution.BufferedItemBridge buffered){
                    out.append(" buffer ").append(Buffers.describe(buffered.bufferIndex, buffered.buffer));
                }
            }
            case com.xpdustry.shard.sim.blocks.distribution.MassDriver d -> {
                out.append(" link ").append(point(d.link)).append(" rotation ").append(d.driverRotation).append(" reloadCounter ").append(d.reloadCounter)
                    .append(" state ").append(d.state.ordinal()).append(" waiting");
                for(var shooter : d.waitingShooters) out.append(' ').append(pos(shooter));
                out.append(" dumpAccum ").append(d.dumpAccum).append(" cdump ").append(d.cdump);
            }
            case com.xpdustry.shard.sim.blocks.distribution.Junction j -> {
                out.append(" indexes ").append(Arrays.toString(j.indexes)).append(" buffers");
                int capacity = j.capacity();
                for(int i = 0; i < 4; i++) out.append(' ').append(Arrays.toString(Arrays.copyOfRange(j.buffers, i * capacity, (i + 1) * capacity)));
            }
            case com.xpdustry.shard.sim.blocks.distribution.Sorter s -> out.append(" sortItem ").append(s.sortItem).append(" rotation ").append(s.rotation);
            case com.xpdustry.shard.sim.blocks.distribution.OverflowGate g -> out.append(" rotation ").append(g.rotation);
            case com.xpdustry.shard.sim.blocks.distribution.DirectionalUnloader u -> out.append(" unloadTimer ").append(u.unloadTimer)
                .append(" unloadItem ").append(u.unloadItem).append(" offset ").append(u.offset);
            case com.xpdustry.shard.sim.blocks.distribution.Unloader u -> {
                out.append(" unloadTimer ").append(u.unloadTimer).append(" rotations ").append(u.rotations)
                    .append(" sortItem ").append(u.sortItem).append(" possible");
                for(var stat : u.possibleBlocks){
                    out.append(' ').append(Stats.describe(pos(stat.building), stat.loadFactor, stat.canLoad, stat.canUnload, stat.notStorage, stat.lastUsed));
                }
            }
            default -> {
            }
        }
        return out.toString();
    }

    /** {@code Unloader.ContainerStat}'s fields, which are package-private in vanilla. */
    static final class Stats{
        static String describe(Object stat){
            try{
                Class<?> type = stat.getClass();
                return describe(pos((mindustry.gen.Building)field(type, "building").get(stat)), field(type, "loadFactor").getFloat(stat),
                    field(type, "canLoad").getBoolean(stat), field(type, "canUnload").getBoolean(stat),
                    field(type, "notStorage").getBoolean(stat), field(type, "lastUsed").getInt(stat));
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }

        static String describe(String building, float loadFactor, boolean canLoad, boolean canUnload, boolean notStorage, int lastUsed){
            return "{" + building + " load " + loadFactor + " canLoad " + canLoad + " canUnload " + canUnload + " notStorage " + notStorage
                + " lastUsed " + lastUsed + "}";
        }

        private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException{
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    /** {@code BufferedItemBridgeBuild.buffer}, a private {@code ItemBuffer}: the queued count and every slot. */
    static final class Buffers{
        static String describe(mindustry.world.blocks.distribution.BufferedItemBridge.BufferedItemBridgeBuild bridge){
            try{
                var field = findField(bridge.getClass(), "buffer");
                Object buffer = field.get(bridge);
                var index = buffer.getClass().getDeclaredField("index");
                var slots = buffer.getClass().getDeclaredField("buffer");
                index.setAccessible(true);
                slots.setAccessible(true);
                return describe(index.getInt(buffer), (long[])slots.get(buffer));
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }

        /** {@code dumpAccum}, protected in vanilla's building. */
        static float dumpAccum(mindustry.gen.Building building){
            try{
                return findField(building.getClass(), "dumpAccum").getFloat(building);
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }

        static String describe(int index, long[] slots){
            return index + " " + Arrays.toString(slots);
        }

        private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException{
            for(Class<?> at = type; at != null; at = at.getSuperclass()){
                try{
                    var field = at.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                }catch(NoSuchFieldException ignored){
                }
            }
            throw new NoSuchFieldException(name);
        }
    }

    static int id(mindustry.type.Item item){
        return item == null ? -1 : item.id;
    }

    static String pos(mindustry.gen.Building build){
        return build == null ? "none" : build.tile.x + "," + build.tile.y;
    }

    static String pos(Building build){
        return build == null ? "none" : build.tileX() + "," + build.tileY();
    }

    static String point(int packed){
        return packed == -1 ? "none" : Point2.x(packed) + "," + Point2.y(packed);
    }

    static String ints(int[] values){
        return Arrays.toString(values);
    }
}
