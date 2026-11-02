package com.xpdustry.shard.net;

import net.jpountz.lz4.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Shard's LZ4 against lz4-java from the vanilla jar, the codec vanilla's clients and server use. */
class Lz4Test{
    /** The pure-Java instance: the native one needs native access, and the format is the same. */
    static final LZ4Factory VANILLA = LZ4Factory.fastestJavaInstance();

    /** Packet-like data: runs, repeats and noise in varying mixes, lengths 0 to 32 KiB. */
    static byte[] sample(Random random, int iteration){
        int length = random.nextInt(iteration % 10 == 0 ? 32_768 : 900);
        byte[] src = new byte[length];
        int alphabet = 1 + random.nextInt(iteration % 3 == 0 ? 256 : 8);
        for(int i = 0; i < length; i++){
            src[i] = (byte)(random.nextInt(4) == 0 ? random.nextInt(alphabet) : i > 8 ? src[i - 1 - random.nextInt(8)] : 0);
        }
        return src;
    }

    @Test
    void vanillaReadsOursAndWeReadVanillas(){
        var random = new Random(1);
        var lz4 = new Lz4();
        long ours = 0, theirs = 0;
        for(int iteration = 0; iteration < 5_000; iteration++){
            byte[] src = sample(random, iteration);
            byte[] dst = new byte[Lz4.bound(src.length)];
            int written = lz4.compress(src, 0, src.length, dst, 0);
            byte[] back = new byte[src.length];
            VANILLA.safeDecompressor().decompress(dst, 0, written, back, 0, src.length);
            assertArrayEquals(src, back, "lz4-java reads ours, length " + src.length);

            byte[] vanilla = VANILLA.fastCompressor().compress(src);
            assertArrayEquals(src, Lz4.decompress(vanilla, 0, vanilla.length, src.length), "we read lz4-java's, length " + src.length);
            ours += written;
            theirs += vanilla.length;
        }
        // the greedy matcher is not lz4-java's, but it should compress about as well
        assertTrue(ours < theirs * 1.1, "ours " + ours + " B vs lz4-java " + theirs + " B");
    }

    @Test
    void compressesAtAnOffset(){
        byte[] src = "padding:".concat("abcabcabcabcabcabcabcabcabcabcabcabcabcabc").getBytes();
        byte[] dst = new byte[10 + Lz4.bound(src.length - 8)];
        int written = new Lz4().compress(src, 8, src.length - 8, dst, 10);
        assertArrayEquals(Arrays.copyOfRange(src, 8, src.length), Lz4.decompress(dst, 10, written, src.length - 8));
        assertTrue(written < src.length - 8);
    }

    @Test
    void malformedBlocksAreRejectedCleanly(){
        var random = new Random(2);
        var lz4 = new Lz4();
        for(int iteration = 0; iteration < 20_000; iteration++){
            byte[] block;
            int length;
            if(iteration % 2 == 0){
                block = new byte[random.nextInt(64)];
                random.nextBytes(block);
                length = random.nextInt(256);
            }else{
                // a valid block with one byte flipped, or cut short
                byte[] src = sample(random, iteration);
                byte[] dst = new byte[Lz4.bound(src.length)];
                int written = lz4.compress(src, 0, src.length, dst, 0);
                block = Arrays.copyOf(dst, Math.max(0, written - random.nextInt(2)));
                if(block.length > 0) block[random.nextInt(block.length)] ^= (byte)(1 + random.nextInt(255));
                length = src.length;
            }
            try{
                assertEquals(length, Lz4.decompress(block, 0, block.length, length).length);
            }catch(IllegalArgumentException expected){
                assertTrue(expected.getMessage().startsWith("LZ4: corrupt block"), expected.getMessage());
            }
        }
    }

    @Test
    void theWrongLengthIsCorrupt(){
        byte[] src = new byte[100];
        byte[] dst = new byte[Lz4.bound(100)];
        int written = new Lz4().compress(src, 0, 100, dst, 0);
        assertThrows(IllegalArgumentException.class, () -> Lz4.decompress(dst, 0, written, 99));
        assertThrows(IllegalArgumentException.class, () -> Lz4.decompress(dst, 0, written, 101));
    }
}
