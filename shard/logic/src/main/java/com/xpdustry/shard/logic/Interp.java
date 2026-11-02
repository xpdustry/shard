package com.xpdustry.shard.logic;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.logic.lang.*;

import java.util.*;

import static com.xpdustry.shard.logic.Program.*;

/**
 * The interpreter (note 28, option A): a switch over pre-resolved slots, with no {@code LVar} objects, no lambdas and
 * no megamorphic calls for the hot opcodes. The program counter is an {@code int}. Instructions that read or write
 * {@code @counter}, and {@code wait}/{@code stop}, take {@link #step}, which runs them exactly as
 * {@code LExecutor.runOnce} does, with {@code @counter} as a double in slot 0.
 */
final class Interp{
    private Interp(){
    }

    /** {@code LExecutor.maxTextBuffer}. */
    static final int maxTextBuffer = 400;

    private static final LAccess[] access = LAccess.values();

    /** Runs up to {@code budget} instructions that don't yield. @return the instructions charged. */
    static int run(Processor p, int budget){
        int done = 0;
        while(done < budget && !p.yield){
            done += p.exact ? runExact(p, budget - done) : runFast(p, budget - done);
        }
        return done;
    }

    private static int runFast(Processor p, int budget){
        final Program prog = p.program;
        final int[] code = prog.code;
        final int[] table = prog.dispatch;
        final int len = prog.length;
        final double[] n = p.n;
        final Object[] o = p.o;
        final boolean[] k = p.k;
        int pc = p.pc;
        int done = 0;
        // whether slot 0 holds vanilla's @counter (after a slow instruction) or the counter is just pc
        boolean raw = p.rawCounter;

        while(done < budget){
            if(pc >= len || pc < 0) pc = 0; // runOnce: an out-of-range counter restarts at 0
            int b = pc * STRIDE;
            int opc = table[pc];
            if(opc == SLOW){
                p.pc = pc;
                boolean charged = step(p);
                if(!charged) return done;
                done++;
                if(p.exact) return done;
                pc = p.pc;
                raw = true;
                continue;
            }
            pc++;
            raw = false;
            switch(opc){
                case SET -> Rt.set(n, o, k, code[b + 2], code[b + 1]);
                case OP -> Rt.setnum(n, o, k, code[b + 3], Rt.num(n, o, code[b + 1]) + Rt.num(n, o, code[b + 2]));
                case OP + 1 -> Rt.setnum(n, o, k, code[b + 3], Rt.num(n, o, code[b + 1]) - Rt.num(n, o, code[b + 2]));
                case OP + 2 -> Rt.setnum(n, o, k, code[b + 3], Rt.num(n, o, code[b + 1]) * Rt.num(n, o, code[b + 2]));
                case OP + 3 -> Rt.setnum(n, o, k, code[b + 3], Rt.num(n, o, code[b + 1]) / Rt.num(n, o, code[b + 2]));
                case OP + 11 -> Rt.setnum(n, o, k, code[b + 3], Rt.num(n, o, code[b + 1]) < Rt.num(n, o, code[b + 2]) ? 1 : 0);
                // jumps: a target of -1 never jumps; others, even out of range, are taken (and wrap on the next run)
                case JUMP + 7 -> {
                    int t = code[b + 3];
                    if(t != -1) pc = t;
                }
                case JUMP + 2 -> {
                    int t = code[b + 3];
                    if(t != -1 && Rt.num(n, o, code[b + 1]) < Rt.num(n, o, code[b + 2])) pc = t;
                }
                case JUMP + 3 -> {
                    int t = code[b + 3];
                    if(t != -1 && Rt.num(n, o, code[b + 1]) <= Rt.num(n, o, code[b + 2])) pc = t;
                }
                case JUMP + 4 -> {
                    int t = code[b + 3];
                    if(t != -1 && Rt.num(n, o, code[b + 1]) > Rt.num(n, o, code[b + 2])) pc = t;
                }
                case JUMP + 5 -> {
                    int t = code[b + 3];
                    if(t != -1 && Rt.num(n, o, code[b + 1]) >= Rt.num(n, o, code[b + 2])) pc = t;
                }
                case JUMP, JUMP + 1, JUMP + 6 -> {
                    int t = code[b + 3];
                    if(t != -1 && Rt.cond(opc - JUMP, n, o, code[b + 1], code[b + 2])) pc = t;
                }
                case END -> pc = len;
                case NOOP, DRAW -> {} // draw does nothing on a headless server (DrawI returns first)
                default -> {
                    if(opc >= OP){
                        Rt.op(p, opc - OP, code[b + 1], code[b + 2], code[b + 3]);
                    }else{
                        // a read or write of this processor's own @counter by name sees and changes the real counter
                        p.pc = pc;
                        p.rawCounter = false;
                        exec(p, b);
                        pc = p.pc;
                        raw = p.rawCounter;
                        if(p.exact) return done + 1;
                    }
                }
            }
            done++;
        }
        p.pc = pc;
        p.rawCounter = raw;
        return done;
    }

    private static int runExact(Processor p, int budget){
        int done = 0;
        while(done < budget && p.exact){
            if(!step(p)) return done;
            done++;
        }
        return done;
    }

    /**
     * One instruction as {@code LExecutor.runOnce} runs it: {@code @counter} is the double in slot 0 while it runs,
     * incremented before the instruction. @return false if the instruction yielded (yields are not charged).
     */
    static boolean step(Processor p){
        final Program prog = p.program;
        final int len = prog.length;
        final double[] n = p.n;
        final Object[] o = p.o;
        double c = p.exact ? p.cexact : p.pc;
        if(c >= len || c < 0) c = 0;
        int at = (int)c;
        int b = at * STRIDE;
        n[0] = c + 1;
        o[0] = Rt.NUM;
        p.rawCounter = true;
        int[] code = prog.code;
        int opc = code[b];

        if(opc >= JUMP && opc < OP){
            int t = code[b + 3];
            if(t != -1 && Rt.cond(opc - JUMP, n, o, code[b + 1], code[b + 2])) n[0] = t;
        }else if(opc == END){
            n[0] = len;
        }else if(opc == WAIT){
            double value = Rt.num(n, o, code[b + 1]);
            if(value <= 0){
                // yield without waiting; the next wait starts afresh (another processor may have changed the value)
                p.yield = true;
                p.waitTime[at] = 0f;
            }else if(p.waitTime[at] >= value){
                p.waitTime[at] = 0f;
            }else{
                n[0]--;
                p.yield = true;
                p.yieldedAt = at;
                p.waitTime[at] += p.world.delta / 60f;
            }
        }else if(opc == STOP){
            n[0]--;
            p.yield = true;
            p.yieldedAt = at;
        }else if(opc >= OP){
            Rt.op(p, opc - OP, code[b + 1], code[b + 2], code[b + 3]);
        }else{
            exec(p, b);
        }

        // n[0] keeps the last number even after an object write to @counter (vanilla keeps numval)
        p.counterWritten();
        return !p.yield;
    }

    /** Everything that is not control flow (the record at offset {@code b}). */
    static void exec(Processor p, int b){
        final Program prog = p.program;
        final int[] code = prog.code;
        final double[] n = p.n;
        final Object[] o = p.o;
        final boolean[] k = p.k;
        final LogicHost host = p.world.host;
        switch(code[b]){
            case NOOP, DRAW, END, WAIT, STOP -> {}
            case SET -> Rt.set(n, o, k, code[b + 2], code[b + 1]);
            case SELECT -> {
                int result = code[b + 2];
                if(k[result]) return;
                Rt.set(n, o, k, result, Rt.cond(code[b + 1], n, o, code[b + 3], code[b + 4]) ? code[b + 5] : code[b + 6]);
            }
            case PRINT -> print(p, code[b + 1]);
            case PRINTCHAR -> {
                StringBuilder text = p.text;
                if(text.length() >= maxTextBuffer) return;
                int v = code[b + 1];
                if(o[v] != Rt.NUM){
                    // UnlockableContent's emojiChar(); bullets aren't unlockable, and print nothing
                    if(o[v] instanceof ContentDef def && def.type() != ContentType.BULLET) text.append((char)def.emoji());
                    return;
                }
                text.append((char)Math.floor(n[v]));
            }
            case FORMAT -> format(p, code[b + 1]);
            case PRINTFLUSH -> {
                p.charge(LogicBudget.Cost.PRINTFLUSH);
                Object target = Rt.obj(o, code[b + 1]);
                if(target != null) host.printflush(p, target);
                p.text.setLength(0);
            }
            case DRAWFLUSH -> {
                p.charge(LogicBudget.Cost.DRAWFLUSH);
                Object target = Rt.obj(o, code[b + 1]);
                if(target != null) host.drawflush(p, target);
            }
            case GETLINK -> {
                int address = (int)Rt.num(n, o, code[b + 2]);
                Object[] links = p.linkTargets;
                Rt.setobj(o, k, code[b + 1], address >= 0 && address < links.length ? links[address] : null);
            }
            case READ -> read(p, Rt.obj(o, code[b + 1]), code[b + 2], code[b + 3]);
            case WRITE -> {
                p.charge(LogicBudget.Cost.WRITE);
                Object target = Rt.obj(o, code[b + 1]);
                if(target != null) host.write(p, target, code[b + 2], code[b + 3]);
            }
            case SENSOR -> sensor(p, Rt.obj(o, code[b + 1]), code[b + 2], Rt.obj(o, code[b + 3]));
            case CONTROL -> {
                p.charge(LogicBudget.Cost.CONTROL);
                Object target = Rt.obj(o, code[b + 2]);
                if(target != null) host.control(p, access[code[b + 1]], target, code[b + 3], code[b + 4], code[b + 5], code[b + 6]);
            }
            case SETRATE -> {
                if(p.building == null) return;
                p.ipt = Math.max(1, Math.min((int)Rt.num(n, o, code[b + 1]), p.maxIpt));
                n[prog.iptSlot] = p.ipt; // vanilla writes @ipt's numval directly, past its constant flag
            }
            case PACKCOLOR -> Rt.setnum(n, o, k, code[b + 1], MlogMath.packColor(
                clamp01((float)Rt.num(n, o, code[b + 2])), clamp01((float)Rt.num(n, o, code[b + 3])),
                clamp01((float)Rt.num(n, o, code[b + 4])), clamp01((float)Rt.num(n, o, code[b + 5]))));
            case UNPACKCOLOR -> {
                int c = (int)Double.doubleToRawLongBits(Rt.num(n, o, code[b + 5]));
                Rt.setnum(n, o, k, code[b + 1], ((c & 0xff000000) >>> 24) / 255f);
                Rt.setnum(n, o, k, code[b + 2], ((c & 0x00ff0000) >>> 16) / 255f);
                Rt.setnum(n, o, k, code[b + 3], ((c & 0x0000ff00) >>> 8) / 255f);
                Rt.setnum(n, o, k, code[b + 4], (c & 0x000000ff) / 255f);
            }
            case LOOKUP -> Rt.setobj(o, k, code[b + 1], p.world.table.lookup(code[b + 3], (int)Rt.num(n, o, code[b + 2])));
            case HOST -> {
                p.hostInstruction = b / STRIDE;
                host(p, (HostOp)prog.aux[b / STRIDE]);
            }
            default -> {
                int opc = code[b];
                if(opc >= OP) Rt.op(p, opc - OP, code[b + 1], code[b + 2], code[b + 3]);
                else throw new IllegalStateException("opcode " + opc);
            }
        }
    }

    private static float clamp01(float value){
        return Math.max(Math.min(value, 1f), 0f);
    }

    /** {@code SenseI}: strings and lists are the VM's; everything else is the host's. */
    private static void sensor(Processor p, Object target, int to, Object sense){
        p.charge(LogicBudget.Cost.SENSOR);
        if(target == null && sense == LAccess.dead){
            p.setnum(to, 1);
            return;
        }
        if(target instanceof CharSequence || target instanceof List<?>){
            if(sense == LAccess.size || sense == LAccess.bufferSize){
                p.setnum(to, target instanceof CharSequence seq ? seq.length() : ((List<?>)target).size());
                return;
            }
        }else if(target != null && p.world.host.sensor(p, target, sense, to)){
            return;
        }
        p.setobj(to, null); // an unknown or unhandled property
    }

    /** {@code ReadI}: readable buildings are the host's; strings and lists are the VM's. */
    private static void read(Processor p, Object target, int position, int output){
        p.charge(LogicBudget.Cost.READ);
        if(target != null && p.world.host.read(p, target, position, output)) return;
        int address = (int)p.num(position);
        if(target instanceof CharSequence str){
            p.setnum(output, address < 0 || address >= str.length() ? Double.NaN : (int)str.charAt(address));
        }else if(target instanceof List<?> list){
            p.setobj(output, address < 0 || address >= list.size() ? null : list.get(address));
        }else{
            p.setobj(output, null);
        }
    }

    /** The host statements, with the parts of them that are pure VM state. */
    private static void host(Processor p, HostOp op){
        p.charge(LogicBudget.Cost.of(op));
        LogicHost host = p.world.host;
        switch(op){
            case Message m -> {
                p.setnum(m.outSuccess(), 1);
                // a headless server shows no announcements or toasts; only the mission text has an effect
                if(m.type() == MessageType.mission) host.call(p, m);
                p.text.setLength(0);
            }
            case Sync s -> p.sync(s.variable());
            case LocalePrint l -> {
                if(p.text.length() >= maxTextBuffer) return;
                if(p.isObj(l.value())){
                    p.text.append(host.localeString(p.world.toString(p.obj(l.value()))));
                }
            }
            default -> host.call(p, op);
        }
    }

    static void print(Processor p, int v){
        StringBuilder text = p.text;
        if(text.length() >= maxTextBuffer) return;
        Object x = p.o[v];
        if(x != Rt.NUM){
            String s = p.world.toString(x);
            text.append(s, 0, Math.min(s.length(), maxTextBuffer - text.length()));
        }else{
            double d = p.n[v];
            // integers print without a fraction
            if(Math.abs(d - Math.round(d)) < 0.00001) text.append(Math.round(d));
            else text.append(d);
        }
    }

    static void format(Processor p, int v){
        StringBuilder text = p.text;
        int placeholderIndex = -1, placeholderNumber = 10;
        for(int i = 0; i < text.length(); i++){
            if(text.charAt(i) == '{' && text.length() - i > 2){
                char numChar = text.charAt(i + 1);
                if(numChar >= '0' && numChar <= '9' && text.charAt(i + 2) == '}'){
                    if(numChar - '0' < placeholderNumber){
                        placeholderNumber = numChar - '0';
                        placeholderIndex = i;
                    }
                }
            }
        }
        if(placeholderIndex == -1) return;
        Object x = p.o[v];
        if(x != Rt.NUM){
            text.replace(placeholderIndex, placeholderIndex + 3, p.world.toString(x));
        }else{
            double d = p.n[v];
            if(Math.abs(d - Math.round(d)) < 0.00001) text.replace(placeholderIndex, placeholderIndex + 3, Math.round(d) + "");
            else text.replace(placeholderIndex, placeholderIndex + 3, d + "");
        }
        if(text.length() > maxTextBuffer) text.setLength(maxTextBuffer);
    }
}
