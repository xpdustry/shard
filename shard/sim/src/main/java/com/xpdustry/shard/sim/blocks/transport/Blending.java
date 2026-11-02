package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * What a rotated transport block reads of its neighbours to choose its shape: vanilla's {@code Autotiler} defaults,
 * {@code Edges.getFacingEdge}, and the per-class {@code Block.outputsItems()} and {@code rotatedOutput} overrides
 * they call. Only the blend type ({@code buildBlending(...)[0]}) is simulation state: a conveyor's clog heat and a
 * stack conveyor's state read it. The masks and scales of the other entries only draw, and a server never computes
 * them ({@code StackConveyor}'s {@code blendprox} is behind {@code !headless}).
 *
 * <p>Tiles are (x, y) here, and a kind's own tile and rotation are its building's.
 */
public final class Blending{
    /** A kind's {@code blends(tile, rotation, otherx, othery, otherrot, otherblock)}, for its own tile and rotation. */
    @FunctionalInterface
    public interface Rule{
        boolean blends(int otherx, int othery, int otherrot, BlockDef otherblock);
    }

    private Blending(){
    }

    /** Blocks whose class overrides {@code outputsItems()}, most derived first: the first match answers. */
    private static final List<Map.Entry<KindKey, Boolean>> OUTPUTS_ITEMS = List.of(
        Map.entry(KindKey.of("payload-unloader"), true), Map.entry(KindKey.of("payload-loader"), false),
        Map.entry(KindKey.of("launch-pad"), false), Map.entry(KindKey.of("landing-pad"), true),
        Map.entry(KindKey.of("mend-projector"), false), Map.entry(KindKey.of("overdrive-projector"), false),
        Map.entry(KindKey.of("regen-projector"), false), Map.entry(KindKey.of("force-projector"), false),
        Map.entry(KindKey.of("accelerator"), false), Map.entry(KindKey.of("unit-factory"), false),
        Map.entry(KindKey.of("unit-cargo-loader"), false), Map.entry(KindKey.of("power-generator"), false),
        Map.entry(KindKey.of("solid-pump"), false), Map.entry(KindKey.of("storage-block"), false),
        Map.entry(KindKey.of("turret"), false), Map.entry(KindKey.of("junction"), true),
        Map.entry(KindKey.of("duct-junction"), true), Map.entry(KindKey.of("sorter"), true),
        Map.entry(KindKey.of("overflow-gate"), true), Map.entry(KindKey.of("item-source"), true),
        Map.entry(KindKey.of("beam-drill"), true), Map.entry(KindKey.of("wall-crafter"), true));
    /** Blocks whose class makes {@code rotatedOutput(x, y)} false. */
    private static final List<KindKey> NO_ROTATED_OUTPUT = List.of(KindKey.of("payload-unloader"), KindKey.of("beam-drill"),
        KindKey.of("wall-crafter"), KindKey.of("heater-generator"), KindKey.of("turret"), KindKey.of("duct-router"),
        KindKey.of("overflow-duct"));
    private static final KindKey GENERIC_CRAFTER = KindKey.of("generic-crafter");
    private static final KindKey STACK_CONVEYOR = KindKey.of("stack-conveyor");

    /** {@code block.outputsItems()}: {@code hasItems}, unless the block's class says otherwise. */
    public static boolean outputsItems(BlockDef block){
        if(!block.baked()) return block.hasItems();
        if(block.isA(GENERIC_CRAFTER)) return block.fields().get("outputItems") != null;
        for(var entry : OUTPUTS_ITEMS){
            if(block.isA(entry.getKey())) return entry.getValue();
        }
        return block.hasItems();
    }

    /**
     * {@code block.rotatedOutput(fromX, fromY, destination)}, for a destination that is a transport block: a stack
     * conveyor's depends on its state, and a generic crafter's is false unless the destination is a conduit.
     */
    public static boolean rotatedOutput(World world, BlockDef block, int fromX, int fromY){
        if(!block.baked()) return block.rotate();
        if(block.isA(STACK_CONVEYOR)){
            if(world.buildings().at(fromX, fromY) instanceof StackConveyor stack) return stack.state != StackConveyor.STATE_UNLOAD;
            return block.rotate();
        }
        if(block.isA(GENERIC_CRAFTER)) return false;
        for(KindKey kind : NO_ROTATED_OUTPUT){
            if(block.isA(kind)) return false;
        }
        return block.rotate();
    }

    /**
     * {@code Edges.getFacingEdge(block, x, y, other)}: the tile of a building of {@code block} at (x, y) closest to
     * (otherX, otherY), as {@code y * width + x}.
     */
    public static int facingEdge(World world, BlockDef block, int x, int y, int otherX, int otherY){
        int size = block.size();
        if(size > 1){
            x += Mathf.clamp(otherX - x, -(size - 1) / 2, size / 2);
            y += Mathf.clamp(otherY - y, -(size - 1) / 2, size / 2);
        }
        return world.tiles().inBounds(x, y) ? world.tiles().pos(x, y) : -1;
    }

    /** {@code Edges.getFacingEdge(source.tile, other.tile)}: never null, the source's own tile at worst. */
    public static int facingEdge(Building source, Building other){
        int edge = facingEdge(source.world(), source.block(), source.tileX(), source.tileY(), other.tileX(), other.tileY());
        return edge == -1 ? source.tile() : edge;
    }

    /** {@code Tile.relativeTo(cx, cy)}: the direction from (x, y) to the adjacent tile (cx, cy), or -1. */
    public static int relativeTo(int x, int y, int cx, int cy){
        if(x == cx && y == cy - 1) return 1;
        if(x == cx && y == cy + 1) return 3;
        if(x == cx - 1 && y == cy) return 0;
        if(x == cx + 1 && y == cy) return 2;
        return -1;
    }

    /** {@code relativeTo} from tile {@code pos} to (cx, cy). */
    static int relativeTo(World world, int pos, int cx, int cy){
        return relativeTo(world.tiles().x(pos), world.tiles().y(pos), cx, cy);
    }

    /** {@code facing(x, y, rotation, x2, y2)}: whether (x, y) at {@code rotation} points at (x2, y2). */
    static boolean facing(int x, int y, int rotation, int x2, int y2){
        return x + Geometry.d4x(rotation) == x2 && y + Geometry.d4y(rotation) == y2;
    }

    /** {@code lookingAt}: whether {@code self} points at the other block's tile facing it. */
    static boolean lookingAt(Building self, int otherx, int othery, BlockDef otherblock){
        var tiles = self.world().tiles();
        int facing = facingEdge(self.world(), otherblock, otherx, othery, self.tileX(), self.tileY());
        return facing != -1 && facing(self.tileX(), self.tileY(), self.rotation, tiles.x(facing), tiles.y(facing));
    }

    /** {@code lookingAtEither}: either points at the other, or the other doesn't output along its rotation. */
    static boolean lookingAtEither(Building self, int otherx, int othery, int otherrot, BlockDef otherblock){
        return facing(self.tileX(), self.tileY(), self.rotation, otherx, othery)
            || !rotatedOutput(self.world(), otherblock, otherx, othery)
            || facing(otherx, othery, otherrot, self.tileX(), self.tileY());
    }

    /** {@code notLookingAt}: the other block doesn't point at {@code self}. */
    static boolean notLookingAt(Building self, int otherx, int othery, int otherrot, BlockDef otherblock){
        return !(rotatedOutput(self.world(), otherblock, otherx, othery) && facing(otherx, othery, otherrot, self.tileX(), self.tileY()));
    }

    /**
     * {@code blendsArmored} (Autotiler.java:197): in front, or feeding from behind. Its overrides take a rotated
     * output only from some blocks ({@code ArmoredConveyor}: conveyors, {@code Duct}: ducts): {@code rotatedFrom}, or
     * null for any.
     */
    static boolean blendsArmored(Building self, int otherx, int othery, int otherrot, BlockDef otherblock, Predicate<BlockDef> rotatedFrom){
        World world = self.world();
        int x = self.tileX(), y = self.tileY();
        if(facing(x, y, self.rotation, otherx, othery)) return true;
        boolean rotated = rotatedOutput(world, otherblock, otherx, othery);
        if(!rotated){
            int facing = facingEdge(world, otherblock, otherx, othery, x, y);
            if(facing != -1 && relativeTo(world, facing, x, y) == self.rotation) return true;
        }
        return (rotatedFrom == null || rotatedFrom.test(otherblock)) && rotated && facing(otherx, othery, otherrot, x, y);
    }

    /** {@code blends(tile, rotation, direction)}: the same-team building on that side, relative to the rotation. */
    static boolean blends(Building self, int direction, Rule rule){
        int dir = Mathf.mod(self.rotation - direction, 4);
        Building other = self.world().buildings().at(self.tileX() + Geometry.d4x(dir), self.tileY() + Geometry.d4y(dir));
        return other != null && other.team == self.team && rule.blends(other.tileX(), other.tileY(), other.rotation, other.block());
    }

    /** {@code buildBlending(tile, rotation, null, true)[0]}: 0 straight, 1 curve, 2 and 4 straight with sides, 3 all sides. */
    static int blendBits(Building self, Rule rule){
        boolean left = blends(self, 1, rule), back = blends(self, 2, rule), right = blends(self, 3, rule);
        // the transformCase of (back && left && right ? 0 : left && right ? 1 : left && back ? 2 : right && back ? 3 : left ? 4 : right ? 5 : -1)
        if(back && left && right) return 3;
        if(left && right) return 4;
        if(left && back) return 2;
        if(right && back) return 2;
        if(left || right) return 1;
        return 0;
    }
}
