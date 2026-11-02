package com.xpdustry.shard.testkit.toy;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.testkit.*;

/**
 * The adapter a family owner would write: fields under the projection's names ({@code item} by name, {@code total}
 * for {@code sum}), and configs applied as the kind's config handlers would. Registered through the index.
 */
@Register
public final class CounterAdapter implements KindAdapter<Counter>{
    @Override
    public KindKey kind(){
        return KindKey.of("toy-counter");
    }

    @Override
    public void fields(Counter counter, Fields out){
        out.put("item", counter.itemId < 0 ? null : out.names().itemName(counter.itemId))
            .put("step", counter.step)
            .put("total", counter.sum)
            .put("target", counter.target);
    }

    @Override
    public void configure(Counter counter, Config config, ShardNames names){
        switch(config){
            case Config.Content(String type, String name) when type.equals("item") -> counter.itemId = names.item(name);
            case Config.Int(int step) -> counter.step = step;
            case Config.Point(int x, int y) -> counter.target = names.building(names.x(counter) + x, names.y(counter) + y);
            case Config.None none -> counter.itemId = -1;
            default -> throw new IllegalArgumentException("a counter takes an item, an int, a point or null, not " + config);
        }
    }
}
