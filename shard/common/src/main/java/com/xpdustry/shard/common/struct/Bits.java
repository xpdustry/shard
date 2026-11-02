package com.xpdustry.shard.common.struct;

/**
 * Arc's {@code arc.struct.Bits}: a growable bit set. Quirks kept: {@link #get(int)} masks the shift implicitly,
 * {@link #nextClearBit(int)} returns {@code numBits()} when every stored bit is set, the array grows only to the word
 * it needs, and {@link #equals(Object)} compares logical length when the arrays differ in size.
 */
public final class Bits{
    long[] bits = {0};

    public Bits(){
    }

    /** Large enough for bits 0 through {@code nbits - 1}, as Arc sizes it (one word more when nbits is a multiple of 64). */
    public Bits(int nbits){
        checkCapacity(nbits >>> 6);
    }

    /** A deep copy with the same capacity, for world forks. */
    public Bits copy(){
        Bits out = new Bits();
        out.bits = bits.clone();
        return out;
    }

    /** Copies {@code other}'s words into this set, up to the shorter length. */
    public void set(Bits other){
        int length = Math.min(bits.length, other.bits.length);
        System.arraycopy(other.bits, 0, bits, 0, length);
    }

    public boolean get(int index){
        final int word = index >>> 6;
        return word < bits.length && (bits[word] & (1L << (index))) != 0L;
    }

    /** Clears the bit and returns whether it was set. */
    public boolean getAndClear(int index){
        final int word = index >>> 6;
        if(word >= bits.length) return false;
        long oldBits = bits[word];
        bits[word] &= ~(1L << (index & 0x3F));
        return bits[word] != oldBits;
    }

    /** Sets the bit and returns whether it was already set. */
    public boolean getAndSet(int index){
        final int word = index >>> 6;
        checkCapacity(word);
        long oldBits = bits[word];
        bits[word] |= 1L << (index & 0x3F);
        return bits[word] == oldBits;
    }

    public void set(int index, boolean value){
        if(value){
            set(index);
        }else{
            clear(index);
        }
    }

    public void set(int index){
        final int word = index >>> 6;
        checkCapacity(word);
        bits[word] |= 1L << (index & 0x3F);
    }

    /** Sets [from, to). */
    public void set(int from, int to){
        if(from == to) return;
        int startWordIndex = from >>> 6;
        int endWordIndex = (to - 1) >>> 6;
        checkCapacity(endWordIndex);
        long mask = 0xffffffffffffffffL;
        long firstWordMask = mask << from;
        long lastWordMask = mask >>> -to;
        if(startWordIndex == endWordIndex){
            bits[startWordIndex] |= (firstWordMask & lastWordMask);
        }else{
            bits[startWordIndex] |= firstWordMask;
            for(int i = startWordIndex + 1; i < endWordIndex; i++)
                bits[i] = mask;
            bits[endWordIndex] |= lastWordMask;
        }
    }

    public void flip(int index){
        final int word = index >>> 6;
        checkCapacity(word);
        bits[word] ^= 1L << (index & 0x3F);
    }

    private void checkCapacity(int len){
        if(len >= bits.length){
            long[] newBits = new long[len + 1];
            System.arraycopy(bits, 0, newBits, 0, bits.length);
            bits = newBits;
        }
    }

    public void clear(int index){
        final int word = index >>> 6;
        if(word >= bits.length) return;
        bits[word] &= ~(1L << (index & 0x3F));
    }

    public void clear(){
        java.util.Arrays.fill(bits, 0L);
    }

    /** Bits currently stored, not the highest set bit. */
    public int numBits(){
        return bits.length << 6;
    }

    /** The index of the highest set bit plus one, or 0. */
    public int length(){
        long[] bits = this.bits;
        for(int word = bits.length - 1; word >= 0; --word){
            long bitsAtWord = bits[word];
            if(bitsAtWord != 0){
                return (word << 6) + 64 - Long.numberOfLeadingZeros(bitsAtWord);
            }
        }
        return 0;
    }

    public boolean isEmpty(){
        for(long word : bits){
            if(word != 0L) return false;
        }
        return true;
    }

    /** The first set bit at or after {@code fromIndex}, or -1. */
    public int nextSetBit(int fromIndex){
        long[] bits = this.bits;
        int word = fromIndex >>> 6;
        int bitsLength = bits.length;
        if(word >= bitsLength) return -1;
        long bitsAtWord = bits[word] & (-1L << (fromIndex & 0x3F));
        if(bitsAtWord != 0) return (word << 6) + Long.numberOfTrailingZeros(bitsAtWord);
        for(word++; word < bitsLength; word++){
            bitsAtWord = bits[word];
            if(bitsAtWord != 0) return (word << 6) + Long.numberOfTrailingZeros(bitsAtWord);
        }
        return -1;
    }

    /** The first clear bit at or after {@code fromIndex}; {@link #numBits()} if every stored bit from there is set. */
    public int nextClearBit(int fromIndex){
        long[] bits = this.bits;
        int word = fromIndex >>> 6;
        int bitsLength = bits.length;
        if(word >= bitsLength) return bits.length << 6;
        long clearAtWord = ~bits[word] & (-1L << (fromIndex & 0x3F));
        if(clearAtWord != 0) return (word << 6) + Long.numberOfTrailingZeros(clearAtWord);
        for(word++; word < bitsLength; word++){
            clearAtWord = ~bits[word];
            if(clearAtWord != 0) return (word << 6) + Long.numberOfTrailingZeros(clearAtWord);
        }
        return bits.length << 6;
    }

    public void and(Bits other){
        int commonWords = Math.min(bits.length, other.bits.length);
        for(int i = 0; commonWords > i; i++){
            bits[i] &= other.bits[i];
        }
        for(int i = commonWords, s = bits.length; s > i; i++){
            bits[i] = 0L;
        }
    }

    public void andNot(Bits other){
        for(int i = 0, j = bits.length, k = other.bits.length; i < j && i < k; i++){
            bits[i] &= ~other.bits[i];
        }
    }

    /** Quirk kept: when {@code other} is longer, this set grows to {@code other.bits.length + 1} words. */
    public void or(Bits other){
        int commonWords = Math.min(bits.length, other.bits.length);
        for(int i = 0; commonWords > i; i++){
            bits[i] |= other.bits[i];
        }
        if(commonWords < other.bits.length){
            checkCapacity(other.bits.length);
            for(int i = commonWords, s = other.bits.length; s > i; i++){
                bits[i] = other.bits[i];
            }
        }
    }

    /** Quirk kept: as {@link #or(Bits)}, this set grows to {@code other.bits.length + 1} words. */
    public void xor(Bits other){
        int commonWords = Math.min(bits.length, other.bits.length);
        for(int i = 0; commonWords > i; i++){
            bits[i] ^= other.bits[i];
        }
        if(commonWords < other.bits.length){
            checkCapacity(other.bits.length);
            for(int i = commonWords, s = other.bits.length; s > i; i++){
                bits[i] = other.bits[i];
            }
        }
    }

    public boolean intersects(Bits other){
        long[] bits = this.bits;
        long[] otherBits = other.bits;
        for(int i = Math.min(bits.length, otherBits.length) - 1; i >= 0; i--){
            if((bits[i] & otherBits[i]) != 0){
                return true;
            }
        }
        return false;
    }

    public boolean containsAll(Bits other){
        long[] bits = this.bits;
        long[] otherBits = other.bits;
        int otherBitsLength = otherBits.length;
        int bitsLength = bits.length;
        for(int i = bitsLength; i < otherBitsLength; i++){
            if(otherBits[i] != 0){
                return false;
            }
        }
        for(int i = Math.min(bitsLength, otherBitsLength) - 1; i >= 0; i--){
            if((bits[i] & otherBits[i]) != otherBits[i]){
                return false;
            }
        }
        return true;
    }

    /** Arc's hash, except that Arc reads past the array when bit 63 of the last word is set; this stops at the end. */
    @Override
    public int hashCode(){
        final int word = Math.min(length() >>> 6, bits.length - 1);
        int hash = 0;
        for(int i = 0; word >= i; i++){
            hash = 127 * hash + (int)(bits[i] ^ (bits[i] >>> 32));
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj){
        if(this == obj) return true;
        if(!(obj instanceof Bits other)) return false;
        long[] otherBits = other.bits;
        int commonWords = Math.min(bits.length, otherBits.length);
        for(int i = 0; commonWords > i; i++){
            if(bits[i] != otherBits[i])
                return false;
        }
        if(bits.length == otherBits.length)
            return true;
        return length() == other.length();
    }
}
