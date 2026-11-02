package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.content.*;

import java.util.*;

/** A tiny content epoch for the sim tests: four blocks of the toy kinds and one unit type. */
public final class ToyContent implements ContentEpoch{
    public static final ToyContent INSTANCE = new ToyContent(List.of(
        new Block(0, "air", KindKey.of("test-wall"), 1, 0f, false, false, false, false, false),
        new Block(1, "counter", KindKey.of("test-counter"), 1, 40f, true, false, true, false, false),
        new Block(2, "link", KindKey.of("test-link"), 2, 160f, true, true, false, true, true),
        new Block(3, "wall", KindKey.of("test-wall"), 1, 80f, false, false, false, false, false)
    ));
    public static final Block COUNTER = INSTANCE.blocks.get(1), LINK = INSTANCE.blocks.get(2), WALL = INSTANCE.blocks.get(3);
    public static final Unit DRONE = new Unit(0, "drone", 70f);

    private final List<Block> blocks;

    public ToyContent(List<Block> blocks){
        this.blocks = List.copyOf(blocks);
    }

    @Override
    public String hash(){
        return "toy-" + blocks.size();
    }

    @Override
    public int blocks(){
        return blocks.size();
    }

    @Override
    public BlockDef block(int id){
        return blocks.get(id);
    }

    @Override
    public int items(){
        return 4;
    }

    @Override
    public int liquids(){
        return 2;
    }

    @Override
    public int units(){
        return 1;
    }

    @Override
    public UnitDef unit(int id){
        return DRONE;
    }

    public record Block(int id, String name, KindKey kind, int size, float health, boolean update, boolean noUpdateDisabled,
                        boolean hasItems, boolean hasLiquids, boolean hasPower) implements BlockDef{
    }

    public record Unit(int id, String name, float health) implements UnitDef{
    }
}
