package com.xpdustry.shard.wire;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.ServerDecode.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4's engine: mutates bodies vanilla wrote and compares vanilla's verdict on each with Shard's. Properties: the same
 * verdict, the same bytes consumed when both accept, and no exception from Shard but {@link WireException}. Where
 * vanilla rejects only a JSON blob's content, Shard accepts it ({@link Verdict#json}). Seeded per decoder, so a failure
 * reproduces.
 *
 * <p>Budget, set from Gradle: {@code -Pshard.fuzzCases=N} runs at least N cases per decoder (default 4,000), and
 * {@code -Pshard.fuzzMinutes=M} keeps a test class fuzzing for at least M minutes, shared evenly by its decoders.
 */
final class Fuzz{
    static final int CASES = Integer.getInteger("com.xpdustry.shard.wire.fuzzCases", 4000);
    static final double MINUTES = Double.parseDouble(System.getProperty("com.xpdustry.shard.wire.fuzzMinutes", "0"));
    static final int[] INTERESTING = {-1, 0, 1, 2, 19, 20, 21, 63, 64, 65, 127, 128, 199, 200, 201, 255, 256, 499, 500, 501, 999, 1000, 1001,
        1199, 1200, 1201, 6000, 32767, 32768, 39999, 40000, 40001, 59999, 60000, 65535, 65536, 99999, 100000, 100001, Integer.MAX_VALUE,
        Integer.MIN_VALUE};

    private Fuzz(){}

    interface Decoder{
        Verdict decode(byte[] body);
    }

    /** The time one of {@code decoders} decoders gets, in nanoseconds. */
    static long budget(long decoders){
        return (long)(MINUTES * 60e9 / Math.max(1, decoders));
    }

    static void fuzz(String name, int seed, List<byte[]> seeds, Decoder vanilla, Decoder shard, long budget){
        Random rnd = new Random(seed * 31L + 7);
        long deadline = System.nanoTime() + budget;
        List<String> mismatches = new ArrayList<>();
        int accepted = 0, json = 0;
        for(byte[] body : seeds){
            //unmutated, both accept and consume the whole body
            Verdict v = vanilla.decode(body), s = shard.decode(body);
            assertTrue(v.accepted() && v.same(s), () -> name + " seed body: vanilla " + v + ", Shard " + s + " on " + Hex.of(body));
        }
        int cases = 0;
        for(; cases < CASES || System.nanoTime() < deadline; cases++){
            byte[] body = mutate(rnd, seeds);
            Verdict v = vanilla.decode(body), s;
            try{
                s = shard.decode(body);
            }catch(Throwable t){
                throw new AssertionError(name + ": Shard threw " + t + " on " + Hex.of(body), t);
            }
            if(v.accepted()) accepted++;
            if(v.json()) json++;
            if(!v.same(s) && mismatches.size() < 10) mismatches.add("vanilla " + v + ", Shard " + s + " on " + Hex.of(body));
        }
        assertEquals(List.of(), mismatches, name);
        System.out.printf("%s: %d cases, %d accepted by both, %d rejected by both, %d only for their JSON%n", name, cases, accepted,
            cases - accepted - json, json);
    }

    static byte[] mutate(Random rnd, List<byte[]> seeds){
        byte[] body = seeds.get(rnd.nextInt(seeds.size())).clone();
        int rounds = 1 + rnd.nextInt(3);
        for(int r = 0; r < rounds; r++){
            body = switch(rnd.nextInt(9)){
                case 0 -> {
                    if(body.length > 0) body[rnd.nextInt(body.length)] ^= (byte)(1 << rnd.nextInt(8));
                    yield body;
                }
                case 1 -> {
                    if(body.length > 0) body[rnd.nextInt(body.length)] = (byte)new int[]{0, 1, 0x7F, 0x80, 0xFE, 0xFF, 0xC0, 0xED, 0xF0}[rnd.nextInt(9)];
                    yield body;
                }
                case 2 -> Arrays.copyOf(body, rnd.nextInt(body.length + 1));
                case 3 -> {
                    //an interesting number over a 1-, 2- or 4-byte window: counts, lengths, tags
                    int width = new int[]{1, 2, 4}[rnd.nextInt(3)];
                    if(body.length < width) yield body;
                    int at = rnd.nextInt(body.length - width + 1), value = INTERESTING[rnd.nextInt(INTERESTING.length)];
                    for(int k = 0; k < width; k++) body[at + k] = (byte)(value >>> (8 * (width - 1 - k)));
                    yield body;
                }
                case 4 -> {
                    int at = rnd.nextInt(body.length + 1);
                    byte[] insert = new byte[1 + rnd.nextInt(8)];
                    rnd.nextBytes(insert);
                    byte[] out = new byte[body.length + insert.length];
                    System.arraycopy(body, 0, out, 0, at);
                    System.arraycopy(insert, 0, out, at, insert.length);
                    System.arraycopy(body, at, out, at + insert.length, body.length - at);
                    yield out;
                }
                case 5 -> {
                    if(body.length == 0) yield body;
                    int from = rnd.nextInt(body.length), to = from + 1 + rnd.nextInt(Math.min(8, body.length - from));
                    byte[] out = new byte[body.length - (to - from)];
                    System.arraycopy(body, 0, out, 0, from);
                    System.arraycopy(body, to, out, from, body.length - to);
                    yield out;
                }
                case 6 -> {
                    byte[] out = new byte[rnd.nextInt(48)];
                    rnd.nextBytes(out);
                    yield out;
                }
                case 7 -> {
                    //a big string or array where a value starts: long tails reach the size limits
                    byte[] tail = new byte[rnd.nextInt(2048)];
                    Arrays.fill(tail, (byte)(rnd.nextBoolean() ? 'a' : 0));
                    byte[] out = Arrays.copyOf(body, body.length + tail.length);
                    System.arraycopy(tail, 0, out, body.length, tail.length);
                    yield out;
                }
                default -> {
                    byte[] other = seeds.get(rnd.nextInt(seeds.size()));
                    int cut = rnd.nextInt(body.length + 1), from = rnd.nextInt(other.length + 1);
                    byte[] out = Arrays.copyOf(body, cut + other.length - from);
                    System.arraycopy(other, from, out, cut, other.length - from);
                    yield out;
                }
            };
        }
        return body;
    }
}
