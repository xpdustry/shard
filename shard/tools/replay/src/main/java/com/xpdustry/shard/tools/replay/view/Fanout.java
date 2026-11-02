package com.xpdustry.shard.tools.replay.view;

import java.util.*;
import java.util.function.*;

/**
 * One {@link ViewSource} read by several writers, for example to compare frame rates on the same match. Each view
 * gets every event once: the fanout drains the source's events and keeps a queue per view.
 */
public final class Fanout{
    private final ViewSource source;
    private final List<List<ViewSource.ViewEvent>> queues = new ArrayList<>();

    public Fanout(ViewSource source){
        this.source = source;
    }

    /** A new view of the source; it sees the events from now on. */
    public ViewSource view(){
        var queue = new ArrayList<ViewSource.ViewEvent>();
        queues.add(queue);
        return new ViewSource(){
            @Override
            public String producer(){
                return source.producer();
            }

            @Override
            public String game(){
                return source.game();
            }

            @Override
            public String content(){
                return source.content();
            }

            @Override
            public String map(){
                return source.map();
            }

            @Override
            public int width(){
                return source.width();
            }

            @Override
            public int height(){
                return source.height();
            }

            @Override
            public long tick(){
                return source.tick();
            }

            @Override
            public List<TeamView> teams(){
                return source.teams();
            }

            @Override
            public String blockName(int id){
                return source.blockName(id);
            }

            @Override
            public void terrain(short[] floor, short[] overlay, short[] env){
                source.terrain(floor, overlay, env);
            }

            @Override
            public void buildings(Consumer<BuildView> out){
                source.buildings(out);
            }

            @Override
            public void units(Consumer<UnitView> out){
                source.units(out);
            }

            @Override
            public void bullets(Consumer<BulletView> out){
                source.bullets(out);
            }

            @Override
            public List<ViewEvent> events(){
                var drained = source.events();
                for(var other : queues) other.addAll(drained);
                var out = List.copyOf(queue);
                queue.clear();
                return out;
            }

            @Override
            public Status status(){
                return source.status();
            }
        };
    }
}
