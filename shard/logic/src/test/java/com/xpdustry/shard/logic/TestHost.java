package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;

import java.util.*;

/**
 * A small world for the VM tests: buildings on a grid (memory cells, messages, switches, processors), vanilla's content
 * for the globals, and a log of every host call.
 */
final class TestHost implements LogicHost{
    /** A block: its name, and its memory size for cells. */
    record Block(String name, int memory){
        @Override
        public String toString(){
            return name;
        }
    }

    static final Block cell = new Block("memory-cell", 64), bank = new Block("memory-bank", 512),
    message = new Block("message", 0), toggle = new Block("switch", 0), processor = new Block("logic-processor", 0);

    /** A building: one tile, its block, a team, memory or text, and the Shard processor if it is one. */
    static final class Building{
        static int nextId;
        final int id = nextId++;
        final Block block;
        final int x, y;
        int team = 1;
        boolean enabled = true;
        final double[] memory;
        final StringBuilder text = new StringBuilder();
        Processor processor;

        Building(Block block, int x, int y){
            this.block = block;
            this.x = x;
            this.y = y;
            this.memory = new double[block.memory()];
        }

        @Override
        public String toString(){
            return block.name() + "@" + x + "," + y;
        }
    }

    static final ContentEpoch content = Epoch.vanilla();
    static final ItemDef copper = content.item("copper"), lead = content.item("lead");

    final Map<Integer, Building> buildings = new HashMap<>(); // lookups only
    final List<String> calls = new ArrayList<>();
    final List<Object> synced = new ArrayList<>();
    /** Replaced by a test that copies another host's world here. */
    LogicWorld world;

    TestHost(){
        world = new LogicWorld(this, globals());
    }

    private static Globals globals;

    /** One table for every test, of vanilla's content. */
    static synchronized Globals globals(){
        if(globals == null) globals = new Globals(content);
        return globals;
    }

    Building place(Block block, int x, int y){
        Building b = new Building(block, x, y);
        Building old = buildings.put(Point2.pack(x, y), b);
        world.links.tileChanged(x, y);
        if(old != null && old.processor != null) old.processor.dispose();
        return b;
    }

    void remove(int x, int y){
        buildings.remove(Point2.pack(x, y));
        world.links.tileChanged(x, y);
    }

    /** A logic processor (ipt 8) at {@code (x, y)} running {@code code}. */
    Processor processor(int x, int y, String code){
        return processor(x, y, code, false, 8, 8);
    }

    Processor processor(int x, int y, String code, boolean privileged, int ipt, int maxIpt){
        Building b = place(processor, x, y);
        Processor p = new Processor(world, b, privileged, ipt, maxIpt, Point2.pack(x, y), x * 8, y * 8);
        b.processor = p;
        p.team(b.team);
        p.setCode(code);
        return p;
    }

    /** {@code ticks} ticks of the world, every processor in place order (by id). */
    void run(int ticks, Processor... processors){
        for(int t = 0; t < ticks; t++){
            world.beginTick(world.tick() + 1, 1, 0, 100, 100, 1f);
            for(Processor p : processors) p.update(((Building)p.building()).enabled, 1f);
            world.endTick();
        }
    }

    // LogicHost

    @Override
    public String toString(Object value){
        if(value instanceof Building b) return b.block.name();
        if(value instanceof Block b) return b.name();
        return null;
    }

    @Override
    public boolean sensor(Processor p, Object target, Object property, int to){
        if(!(target instanceof Building b)) return false;
        if(property == LAccess.enabled) p.setbool(to, b.enabled);
        else if(property == LAccess.memoryCapacity) p.setnum(to, b.memory.length);
        else if(property == LAccess.type) p.setobj(to, b.block);
        else if(property == copper) p.setnum(to, 42);
        else return false;
        return true;
    }

    @Override
    public void control(Processor p, LAccess type, Object target, int p1, int p2, int p3, int p4){
        if(type == LAccess.enabled && target instanceof Building b) b.enabled = p.bool(p1);
        calls.add("control " + type + " " + target + " " + p.value(p1));
    }

    @Override
    public boolean read(Processor p, Object target, int position, int output){
        if(!(target instanceof Building b)) return false;
        if(b.processor != null){
            b.processor.externalRead(p, position, output);
            return true;
        }
        int address = p.numi(position);
        if(b.memory.length == 0 || address < 0 || address >= b.memory.length) p.setnum(output, 0);
        else p.setnum(output, b.memory[address]);
        return true;
    }

    @Override
    public void write(Processor p, Object target, int position, int value){
        if(!(target instanceof Building b)) return;
        if(b.processor != null){
            b.processor.externalWrite(p, position, value);
            return;
        }
        int address = p.numi(position);
        if(address >= 0 && address < b.memory.length) b.memory[address] = p.num(value);
    }

    @Override
    public void printflush(Processor p, Object target){
        if(target instanceof Building b && b.block == message){
            b.text.setLength(0);
            b.text.append(p.text());
        }
    }

    @Override
    public Object building(int x, int y){
        return buildings.get(Point2.pack(x, y));
    }

    @Override
    public boolean validLink(Processor p, Object other){
        Building b = (Building)other;
        return p.privileged || b.team == p.team();
    }

    @Override
    public Object block(Object building){
        return ((Building)building).block;
    }

    @Override
    public String blockName(Object building){
        return ((Building)building).block.name();
    }

    @Override
    public int buildingId(Object building){
        return ((Building)building).id;
    }

    @Override
    public int buildingTile(Object building){
        Building b = (Building)building;
        return Point2.pack(b.x, b.y);
    }

    @Override
    public void call(Processor p, HostOp op){
        calls.add(op.getClass().getSimpleName());
    }

    @Override
    public void sync(Processor p, int variable, Object value){
        synced.add(p.program().variableName(variable) + "=" + value);
    }

    @Override
    public String localeString(String name){
        return name.equals("hello") ? "Hello!" : null;
    }

    /** The value of variable {@code name}: a Double, an object, or null. */
    static Object var(Processor p, String name){
        int index = p.program().variable(name);
        if(index < 0) throw new IllegalArgumentException("no variable " + name);
        return p.variable(index);
    }

    static double num(Processor p, String name){
        return (Double)var(p, name);
    }
}
