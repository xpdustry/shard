package com.xpdustry.shard.testkit.toy;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;

import java.util.*;

/**
 * Content for the testkit's own tests, named like vanilla where a test compares with it: its items and liquids are
 * vanilla's. {@link #broken()} binds the pump block to {@link BrokenPump} instead of {@link Pump}; nothing else differs.
 */
public final class ToyContent implements ContentEpoch{
    private static final ContentEpoch VANILLA = Epoch.vanilla();
    private static final ToyContent NORMAL = new ToyContent("pump", "toy-pump"), BROKEN = new ToyContent("pump", "toy-pump-broken");

    private final List<BlockDef> blocks;
    private final List<UnitDef> units = List.of(new Unit(0, "dagger", 130f), new Unit(1, "flare", 70f));
    private final String hash;

    public ToyContent(String pump, String pumpKind){
        blocks = List.of(
            new Block(0, "air", KindKey.of("toy-wall"), 1, 0f, false, false, false, false, false),
            new Block(1, "copper-wall", KindKey.of("toy-wall"), 1, 320f, false, false, false, false, false),
            new Block(2, pump, KindKey.of(pumpKind), 1, 60f, true, false, false, true, true),
            new Block(3, "counter", KindKey.of("toy-counter"), 1, 40f, true, false, true, false, false),
            new Block(4, "big-wall", KindKey.of("toy-wall"), 2, 1280f, false, false, false, false, false)
        );
        hash = "testkit-toy-" + pumpKind;
    }

    public static ToyContent normal(){
        return NORMAL;
    }

    public static ToyContent broken(){
        return BROKEN;
    }

    @Override
    public String hash(){
        return hash;
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
        return VANILLA.items();
    }

    @Override
    public int liquids(){
        return VANILLA.liquids();
    }

    @Override
    public ContentDef get(ContentType type, int id){
        return switch(type){
            case ITEM, LIQUID -> VANILLA.get(type, id);
            default -> ContentEpoch.super.get(type, id);
        };
    }

    @Override
    public int units(){
        return units.size();
    }

    @Override
    public UnitDef unit(int id){
        return units.get(id);
    }

    public record Block(int id, String name, KindKey kind, int size, float health, boolean update, boolean noUpdateDisabled,
                        boolean hasItems, boolean hasLiquids, boolean hasPower) implements BlockDef{
    }

    public record Unit(int id, String name, float health) implements UnitDef{
    }
}
