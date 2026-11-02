package com.xpdustry.shard.sim.world;

/** For oracle tests that call into a world between ticks: hands the events staged since to an observer, as a tick's end does. */
public final class Delivery{
    private Delivery(){
    }

    public static void commit(Observer observer){
        observer.commit();
    }
}
