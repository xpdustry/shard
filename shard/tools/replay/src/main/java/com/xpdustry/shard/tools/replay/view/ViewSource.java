package com.xpdustry.shard.tools.replay.view;

import java.util.*;
import java.util.function.*;

/**
 * A world as a viewer sees it, read at a tick boundary. {@link ViewWriter} samples it every few ticks and encodes the
 * differences. Implementations read their world directly and allocate little: the writer calls them up to 60 times a
 * second of game time.
 */
public interface ViewSource{
    /** Who recorded it, for people: {@code vanilla v160.7 oracle}, {@code shard <build>}. */
    String producer();

    /** The game version whose content the names refer to, such as {@code v160.7}; it picks the viewer's asset pack. */
    String game();

    /** The content epoch's hash, or empty when the producer has none (vanilla). */
    String content();

    String map();

    int width();

    int height();

    /** The world tick: 60 per second of game time. */
    long tick();

    /** Every team the world has used so far. */
    List<TeamView> teams();

    /** A block's name by content id, for {@link #terrain}. */
    String blockName(int id);

    /**
     * The terrain now, as block content ids by tile ({@code y * width + x}): the floor, the overlay, and the block of
     * every tile that holds no building (static walls, boulders, air). Buildings come from {@link #buildings}.
     */
    void terrain(short[] floor, short[] overlay, short[] env);

    void buildings(Consumer<BuildView> out);

    void units(Consumer<UnitView> out);

    void bullets(Consumer<BulletView> out);

    /** The events since the last call, oldest first. */
    List<ViewEvent> events();

    Status status();

    /** A team: id, name and colour as RGBA8888. */
    record TeamView(int id, String name, int color){
    }

    /**
     * A building, at its tile ({@code y * width + x}; vanilla's {@code build.tile}).
     *
     * @param rotation 0–3, vanilla's {@code build.rotation}
     * @param health 0–1
     * @param aim a turret's rotation in degrees, if {@link #AIM} is set
     * @param target the block a construction site builds or deconstructs, or null
     * @param progress construction progress 0–1
     */
    record BuildView(int tile, String block, int team, int rotation, float health, int flags, float aim, String target,
                     float progress){
        /** Working: a crafter crafting, a drill drilling. Viewers animate it. */
        public static final int ACTIVE = 1;
        /** {@link #aim()} is meaningful (turrets). */
        public static final int AIM = 2;
        public static final int CONSTRUCT = 4;
        public static final int DECONSTRUCT = 8;
        public static final int SHOOTING = 16;
    }

    /**
     * A unit.
     *
     * @param rotation degrees
     * @param health 0–1
     * @param elevation 0 on the ground, 1 flying
     * @param player the controlling player's name, or null
     */
    record UnitView(int id, String type, int team, float x, float y, float rotation, float health, float elevation, int flags,
                    String player){
        public static final int SHOOTING = 1;
        public static final int BOOSTING = 2;
    }

    /**
     * A bullet.
     *
     * @param age ticks since it was shot
     */
    record BulletView(int id, BulletStyle style, int team, float x, float y, float rotation, float age){
    }

    /**
     * How a bullet type looks, so a viewer draws it without its code. {@code kind} names the vanilla class family
     * ({@code basic}, {@code artillery}, {@code missile}, {@code laser}, {@code lightning}…); colours are RGBA8888.
     */
    record BulletStyle(String kind, String sprite, float width, float height, int front, int back, float shrinkY, float lifetime,
                       int trailLength, float trailWidth, int trailColor, float hitSize){
    }

    /**
     * Something that happened between two frames: {@code unit-death}, {@code block-destroy}, {@code wave}…
     *
     * @param content the unit type or block involved, or null
     * @param size the unit's hit size or the block's size in tiles
     */
    record ViewEvent(String kind, float x, float y, float rotation, int team, String content, float size){
    }

    /** The game's state for a HUD. {@code countdown} is in ticks. */
    record Status(int wave, float countdown, int enemies){
    }
}
