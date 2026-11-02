package com.xpdustry.shard.server.session;

/**
 * What one session may see, frozen at a sync boundary: its team and the view rectangle its client last reported, in
 * world units. The sync layer (§8, WP25) builds one per session from its {@link PlayerEntity} and filters by it; fog
 * adds the team's visibility here later.
 *
 * @param x the view's centre
 * @param y the view's centre
 */
public record Viewer(long session, int team, float x, float y, float width, float height, boolean admin){
    /** Whether the point is in view, with {@code margin} world units around it. */
    public boolean sees(float px, float py, float margin){
        return Math.abs(px - x) <= width / 2f + margin && Math.abs(py - y) <= height / 2f + margin;
    }
}
