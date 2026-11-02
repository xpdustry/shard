package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;

import static com.xpdustry.shard.logic.lang.LogicOp.*;

/**
 * mlog value semantics. A slot is numeric when {@code o[s] == NUM}, with its value in {@code n[s]}, which is always
 * finite; otherwise it holds the object in {@code o[s]}, and null is mlog's null. That is vanilla's
 * {@code LVar{isobj, objval, numval}} without one object per variable, including the stale {@code numval} an object
 * write leaves behind (which {@code @counter} relies on). {@code k[s]} is the constant flag.
 */
final class Rt{
    private Rt(){
    }

    /** The marker for "number in n[s]". */
    static final Object NUM = new Object(){
        @Override
        public String toString(){
            return "<num>";
        }
    };

    private static final LogicOp[] ops = LogicOp.values();
    private static final ConditionOp[] conds = ConditionOp.values();
    private static final boolean[] unary = new boolean[ops.length];

    static{
        for(LogicOp op : ops) unary[op.ordinal()] = op.unary();
    }

    /** {@code LVar.num()}: null is 0 and any other object is 1. */
    static double num(double[] n, Object[] o, int s){
        Object x = o[s];
        return x == NUM ? n[s] : x == null ? 0 : 1;
    }

    /** {@code LVar.bool()}: an object is true unless null; a number is true when {@code |x| >= 1e-5}. */
    static boolean bool(double[] n, Object[] o, int s){
        Object x = o[s];
        return x == NUM ? Math.abs(n[s]) >= 0.00001 : x != null;
    }

    /** {@code LVar.obj()}: null for numbers. */
    static Object obj(Object[] o, int s){
        Object x = o[s];
        return x == NUM ? null : x;
    }

    /** {@code LVar.setnum}: NaN and infinities become null, keeping the old number (it matters for {@code @counter}). */
    static void setnum(double[] n, Object[] o, boolean[] k, int s, double v){
        if(k[s]) return;
        if(Double.isFinite(v)){
            n[s] = v;
            o[s] = NUM;
        }else{
            o[s] = null;
        }
    }

    static void setobj(Object[] o, boolean[] k, int s, Object v){
        if(k[s]) return;
        o[s] = v;
    }

    /** {@code SetI}: {@code LVar.set}, unless the destination is a constant. */
    static void set(double[] n, Object[] o, boolean[] k, int to, int from){
        if(k[to]) return;
        Object x = o[from];
        o[to] = x;
        if(x == NUM) n[to] = n[from]; // already finite
    }

    /** {@code Structs.eq}. */
    static boolean eq(Object a, Object b){
        return a == b || a != null && a.equals(b);
    }

    /** {@code ConditionOp.test}. */
    static boolean cond(int c, double[] n, Object[] o, int a, int b){
        Object oa = o[a], ob = o[b];
        switch(conds[c]){
            case strictEqual:
                return (oa == NUM) == (ob == NUM) && (oa != NUM ? eq(oa, ob) : n[a] == n[b]);
            case equal:
                if(oa != NUM && ob != NUM) return eq(oa, ob);
                return Math.abs(num(n, o, a) - num(n, o, b)) < 0.000001;
            case notEqual:
                if(oa != NUM && ob != NUM) return !eq(oa, ob);
                return Math.abs(num(n, o, a) - num(n, o, b)) >= 0.000001;
            case lessThan:
                return num(n, o, a) < num(n, o, b);
            case lessThanEq:
                return num(n, o, a) <= num(n, o, b);
            case greaterThan:
                return num(n, o, a) > num(n, o, b);
            case greaterThanEq:
                return num(n, o, a) >= num(n, o, b);
            default:
                return true;
        }
    }

    /** {@code OpI.run}. */
    static void op(Processor p, int op, int a, int b, int dest){
        double[] n = p.n;
        Object[] o = p.o;
        boolean[] k = p.k;
        if(op == strictEqual.ordinal()){
            setnum(n, o, k, dest, cond(ConditionOp.strictEqual.ordinal(), n, o, a, b) ? 1 : 0);
        }else if(unary[op]){
            setnum(n, o, k, dest, unary(p, op, num(n, o, a)));
        }else if((op == equal.ordinal() || op == notEqual.ordinal()) && o[a] != NUM && o[b] != NUM){
            // the object function, when both are objects
            boolean e = eq(o[a], o[b]);
            setnum(n, o, k, dest, (op == equal.ordinal()) == e ? 1 : 0);
        }else{
            setnum(n, o, k, dest, binary(op, num(n, o, a), num(n, o, b)));
        }
    }

    static double binary(int op, double a, double b){
        return switch(ops[op]){
            case add -> a + b;
            case sub -> a - b;
            case mul -> a * b;
            case div -> a / b;
            case idiv -> Math.floor(a / b);
            case mod -> a % b;
            case emod -> ((a % b) + b) % b;
            case pow -> StrictMath.pow(a, b);
            case equal -> Math.abs(a - b) < 0.000001 ? 1 : 0;
            case notEqual -> Math.abs(a - b) < 0.000001 ? 0 : 1;
            case land -> a != 0 && b != 0 ? 1 : 0;
            case lessThan -> a < b ? 1 : 0;
            case lessThanEq -> a <= b ? 1 : 0;
            case greaterThan -> a > b ? 1 : 0;
            case greaterThanEq -> a >= b ? 1 : 0;
            // (long) casts saturate and shift counts are taken & 63, as in Java
            case shl -> (long)a << (long)b;
            case shr -> (long)a >> (long)b;
            case ushr -> (long)a >>> (long)b;
            case or -> (long)a | (long)b;
            case and -> (long)a & (long)b;
            case xor -> (long)a ^ (long)b;
            case max -> Math.max(a, b);
            case min -> Math.min(a, b);
            case angle -> MlogMath.angle(a, b);
            case angleDiff -> MlogMath.angleDiff(a, b);
            case len -> MlogMath.len(a, b);
            case noise -> MlogMath.noise(0, a, b);
            case logn -> StrictMath.log(a) / StrictMath.log(b);
            default -> 0;
        };
    }

    static double unary(Processor p, int op, double a){
        return switch(ops[op]){
            case not -> ~(long)a;
            case abs -> Math.abs(a);
            case sign -> Math.signum(a);
            case log -> StrictMath.log(a);
            case log10 -> StrictMath.log10(a);
            case floor -> Math.floor(a);
            case ceil -> Math.ceil(a);
            case round -> Math.round(a);
            case sqrt -> Math.sqrt(a);
            case rand -> p.world.rand().nextDouble() * a;
            case sin -> StrictMath.sin(a * MlogMath.doubleDegRad);
            case cos -> StrictMath.cos(a * MlogMath.doubleDegRad);
            case tan -> StrictMath.tan(a * MlogMath.doubleDegRad);
            case asin -> StrictMath.asin(a) * MlogMath.doubleRadDeg;
            case acos -> StrictMath.acos(a) * MlogMath.doubleRadDeg;
            case atan -> StrictMath.atan(a) * MlogMath.doubleRadDeg;
            default -> 0;
        };
    }
}
