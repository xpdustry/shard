package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * Vanilla's {@code Administration.PlayerAction} (Administration.java:700): what a player asks to do, as one immutable
 * value instead of vanilla's pooled, mutable one. Fields a type doesn't set keep their defaults: -1 for positions and
 * content ids, null for references and arrays, 0 for amounts. The arrays are the intent's own: read them, don't keep
 * or change them.
 *
 * @param player the acting player, in the world the filter runs on
 * @param session the player's session
 * @param tile the tile acted on as {@code Point2.pack(x, y)}, or -1: the building's origin for building actions, the
 *     plan's tile for plans, the unit's tile for {@code dropPayload}
 * @param block {@code placeBlock}: the block's content id
 * @param rotation {@code placeBlock}: the plan's rotation; {@code rotate}: the rotation the building would get
 * @param config {@code configure}: the value, as the sim reads configs; {@code placeBlock}: the plan's config
 * @param item {@code withdrawItem}, {@code depositItem}: the item's content id
 * @param itemAmount the amount asked for, or carried
 * @param unit {@code control}: the unit asked for (null to let go); {@code pickupBlock}: the player's unit
 * @param payload {@code dropPayload}: the payload on top
 * @param plans {@code removePlanned}: packed positions
 * @param unitIds {@code commandUnits}: the units' entity ids
 * @param unitCommand {@code commandUnits} from {@code setUnitCommand}: the command id, else -1
 * @param buildingPositions {@code commandBuilding}: packed positions
 */
public record PlayerAction(Player player, long session, ActionType type, int tile, int block, int rotation, Object config, int item,
                           int itemAmount, Unit unit, Payload payload, int[] plans, int[] unitIds, int unitCommand, int[] buildingPositions,
                           String pingText, float pingX, float pingY){
    public PlayerAction{
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
    }

    /** A {@code type} action by {@code player} with nothing else set yet. */
    public static Builder of(Player player, long session, ActionType type){
        return new Builder(player, session, type);
    }

    /** The tile's x, or -1. */
    public int tileX(){
        return tile == -1 ? -1 : Point2.x(tile);
    }

    /** The tile's y, or -1. */
    public int tileY(){
        return tile == -1 ? -1 : Point2.y(tile);
    }

    /** Sets fields before the action is built; vanilla's {@code allowAction} setter lambda. */
    public static final class Builder{
        private final Player player;
        private final long session;
        private final ActionType type;
        private int tile = -1, block = -1, rotation, item = -1, itemAmount, unitCommand = -1;
        private Object config;
        private Unit unit;
        private Payload payload;
        private int[] plans, unitIds, buildingPositions;
        private String pingText;
        private float pingX, pingY;

        private Builder(Player player, long session, ActionType type){
            this.player = player;
            this.session = session;
            this.type = type;
        }

        public Builder tile(int x, int y){
            tile = Point2.pack(x, y);
            return this;
        }

        public Builder block(int block, int rotation, Object config){
            this.block = block;
            this.rotation = rotation;
            this.config = config;
            return this;
        }

        public Builder rotation(int rotation){
            this.rotation = rotation;
            return this;
        }

        public Builder config(Object config){
            this.config = config;
            return this;
        }

        public Builder item(int item, int amount){
            this.item = item;
            this.itemAmount = amount;
            return this;
        }

        public Builder unit(Unit unit){
            this.unit = unit;
            return this;
        }

        public Builder payload(Payload payload){
            this.payload = payload;
            return this;
        }

        public Builder plans(int[] plans){
            this.plans = plans;
            return this;
        }

        public Builder units(int[] ids, int command){
            this.unitIds = ids;
            this.unitCommand = command;
            return this;
        }

        public Builder buildings(int[] positions){
            this.buildingPositions = positions;
            return this;
        }

        public Builder ping(float x, float y, String text){
            pingX = x;
            pingY = y;
            pingText = text;
            return this;
        }

        public PlayerAction build(){
            return new PlayerAction(player, session, type, tile, block, rotation, config, item, itemAmount, unit, payload, plans, unitIds,
                unitCommand, buildingPositions, pingText, pingX, pingY);
        }
    }
}
