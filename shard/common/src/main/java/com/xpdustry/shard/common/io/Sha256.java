package com.xpdustry.shard.common.io;

import java.io.*;

/**
 * SHA-256 (FIPS 180-4) in plain Java, as an {@link OutputStream}: write the message, then call {@link #digest()}.
 * The same digest as {@code MessageDigest.getInstance("SHA-256")}, without the JDK's security providers.
 * {@code Sha256Test} checks it against {@code MessageDigest}.
 */
public final class Sha256 extends OutputStream{
    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    private final int[] h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    private final int[] w = new int[64];
    private final byte[] block = new byte[64];
    private int filled;
    private long length;
    private boolean done;

    /** The digest of {@code bytes}. */
    public static byte[] of(byte[] bytes){
        var sha = new Sha256();
        sha.write(bytes, 0, bytes.length);
        return sha.digest();
    }

    @Override
    public void write(int b){
        if(done) throw new IllegalStateException("digest() was called");
        block[filled++] = (byte)b;
        length++;
        if(filled == 64) compress();
    }

    @Override
    public void write(byte[] bytes, int offset, int count){
        for(int i = offset; i < offset + count; i++) write(bytes[i]);
    }

    /** The 32-byte digest of everything written. The stream takes no more bytes after. */
    public byte[] digest(){
        if(done) throw new IllegalStateException("digest() was called");
        long bits = length * 8;
        write(0x80);
        while(filled != 56) write(0);
        for(int shift = 56; shift >= 0; shift -= 8) write((int)(bits >>> shift));
        done = true;
        var out = new byte[32];
        for(int i = 0; i < 32; i++) out[i] = (byte)(h[i / 4] >>> (24 - i % 4 * 8));
        return out;
    }

    private void compress(){
        for(int i = 0; i < 16; i++){
            w[i] = (block[i * 4] & 0xff) << 24 | (block[i * 4 + 1] & 0xff) << 16 | (block[i * 4 + 2] & 0xff) << 8 | block[i * 4 + 3] & 0xff;
        }
        for(int i = 16; i < 64; i++){
            int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ w[i - 15] >>> 3;
            int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ w[i - 2] >>> 10;
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for(int i = 0; i < 64; i++){
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int t1 = hh + s1 + (e & f ^ ~e & g) + K[i] + w[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int t2 = s0 + (a & b ^ a & c ^ b & c);
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += hh;
        filled = 0;
    }

    /** Lowercase hex of {@code bytes}, two digits a byte, as {@code HexFormat.of().formatHex}. */
    public static String hex(byte[] bytes){
        var out = new char[bytes.length * 2];
        for(int i = 0; i < bytes.length; i++){
            out[i * 2] = Character.forDigit(bytes[i] >>> 4 & 0xf, 16);
            out[i * 2 + 1] = Character.forDigit(bytes[i] & 0xf, 16);
        }
        return new String(out);
    }
}
