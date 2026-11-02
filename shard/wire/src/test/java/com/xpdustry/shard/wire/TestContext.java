package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.geom.*;

/**
 * A small world for codec tests: a 16x16 map, blocks 0-99 of which the even ones rotate, statuses 0-9 of which 5 is dynamic, unit types 0-49, only
 * content type 0 (items) unlockable, synced entity 1 a player (class 12) and 2-9 mech units (class 4).
 */
class TestContext implements WireContext{
    static final TestContext instance = new TestContext();

    @Override
    public boolean statusDynamic(int status){
        return status == 5;
    }

    @Override
    public boolean contentExists(int type, int id){
        return type == Codecs.CONTENT_STATUS ? id >= 0 && id < 10 : type != Codecs.CONTENT_UNIT || id >= 0 && id < 50;
    }

    @Override
    public boolean blockRotates(int block){
        return block % 2 == 0;
    }

    @Override
    public boolean blockExists(int block){
        return block >= 0 && block < 100;
    }

    @Override
    public boolean unlockable(int type, int id){
        return type == 0 && id >= 0 && id < 20;
    }

    @Override
    public int syncedClass(int id){
        return id == 1 ? 12 : id > 1 && id < 10 ? 4 : -1;
    }

    @Override
    public boolean tileExists(int pos){
        int x = Point2.x(pos), y = Point2.y(pos);
        return x >= 0 && y >= 0 && x < 16 && y < 16;
    }
}
