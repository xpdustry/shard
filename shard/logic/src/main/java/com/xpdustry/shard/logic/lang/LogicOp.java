package com.xpdustry.shard.logic.lang;

/** Vanilla's {@code LogicOp}, for {@code op}. {@code atan2} and {@code dst} parse as {@link #angle} and {@link #len}. */
public enum LogicOp{
    add, sub, mul, div, idiv, mod, emod, pow,
    equal, notEqual, land, lessThan, lessThanEq, greaterThan, greaterThanEq, strictEqual,
    shl, shr, ushr, or, and, xor, not,
    max, min, angle, angleDiff, len, noise, abs, sign, log, logn, log10, floor, ceil, round, sqrt, rand,
    sin, cos, tan, asin, acos, atan;

    /** Whether the op reads only its first operand. */
    public boolean unary(){
        return switch(this){
            case not, abs, sign, log, log10, floor, ceil, round, sqrt, rand, sin, cos, tan, asin, acos, atan -> true;
            default -> false;
        };
    }
}
