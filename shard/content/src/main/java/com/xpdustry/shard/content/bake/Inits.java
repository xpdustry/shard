package com.xpdustry.shard.content.bake;

/** Registers every ported {@code init()}. */
final class Inits{
    private Inits(){
    }

    static void register(){
        BlockInits.register();
        TurretInits.register();
        BulletInits.register();
        UnitInits.register();
        MiscInits.register();
    }
}
