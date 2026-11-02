package com.xpdustry.shard.compat.v160.blocks.environment;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.tile.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class TileDataCodecTest{
    @Test
    void theSavedRecordIsSevenBigEndianBytes(){
        var tiles = new TileGrid(4, 4);
        int pos = tiles.pos(2, 1);
        tiles.data(pos, (byte)5);
        tiles.floorData(pos, (byte)-2);
        tiles.overlayData(pos, (byte)0x7f);
        tiles.extraData(pos, 0x01020304);
        var out = new Writes();
        TileDataCodec.write(out, tiles, pos);
        assertArrayEquals(new byte[]{5, -2, 0x7f, 1, 2, 3, 4}, out.toByteArray());
        assertEquals(TileDataCodec.SAVED_BYTES, out.size());

        var copy = new TileGrid(4, 4);
        TileDataCodec.read(new Reads(out.toByteArray()), copy, pos);
        assertEquals(TileDataCodec.packed(tiles, pos), TileDataCodec.packed(copy, pos));
    }

    @Test
    void thePackedLongMatchesPackedTileData(){
        // PackedTileData: extraData 0..31, data 32..39, floorData 40..47, overlayData 48..55
        assertEquals(0x00_7f_fe_05_ffff_fff0L, TileDataCodec.pack(-16, (byte)5, (byte)-2, (byte)0x7f));
        var tiles = new TileGrid(2, 2);
        TileDataCodec.unpack(0x00_7f_fe_05_ffff_fff0L, tiles, 3);
        assertEquals(-16, tiles.extraData(3));
        assertEquals(5, tiles.data(3));
        assertEquals(-2, tiles.floorData(3));
        assertEquals(0x7f, tiles.overlayData(3));
    }
}
