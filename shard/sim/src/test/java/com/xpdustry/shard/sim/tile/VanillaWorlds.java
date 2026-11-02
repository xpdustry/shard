package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/** Worlds of the vanilla epoch; unported kinds are passthroughs. */
public final class VanillaWorlds{
    public static final Epoch VANILLA = Epoch.vanilla();

    public static World of(ContentEpoch content, int width, int height){
        return new World(WorldConfig.of(content, width, height, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
    }

    /** A {@code width × height} world of stone with the built-in systems, as the oracle's {@code emptyWorld}. */
    public static World world(int width, int height, long seed){
        return world(width, height, seed, Systems.builtIn());
    }

    /** A {@code width × height} world of stone with {@code systems}, as the oracle's {@code emptyWorld}. */
    public static World world(int width, int height, long seed, Systems systems){
        World world = new World(WorldConfig.of(VANILLA, width, height, seed).withSystems(systems)
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        fill(world, "stone");
        return world;
    }

    /** A {@code width × height} world of vanilla blocks with {@code floor} everywhere. */
    public static World of(int width, int height, String floor){
        return filled(VANILLA, width, height, floor);
    }

    public static World filled(ContentEpoch content, int width, int height, String floor){
        return fill(of(content, width, height), floor);
    }

    /** Loads {@code floor} into every tile of {@code world}, as a map load. */
    static World fill(World world, String floor){
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, world.content().block(floor).id());
        world.buildings().endLoad(false);
        return world;
    }

    /** The vanilla blocks, with the ones named given {@code kind}, for tests that swap in a probe kind. */
    public static ContentEpoch withKind(KindKey kind, String... names){
        var swapped = new TreeMap<Integer, BlockDef>();
        for(String name : names) swapped.put(VANILLA.block(name).id(), new Rekinded(VANILLA.block(name), kind));
        return new Swapped(VANILLA, swapped);
    }

    /** A block of another kind; it builds and saves as that kind. */
    record Rekinded(BlockDef block, KindKey kind) implements BlockDef{
        @Override
        public int id(){
            return block.id();
        }

        @Override
        public String name(){
            return block.name();
        }

        @Override
        public KindKey buildKind(){
            return kind;
        }

        @Override
        public boolean baked(){
            return true;
        }

        @Override
        public com.xpdustry.shard.content.schema.Struct fields(){
            return block.fields();
        }

        @Override
        public ItemDef itemDrop(){
            return block.itemDrop();
        }

        @Override
        public int size(){
            return block.size();
        }

        @Override
        public float health(){
            return block.health();
        }

        @Override
        public boolean update(){
            return block.update();
        }

        @Override
        public boolean noUpdateDisabled(){
            return block.noUpdateDisabled();
        }

        @Override
        public boolean hasItems(){
            return block.hasItems();
        }

        @Override
        public boolean hasLiquids(){
            return block.hasLiquids();
        }

        @Override
        public boolean hasPower(){
            return block.hasPower();
        }
    }

    /** {@code base} with some blocks swapped, under a hash of its own. */
    record Swapped(ContentEpoch base, SortedMap<Integer, BlockDef> swapped) implements ContentEpoch{
        @Override
        public String hash(){
            return base.hash() + "+" + swapped.values().stream().map(b -> b.name() + ":" + b.kind()).toList();
        }

        @Override
        public int blocks(){
            return base.blocks();
        }

        @Override
        public BlockDef block(int id){
            BlockDef block = swapped.get(id);
            return block != null ? block : base.block(id);
        }

        @Override
        public int count(ContentType type){
            return base.count(type);
        }

        @Override
        public ContentDef get(ContentType type, int id){
            return type == ContentType.BLOCK ? block(id) : base.get(type, id);
        }

        @Override
        public ContentDef find(ContentType type, String name){
            ContentDef def = base.find(type, name);
            return def instanceof BlockDef block ? block(block.id()) : def;
        }

        @Override
        public int items(){
            return base.items();
        }

        @Override
        public int liquids(){
            return base.liquids();
        }

        @Override
        public int units(){
            return base.units();
        }

        @Override
        public UnitDef unit(int id){
            return base.unit(id);
        }
    }

    private VanillaWorlds(){}
}
