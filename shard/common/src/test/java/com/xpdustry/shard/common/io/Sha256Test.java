package com.xpdustry.shard.common.io;

import org.junit.jupiter.api.*;

import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Sha256} against {@link MessageDigest}'s SHA-256, and its hex against {@link HexFormat}'s. */
class Sha256Test{
    @Test
    void digestsAsTheJdkDoes() throws NoSuchAlgorithmException{
        var random = new SplittableRandom(256);
        // every length around the 55/56/64-byte padding boundaries, then longer messages written in pieces
        for(int length = 0; length < 300; length++){
            var bytes = new byte[length];
            random.nextBytes(bytes);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), Sha256.of(bytes), "length " + length);
            var pieces = new Sha256();
            for(int at = 0; at < length; ){
                int count = Math.min(length - at, random.nextInt(1, 70));
                pieces.write(bytes, at, count);
                at += count;
            }
            assertArrayEquals(Sha256.of(bytes), pieces.digest(), "length " + length + " in pieces");
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(Sha256.of(new byte[0])));
    }

    @Test
    void hexIsHexFormats(){
        var bytes = new byte[256];
        for(int i = 0; i < bytes.length; i++) bytes[i] = (byte)i;
        assertEquals(HexFormat.of().formatHex(bytes), Sha256.hex(bytes));
    }
}
