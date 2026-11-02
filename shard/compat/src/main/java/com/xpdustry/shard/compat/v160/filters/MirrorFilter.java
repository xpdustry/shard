package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;

/**
 * Vanilla's {@code MirrorFilter}: tiles on the right of a line through the pivot copy their mirror image, or their
 * point reflection with {@code rotate} (and on non-square maps at angles off the axes). Not buffered: the image is read
 * live, so a tile may copy one this pass already wrote.
 */
public record MirrorFilter(int angle, boolean rotate, float axisX, float axisY) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        // v1.trnsExact(angle - 90, 1f): Vec2.rotateRadExact of (1, 0)
        float radians = (float)(angle - 90) * Mathf.degreesToRadians;
        float cos = (float)StrictMath.cos(radians), sin = (float)StrictMath.sin(radians);
        float v1x = 1f * cos - 0f * sin, v1y = 1f * sin + 0f * cos;
        float v2x = v1x * -1f, v2y = v1y * -1f;

        // place the mirror line at the configured normalized pivot
        float px = coord(axisX, in.width), py = coord(axisY, in.height);
        v1x += px;
        v1y += py;
        v2x += px;
        v2y += py;

        float x = in.x, y = in.y;

        if(!((v2x - v1x) * (y - v1y) > (v2y - v1y) * (x - v1x))){
            // mirror(): special case, an uneven map mirrored at 45 degrees (or rotational symmetry)
            if((in.width != in.height && angle % 90 != 0) || rotate){
                x = in.width - x - 1;
                y = in.height - y - 1;
            }else{
                float dx = v2x - v1x;
                float dy = v2y - v1y;

                float a = (dx * dx - dy * dy) / (dx * dx + dy * dy);
                float b = 2 * dx * dy / (dx * dx + dy * dy);

                float mx = a * (x - v1x) + b * (y - v1y) + v1x, my = b * (x - v1x) - a * (y - v1y) + v1y;
                x = mx;
                y = my;
            }
            FilterGrid grid = in.grid;
            int tile = in.tile(x, y);
            in.floor = grid.floor(tile);
            if(!grid.synthetic(tile)){
                in.block = grid.block(tile);
            }
            in.overlay = grid.overlay(tile);
            in.data = grid.tiles.data(tile);
            in.floorData = grid.tiles.floorData(tile);
            in.overlayData = grid.tiles.overlayData(tile);
            in.extraData = grid.tiles.extraData(tile);
        }
    }

    private static float coord(float axis, int size){
        if(size <= 1) return 0f;
        return Mathf.clamp(axis, 0f, 1f) * (size - 1f);
    }
}
