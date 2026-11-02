package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ActiveList} against vanilla's {@code EntityGroup} from the jar, driven the way vanilla's generated entity
 * code drives it ({@code addIndex}, {@code removeIndex} with a tracked position, {@code each}). The same script of
 * adds and removes, many of them in the middle of an iteration, must visit entities in the same order and leave the
 * same order behind.
 */
class ActiveListTest{
    interface Side{
        void add(int label);

        void remove(int label);

        void each(IntConsumer visit);

        void clear();

        List<Integer> order();
    }

    @Test
    void scriptsVisitAndOrderLikeVanilla(){
        for(long seed = 0; seed < 300; seed++){
            List<List<Integer>> vanilla = run(seed, new Vanilla()), ours = run(seed, new Ours());
            assertEquals(vanilla, ours, "seed " + seed);
        }
    }

    /** The trace of one script: each iteration's visits, then the order after it. */
    static List<List<Integer>> run(long seed, Side side){
        var random = new SplittableRandom(seed);
        var members = new TreeSet<Integer>();
        var trace = new ArrayList<List<Integer>>();
        int[] next = {0};
        for(int round = 0; round < 40; round++){
            for(int i = random.nextInt(0, 8); i > 0; i--){
                side.add(next[0]);
                members.add(next[0]++);
            }
            if(!members.isEmpty() && random.nextInt(3) == 0){
                int victim = pick(members, random);
                side.remove(victim);
                members.remove(victim);
            }
            var visits = new ArrayList<Integer>();
            side.each(label -> {
                visits.add(label);
                int roll = random.nextInt(20);
                if(roll < 3 && members.contains(label)){
                    side.remove(label);
                    members.remove(label);
                }else if(roll < 5 && !members.isEmpty()){
                    int victim = pick(members, random);
                    side.remove(victim);
                    members.remove(victim);
                }else if(roll < 7){
                    side.add(next[0]);
                    members.add(next[0]++);
                }
            });
            trace.add(visits);
            trace.add(side.order());
            if(random.nextInt(25) == 0){
                side.clear();
                members.clear();
                trace.add(side.order());
            }
        }
        return trace;
    }

    static int pick(TreeSet<Integer> members, SplittableRandom random){
        int skip = random.nextInt(members.size());
        var iterator = members.iterator();
        for(int i = 0; i < skip; i++) iterator.next();
        return iterator.next();
    }

    /** A bare entity for the list: just a label and a position. */
    static final class Probe extends Entity{
        final int label;
        int position = -1;

        Probe(int label){
            this.label = label;
        }

        @Override
        public void add(){
        }

        @Override
        public void remove(){
        }

        @Override
        public Copyable blank(){
            return new Probe(label);
        }
    }

    static final class Ours implements Side{
        final ActiveList<Probe> list = new ActiveList<>(new ActiveList.Slot<>(){
            @Override
            public int get(Probe probe){
                return probe.position;
            }

            @Override
            public void set(Probe probe, int position){
                probe.position = position;
            }
        });
        final TreeMap<Integer, Probe> probes = new TreeMap<>();

        @Override
        public void add(int label){
            var probe = new Probe(label);
            probes.put(label, probe);
            list.add(probe);
        }

        @Override
        public void remove(int label){
            list.remove(probes.get(label));
        }

        @Override
        public void each(IntConsumer visit){
            list.each(probe -> visit.accept(probe.label));
        }

        @Override
        public void clear(){
            list.clear(probe -> list.remove(probe));
        }

        @Override
        public List<Integer> order(){
            return list.toList().stream().map(p -> p.label).toList();
        }
    }

    /** Vanilla's group over proxy entities, positions tracked as its generated {@code index__} fields are. */
    static final class Vanilla implements Side{
        final IdentityHashMap<Object, Integer> positions = new IdentityHashMap<>();
        final EntityGroup<Entityc> group = new EntityGroup<>(Entityc.class, false, false, (entity, index) -> positions.put(entity, index));
        final TreeMap<Integer, Entityc> entities = new TreeMap<>();

        @Override
        public void add(int label){
            Entityc entity = proxy(label);
            entities.put(label, entity);
            positions.put(entity, group.addIndex(entity));
        }

        @Override
        public void remove(int label){
            Entityc entity = entities.get(label);
            group.removeIndex(entity, positions.get(entity));
            positions.put(entity, -1);
        }

        @Override
        public void each(IntConsumer visit){
            group.each(entity -> visit.accept(entity.id()));
        }

        @Override
        public void clear(){
            group.clear();
        }

        @Override
        public List<Integer> order(){
            var out = new ArrayList<Integer>();
            for(int i = 0; i < group.size(); i++) out.add(group.index(i).id());
            return out;
        }

        Entityc proxy(int label){
            var holder = new Entityc[1];
            holder[0] = (Entityc)Proxy.newProxyInstance(Entityc.class.getClassLoader(), new Class<?>[]{Entityc.class}, (self, method, args) -> switch(method.getName()){
                case "id" -> label;
                case "equals" -> self == args[0];
                case "hashCode" -> label;
                case "toString" -> "entity " + label;
                case "remove" -> {
                    remove(label);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return holder[0];
        }
    }
}
