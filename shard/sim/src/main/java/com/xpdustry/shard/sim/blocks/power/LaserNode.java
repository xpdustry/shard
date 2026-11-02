package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Vanilla's {@code PowerNode.PowerNodeBuild} (PowerNode.java), the base of the kinds that link by laser: power nodes,
 * the long beam link and the power source. A node doesn't update; it holds its links in its {@link PowerModule} and
 * changes them through two configs:
 * <ul>
 *   <li>{@code Integer}, a packed position: unlinks it if linked (both sides reflow into new graphs, which update at
 *   once), else links it if {@link #linkValid} and this node has room, merging the graphs;</li>
 *   <li>{@code Point2[]}, offsets from this node: unlinks everything, then links each in order. {@link #config()}
 *   gives the links back this way.</li>
 * </ul>
 * {@link #placed()} links a new node to what is in range ({@link #potentialLinks}), then does the base's
 * {@link Building#placed()}.
 *
 * <p>The candidates come from the team's {@link BuildingTree} in range, in its order, then a stable sort puts nodes
 * first and the rest by distance: candidates at the same distance link in the tree's order, as vanilla's.
 *
 * <p>Not ported: vanilla's static {@code maxRange}, the widest autolink range
 * of any node made so far, which only bounds {@link #nodeLinks}' search: the content's widest range bounds it the same.
 */
public abstract class LaserNode extends Building{
    static final KindKey POWER_NODE = KindKey.of("power-node");

    static final Configs<LaserNode> CONFIGS = Configs.of(LaserNode.class)
        .on(Integer.class, LaserNode::configureLink)
        .on(Point2[].class, LaserNode::configureLinks);

    @Derived("the block's laser parameters, read on first use")
    private NodeParams params;

    /** {@code laserRange}, {@code maxNodes}, {@code autolink}, {@code sameBlockConnection} of a power-node block. */
    record NodeParams(float laserRange, int maxNodes, boolean autolink, boolean sameBlockConnection){
        static NodeParams of(BlockDef block){
            if(!block.baked()) return new NodeParams(6f, 3, true, false);
            var fields = block.fields();
            return new NodeParams(fields.f("laserRange"), fields.i("maxNodes"), fields.b("autolink"), fields.b("sameBlockConnection"));
        }
    }

    final NodeParams params(){
        if(params == null) params = NodeParams.of(block());
        return params;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config()}: the links as offsets from this node's tile, in link order. */
    @Override
    public Point2[] config(){
        IntSeq links = power.links;
        var out = new Point2[links.size];
        for(int i = 0; i < out.length; i++){
            int pos = links.get(i);
            out[i] = new Point2(Point2.x(pos) - tileX(), Point2.y(pos) - tileY());
        }
        return out;
    }

    /** The {@code Integer} config (PowerNode.java:60). */
    private void configureLink(Integer value){
        PowerModule power = this.power;
        Building other = PowerGraph.at(world(), value);
        boolean contains = power.links.contains(value), valid = other != null && other.power != null;
        if(contains){
            // unlink
            power.links.removeValue(value);
            if(valid) other.power.links.removeValue(pos());
            PowerGraph newgraph = PowerGraph.create(world());
            // reflow from this point, covering all tiles on this side
            newgraph.reflow(this);
            newgraph.update();
            if(valid && other.power.graph != newgraph){
                // a new graph for the other end
                PowerGraph og = PowerGraph.create(world());
                og.reflow(other);
                og.update();
            }
        }else if(linkValid(this, other) && valid && power.links.size < params().maxNodes()){
            power.links.addUnique(other.pos());
            if(other.team == team) other.power.links.addUnique(pos());
            power.graph.addGraph(other.power.graph);
        }
    }

    /** The {@code Point2[]} config (PowerNode.java:97): clears the old links one by one, then links the new ones. */
    private void configureLinks(Point2[] value){
        IntSeq old = new IntSeq(power.links);
        for(int i = 0; i < old.size; i++) configureLink(old.get(i));
        for(Point2 p : value) configureLink(Point2.pack(p.x() + tileX(), p.y() + tileY()));
    }

    /** {@code placed()} (PowerNode.java:400): a new node without links links to what is in range. */
    @Override
    public void placed(){
        if(power.links.size > 0) return;
        potentialLinks(other -> {
            if(!power.links.contains(other.pos())) configureAny(other.pos());
        });
        super.placed();
    }

    /** {@code linkValid(this, link, true)} with this node's block: in range one way or the other, with room. */
    public final boolean linkValid(Building tile, Building link){
        return linkValid(tile, link, true);
    }

    /** {@code linkValid(tile, link, checkMaxNodes)} (PowerNode.java:362), with this node's block as vanilla's {@code this}. */
    public final boolean linkValid(Building tile, Building link, boolean checkMaxNodes){
        NodeParams params = params();
        if(tile == link || link == null || !link.block().hasPower() || !link.info().connectedPower || tile.team != link.team
            || (params.sameBlockConnection() && tile.block() != link.block())) return false;
        NodeParams other = isNode(link.block()) ? NodeParams.of(link.block()) : null;
        if(overlaps(tile.x(), tile.y(), link, params.laserRange() * Footprint.TILE_SIZE)
            || (other != null && overlaps(link.x(), link.y(), tile, other.laserRange() * Footprint.TILE_SIZE))){
            if(checkMaxNodes && other != null){
                return link.power.links.size < other.maxNodes() || link.power.links.contains(tile.pos());
            }
            return true;
        }
        return false;
    }

    /**
     * {@code getPotentialLinks(tile, team, others)} (PowerNode.java:221): up to {@code maxNodes} buildings in laser range
     * that join power graphs, nodes first and then the closest, one per graph not already connected through this
     * node's edges, never across insulation and never an adjacent building or a full node. The test runs again as
     * each is handed out, after the previous one linked.
     */
    final void potentialLinks(Consumer<Building> others){
        NodeParams params = params();
        if(!params.autolink()) return;
        World world = world();
        int size = block().size();
        float offset = Footprint.worldOffset(size);
        float sx = tileX() * Footprint.TILE_SIZE + offset, sy = tileY() * Footprint.TILE_SIZE + offset;
        float range = params.laserRange() * Footprint.TILE_SIZE;
        var graphs = new ArrayList<PowerGraph>();
        // the conducting graphs, to prevent a double link
        for(int i = 0; i < Footprint.edgeCount(size); i++){
            Building other = world.buildings().at(tileX() + Footprint.edgeX(size, i), tileY() + Footprint.edgeY(size, i));
            if(other != null && other.team == team && other.power != null) addGraph(graphs, other.power.graph);
        }
        addGraph(graphs, power.graph);
        Predicate<Building> valid = other -> other != this && other.info().connectedPower && other.power != null
            && (other.info().outputsPower || other.info().consumesPower || isNode(other.block()))
            && overlaps(sx, sy, other, range) && other.team == team && !containsGraph(graphs, other.power.graph)
            && !insulated(world, tileX(), tileY(), other.tileX(), other.tileY())
            && !(other instanceof LaserNode node && node.power.links.size >= node.params().maxNodes())
            && !adjacent(world, tileX(), tileY(), size, other);
        List<Building> candidates = inRange(world, team, tileX() * Footprint.TILE_SIZE, tileY() * Footprint.TILE_SIZE, range, valid);
        sortCandidates(candidates, tileX() * Footprint.TILE_SIZE + offset, tileY() * Footprint.TILE_SIZE + offset);
        int returned = 0;
        for(Building candidate : candidates){
            if(!valid.test(candidate)) continue;
            if(returned++ < params.maxNodes()){
                addGraph(graphs, candidate.power.graph);
                others.accept(candidate);
            }
        }
    }

    /**
     * {@code PowerNode.getNodeLinks(tile, block, team, others)} (PowerNode.java:278): the autolinking power nodes a
     * {@code block} at {@code tile} would join, with room and in their own range, one per graph not already connected
     * through the block's edges, never across insulation and never adjacent; closest first.
     */
    public static void nodeLinks(World world, int tile, BlockDef block, int team, Consumer<Building> others){
        TileGrid tiles = world.tiles();
        int tx = tiles.x(tile), ty = tiles.y(tile), size = block.size();
        BlockInfo info = world.buildings().info(block);
        var graphs = new ArrayList<PowerGraph>();
        // the conducting graphs, to prevent a double link
        for(int i = 0; i < Footprint.edgeCount(size); i++){
            Building other = world.buildings().at(tx + Footprint.edgeX(size, i), ty + Footprint.edgeY(size, i));
            if(other != null && other.team == team && other.power != null
                && !(info.consumesPower && other.info().consumesPower && !info.outputsPower && !other.info().outputsPower)){
                addGraph(graphs, other.power.graph);
            }
        }
        Building self = world.buildings().at(tile);
        if(self != null && self.power != null) addGraph(graphs, self.power.graph);
        float offset = Footprint.worldOffset(size);
        Predicate<Building> valid = other -> other.tile() != tile && isNode(other.block()) && nodeParams(other).autolink()
            && other.power.links.size < nodeParams(other).maxNodes()
            && overlapsBlock(other.x(), other.y(), tx, ty, block, nodeParams(other).laserRange() * Footprint.TILE_SIZE)
            && other.team == team && !containsGraph(graphs, other.power.graph)
            && !insulated(world, tx, ty, other.tileX(), other.tileY()) && !adjacent(world, tx, ty, size, other);
        List<Building> candidates = inRange(world, team, tx * Footprint.TILE_SIZE, ty * Footprint.TILE_SIZE,
            maxRange(world.content()) * Footprint.TILE_SIZE, valid);
        // a tile's position is its block's centre (Tile.getX)
        BlockDef at = world.content().block(tiles.block(tile));
        sortCandidates(candidates, tx * Footprint.TILE_SIZE + Footprint.worldOffset(at.size()), ty * Footprint.TILE_SIZE + Footprint.worldOffset(at.size()));
        for(Building candidate : candidates){
            if(!valid.test(candidate)) continue;
            addGraph(graphs, candidate.power.graph);
            others.accept(candidate);
        }
    }

    private static NodeParams nodeParams(Building node){
        return node instanceof LaserNode laser ? laser.params() : NodeParams.of(node.block());
    }

    /**
     * {@code team.data().buildingTree.intersect(…)}: the team's buildings whose box overlaps the square of half side
     * {@code range} around (wx, wy) and pass {@code valid}, each once, in the tree's order.
     */
    private static List<Building> inRange(World world, int team, float wx, float wy, float range, Predicate<Building> valid){
        var out = new ArrayList<Building>();
        BuildingTree tree = world.buildings().tree(team);
        if(tree == null) return out;
        tree.intersect(wx - range, wy - range, range * 2, range * 2, build -> {
            if(valid.test(build) && !out.contains(build)) out.add(build);
        });
        return out;
    }

    /** Vanilla's stable sort: power nodes first, then by squared distance to (x, y). */
    private static void sortCandidates(List<Building> candidates, float x, float y){
        candidates.sort((a, b) -> {
            int type = -Boolean.compare(isNode(a.block()), isNode(b.block()));
            if(type != 0) return type;
            return Float.compare(dst2(a, x, y), dst2(b, x, y));
        });
    }

    private static float dst2(Building building, float x, float y){
        float xd = building.x() - x, yd = building.y() - y;
        return xd * xd + yd * yd;
    }

    /** Whether {@code block} is a {@code PowerNode} or a subclass of it (the long beam link, the power source). */
    static boolean isNode(BlockDef block){
        return block.baked() && block.isA(POWER_NODE);
    }

    /** {@code overlaps(srcx, srcy, other.tile, range)}: the circle against the building's hitbox. */
    static boolean overlaps(float srcx, float srcy, Building other, float range){
        float half = other.block().size() * Footprint.TILE_SIZE / 2f;
        return circleOverlaps(srcx, srcy, range, other.x() - half, other.y() - half, half * 2, half * 2);
    }

    /** {@code overlaps(srcx, srcy, tile, block, range)}: the circle against {@code block}'s footprint at a tile. */
    static boolean overlapsBlock(float srcx, float srcy, int tx, int ty, BlockDef block, float range){
        float side = block.size() * Footprint.TILE_SIZE, offset = Footprint.worldOffset(block.size());
        return circleOverlaps(srcx, srcy, range, tx * Footprint.TILE_SIZE + offset - side / 2f, ty * Footprint.TILE_SIZE + offset - side / 2f, side, side);
    }

    /** {@code Intersector.overlaps(Circle, Rect)}. */
    static boolean circleOverlaps(float cx, float cy, float radius, float rx, float ry, float rw, float rh){
        float closestX = cx, closestY = cy;
        if(cx < rx){
            closestX = rx;
        }else if(cx > rx + rw){
            closestX = rx + rw;
        }
        if(cy < ry){
            closestY = ry;
        }else if(cy > ry + rh){
            closestY = ry + rh;
        }
        closestX = closestX - cx;
        closestX *= closestX;
        closestY = closestY - cy;
        closestY *= closestY;
        return closestX + closestY < radius * radius;
    }

    /** {@code PowerNode.insulated(x, y, x2, y2)}: an insulated building on the line between the two tiles. */
    public static boolean insulated(World world, int x, int y, int x2, int y2){
        return Geometry.raycast(x, y, x2, y2, (wx, wy) -> {
            Building tile = world.buildings().at(wx, wy);
            return tile != null && tile.isInsulated();
        });
    }

    /** Whether {@code other} stands on one of the edges of a {@code size} footprint at (x, y). */
    private static boolean adjacent(World world, int x, int y, int size, Building other){
        for(int i = 0; i < Footprint.edgeCount(size); i++){
            int ex = x + Footprint.edgeX(size, i), ey = y + Footprint.edgeY(size, i);
            if(world.tiles().inBounds(ex, ey) && world.buildings().at(ex, ey) == other) return true;
        }
        return false;
    }

    private static void addGraph(List<PowerGraph> graphs, PowerGraph graph){
        if(!containsGraph(graphs, graph)) graphs.add(graph);
    }

    private static boolean containsGraph(List<PowerGraph> graphs, PowerGraph graph){
        for(PowerGraph other : graphs){
            if(other == graph) return true;
        }
        return false;
    }

    /** The widest {@code laserRange} of the content's autolinking power nodes: vanilla's {@code maxRange} once they exist. */
    static float maxRange(ContentEpoch content){
        float max = 0f;
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(!isNode(block)) continue;
            NodeParams params = NodeParams.of(block);
            if(params.autolink()) max = Math.max(max, params.laserRange());
        }
        return max;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((LaserNode)into).params = params;
    }

    /** {@code dropped()}: placed again by a payload unit, it drops its links and joins what it touches. */
    @Override
    public void dropped(){
        power.links.clear();
        PowerGraph.updatePowerGraph(this);
    }
}
