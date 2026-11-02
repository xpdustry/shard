package com.xpdustry.shard.logic.oracle;

import arc.struct.*;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.ContentDef;
import com.xpdustry.shard.logic.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import java.util.*;

/**
 * A {@link LogicHost} on vanilla's world objects, for the oracle tests: the bodies of {@code SenseI}, {@code ControlI},
 * {@code ReadI}, {@code WriteI} and {@code PrintFlushI} (LExecutor.java:575-724, 1178). Values cross over as each
 * side's own: the VM's content definitions, teams and {@link com.xpdustry.shard.logic.lang.LAccess} become vanilla's
 * objects ({@link #unshard}), and vanilla's content and teams the VM's ({@link #shard}). Reads and writes of a
 * building that a Shard processor runs on go to that processor.
 */
final class VanillaHost implements LogicHost{
    private static final mindustry.logic.LAccess[] access = mindustry.logic.LAccess.values();

    private final Map<Object, Processor> processors = new IdentityHashMap<>(); // lookups only
    private final Map<Processor, LExecutor> permissions = new IdentityHashMap<>();
    private final LVar position = new LVar("position"), io = new LVar("io");

    void register(Processor p){
        processors.put(p.building(), p);
    }

    void unregister(Processor p){
        processors.remove(p.building());
        permissions.remove(p);
    }

    /** {@code readable}/{@code printable}/{@code privileged} checks take an executor: one with the processor's identity. */
    private LExecutor exec(Processor p){
        LExecutor exec = permissions.computeIfAbsent(p, key -> new LExecutor());
        exec.build = (LogicBuild)p.building();
        exec.team = Team.get(p.team());
        exec.privileged = p.privileged;
        return exec;
    }

    static mindustry.logic.LAccess vanilla(com.xpdustry.shard.logic.lang.LAccess type){
        return access[type.ordinal()];
    }

    @Override
    public String toString(Object value){
        return LExecutor.PrintI.toString(value);
    }

    @Override
    public boolean sensor(Processor p, Object target, Object property, int to){
        // content is senseable too: sensor @id of @copper is its logic id
        if(!(unshard(target) instanceof Senseable se)) return false;
        if(unshard(property) instanceof Content content){
            p.setnum(to, se.sense(content));
            return true;
        }
        if(property instanceof com.xpdustry.shard.logic.lang.LAccess shard && (p.privileged || !shard.privileged)){
            mindustry.logic.LAccess type = vanilla(shard);
            Object out = se.senseObject(type);
            if(out == Senseable.noSensed) p.setnum(to, se.sense(type));
            else p.setobj(to, shard(out));
            return true;
        }
        return false;
    }

    @Override
    public void control(Processor p, com.xpdustry.shard.logic.lang.LAccess shard, Object target, int p1, int p2, int p3, int p4){
        LExecutor exec = exec(p);
        mindustry.logic.LAccess type = vanilla(shard);
        if(target instanceof Building b && (exec.privileged || (exec.build != null && exec.build.validLink(b)))){
            if(type == mindustry.logic.LAccess.enabled){
                if(p.bool(p1)) b.noSleep();
                else b.lastDisabler = exec.build;
            }
            if(type.isObj && p.isObj(p1)){
                b.control(type, unshard(p.obj(p1)), p.num(p2), p.num(p3), p.num(p4));
            }else{
                b.control(type, p.num(p1), p.num(p2), p.num(p3), p.num(p4));
            }
        }
    }

    /** The VM's values as vanilla's, for values handed to vanilla code: content by id, teams, Shard's own enums. */
    static Object unshard(Object value){
        return switch(value){
            case com.xpdustry.shard.logic.lang.LAccess type -> vanilla(type);
            case ContentDef def -> Vars.content.getByID(mindustry.ctype.ContentType.all[def.type().vanillaOrdinal()], def.id());
            case LogicTeam team -> Team.get(team.id());
            case null, default -> value;
        };
    }

    /** Vanilla's values as the VM's: content as the epoch's definitions (the same ids), teams as the table's. */
    static Object shard(Object value){
        Globals globals = VanillaLogic.globals();
        if(value instanceof Content content){
            var type = com.xpdustry.shard.content.ContentType.of(content.getContentType().name());
            // content Shard has no definitions for (a planet) stays vanilla's, as a host object
            if(type != null) return globals.content().get(type, content.id);
        }
        return value instanceof Team team ? globals.team(team.id) : value;
    }

    @Override
    public boolean read(Processor p, Object target, int position, int output){
        if(!(target instanceof LReadable read)) return false;
        LExecutor exec = exec(p);
        if(!read.readable(exec)){
            p.setobj(output, null);
            return true;
        }
        Processor other = processors.get(target);
        if(other != null){
            other.externalRead(p, position, output);
            return true;
        }
        load(this.position, p, position);
        load(io, p, output);
        read.read(this.position, io);
        if(io.isobj) p.setobj(output, shard(io.objval));
        else p.setnum(output, io.numval);
        return true;
    }

    @Override
    public void write(Processor p, Object target, int position, int value){
        if(!(target instanceof LWritable write)) return;
        if(!write.writable(exec(p))) return;
        Processor other = processors.get(target);
        if(other != null){
            other.externalWrite(p, position, value);
            return;
        }
        load(this.position, p, position);
        load(io, p, value);
        write.write(this.position, io);
    }

    private static void load(LVar var, Processor p, int slot){
        Object value = p.value(slot);
        if(value instanceof Double d){
            var.isobj = false;
            var.numval = d;
        }else{
            var.isobj = true;
            var.objval = unshard(value);
        }
        var.constant = p.isConstant(slot);
    }

    @Override
    public void printflush(Processor p, Object target){
        if(target instanceof Building b && b instanceof LPrintable printable && printable.printable(exec(p))){
            printable.print(p.text());
        }
    }

    @Override
    public Object building(int x, int y){
        return Vars.world.build(x, y);
    }

    @Override
    public boolean validLink(Processor p, Object other){
        return ((LogicBuild)p.building()).validLink((Building)other);
    }

    @Override
    public Object block(Object building){
        return ((Building)building).block;
    }

    @Override
    public String blockName(Object building){
        return ((Building)building).block.name;
    }

    @Override
    public int buildingId(Object building){
        return ((Building)building).id;
    }

    @Override
    public int buildingTile(Object building){
        Building b = (Building)building;
        return Point2.pack(b.tileX(), b.tileY());
    }

    /** Vanilla's {@code Seq} results as Shard lists, so the VM's {@code read}/{@code sensor @size} see them. */
    static List<Object> list(Seq<?> seq){
        ArrayList<Object> list = new ArrayList<>(seq.size);
        for(Object o : seq) list.add(o);
        return list;
    }
}
