package com.xpdustry.shard.server.replay.view;

import java.util.*;
import java.util.function.*;

/**
 * A world as a full-vision viewer sees it, read at a tick boundary: what {@link ViewWriter} samples every few ticks into
 * a view stream (format 2, {@code docs/replay/FORMAT.md}). Content is numbered by the source's own tables
 * ({@link #blocks()}, {@link #units()}, {@link #bullets()}, {@link #liquids()}), which list every name the stream may
 * use, so any segment can be written again from its keyframe alone. Implementations read their world directly and
 * allocate little: the writer reads them 20 times a second of game time.
 */
public interface ViewSource{
    /** Who recorded it, for people: {@code shard <build>}. */
    String producer();

    /** The game version whose content the names refer to, such as {@code v160.7}; it picks the viewer's asset pack. */
    String game();

    /** The content epoch's hash, or empty when the producer has none. */
    String content();

    String map();

    int width();

    int height();

    /** The world tick: 60 per second of game time. */
    long tick();

    /** Block names by content id; index 0 is {@code air}. Fixed for the stream. */
    List<String> blocks();

    /** Unit type names by content id. Fixed for the stream. */
    List<String> units();

    /** How each bullet type looks, by content id. Fixed for the stream. */
    List<BulletStyle> bullets();

    /** Liquid names by content id, for puddles. Fixed for the stream. */
    List<String> liquids();

    /** Every team the world has used so far. */
    List<TeamView> teams();

    /** The match's facts for the header: its id, mode and rules. */
    MatchView match();

    /**
     * The terrain now, as block content ids by tile ({@code y * width + x}): the floor, the overlay, and the block of
     * every tile that holds no building (static walls, boulders, air). Buildings come from {@link #buildings}.
     */
    void terrain(short[] floor, short[] overlay, short[] env);

    void buildings(Consumer<BuildView> out);

    void units(Consumer<UnitView> out);

    void bullets(Consumer<BulletView> out);

    /** The players in the world, joined or not yet confirmed. */
    void players(Consumer<PlayerView> out);

    /** The build plan queue of every unit that has one. */
    void plans(Consumer<PlanView> out);

    void fires(Consumer<FireView> out);

    void puddles(Consumer<PuddleView> out);

    /** Every team's rebuild plans: the ghosts of its lost buildings, one per tile. */
    void rebuild(Consumer<RebuildView> out);

    /** The events since the last call, oldest first. */
    List<ViewEvent> events();

    /** Chat since the last call, oldest first. */
    List<ChatView> chat();

    Status status();

    /** A team: id, name and colour as RGBA8888. */
    record TeamView(int id, String name, int color){
    }

    /**
     * @param id the match's id ({@code survival-20261029T171500Z})
     * @param mode {@code survival}, {@code pvp}, {@code attack}…
     */
    record MatchView(String id, String mode, boolean pvp, boolean fog){
    }

    /**
     * A building, at its tile ({@code y * width + x}; vanilla's {@code build.tile}).
     *
     * @param block a {@link #blocks()} index
     * @param rotation 0–3, vanilla's {@code build.rotation}
     * @param health 0–1
     * @param aim a turret's rotation in degrees, if {@link #AIM} is set
     * @param target the block a construction site builds or deconstructs, or -1
     * @param progress construction progress 0–1
     */
    record BuildView(int tile, int block, int team, int rotation, float health, int flags, float aim, int target, float progress){
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
     * @param type a {@link #units()} index
     * @param rotation degrees
     * @param health 0–1
     * @param elevation 0 on the ground, 1 flying
     * @param player the controlling player's id, or -1
     * @param aim where its weapons point, in degrees from the unit, if {@link #AIM} is set
     */
    record UnitView(int id, int type, int team, float x, float y, float rotation, float health, float elevation, int flags, int player,
                    float aim){
        public static final int SHOOTING = 1;
        public static final int BOOSTING = 2;
        public static final int AIM = 4;
    }

    /**
     * A bullet.
     *
     * @param type a {@link #bullets()} index
     * @param age ticks since it was shot: it was shot at {@code tick - age}
     */
    record BulletView(int id, int type, int team, float x, float y, float rotation, int age){
    }

    /**
     * How a bullet type looks, so a viewer draws it without its code. {@code kind} names the vanilla class family
     * ({@code basic}, {@code artillery}, {@code missile}, {@code laser}, {@code lightning}…); colours are RGBA8888.
     */
    record BulletStyle(String name, String kind, String sprite, float width, float height, int front, int back, float shrinkY,
                       float lifetime, int trailLength, float trailWidth, int trailColor, float hitSize){
    }

    /**
     * A player.
     *
     * @param color RGBA8888
     * @param unit the unit it controls, or -1
     */
    record PlayerView(int id, String name, int color, int team, boolean admin, int unit){
    }

    /** A unit's build plans, first first. */
    record PlanView(int unit, List<Plan> plans){
    }

    /**
     * One build plan.
     *
     * @param block a {@link #blocks()} index, or -1 to break what is on the tile
     * @param progress 0–1, how far its construction got
     */
    record Plan(int tile, int block, int rotation, float progress){
    }

    /** A fire, at its tile; {@code life} 1 when it starts, 0 when it goes out. */
    record FireView(int tile, float life){
    }

    /**
     * A puddle, at its tile.
     *
     * @param liquid a {@link #liquids()} index
     * @param amount 0–1 of a full puddle
     */
    record PuddleView(int tile, int liquid, float amount){
    }

    /**
     * A team's plan to rebuild a lost building ({@code TeamData.plans}).
     *
     * @param tile where the building's tile was
     * @param block a {@link #blocks()} index
     * @param rotation 0–3
     */
    record RebuildView(int tile, int team, int block, int rotation){
    }

    /**
     * Something that happened between two frames: {@code unit-death}, {@code block-destroy}, {@code wave},
     * {@code game-over} ({@link ViewWriter#EFFECTS}).
     *
     * @param tick when it happened
     * @param content a unit type for {@code unit-} events, a block for others, or -1
     * @param size the unit's hit size, the block's size in tiles, the wave number or the winning team
     */
    record ViewEvent(long tick, String kind, float x, float y, float rotation, int team, int content, float size){
    }

    /**
     * A chat line.
     *
     * @param player the player's id, or -1 for the server
     */
    record ChatView(long tick, int player, String text){
    }

    /** The game's state for a HUD. {@code countdown} is in ticks. */
    record Status(int wave, float countdown, int enemies){
    }
}
