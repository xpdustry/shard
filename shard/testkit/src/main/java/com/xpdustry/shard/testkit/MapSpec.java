package com.xpdustry.shard.testkit;

import java.util.*;

/** The world a scenario starts from. */
public sealed interface MapSpec{
    /** An empty, coreless stone world in play, as the oracle's {@code Vanilla.emptyWorld} (no game over). */
    record Empty(int width, int height) implements MapSpec{
        public Empty{
            if(width <= 0 || height <= 0) throw new IllegalArgumentException("bad world size " + width + "×" + height);
        }
    }

    /**
     * A bundled or {@code .msav} map, hosted in survival as the server's {@code host} command does. Names as the
     * oracle's {@code Vanilla.findMap}: {@code fortress}, {@code serpulo/groundZero}, {@code Ancient Caldera}…
     */
    record Bundled(String map) implements MapSpec{
        public Bundled{
            Objects.requireNonNull(map, "map");
        }
    }

    static MapSpec empty(int width, int height){
        return new Empty(width, height);
    }

    static MapSpec bundled(String map){
        return new Bundled(map);
    }
}
