package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ItemBridge.ItemBridgeBuild} (ItemBridge.java), the base of the bridges that link to another bridge of
 * the same block up to {@code range} tiles away in a straight line: the phase conveyor ({@link ItemBridge}), the bridge
 * conveyor ({@link BufferedItemBridge}) and the bridge conduits (the liquid row's). Not a kind: the kinds extend it and
 * override {@link #updateTransport} and {@link #doDump}, as vanilla's subclasses do.
 *
 * <p>Each update a bridge drops the {@link #incoming} bridges that no longer link here ({@link #checkIncoming}), then,
 * with a valid {@link #link}, registers itself in the link's {@code incoming}, warms up toward its efficiency and moves
 * what it holds over; without one it dumps to its neighbours, never back toward a bridge feeding it
 * ({@link #checkDump}). It takes from any side but the one its link is on ({@link #checkAccept}), and from the bridge
 * linking to it. {@link #moved} feeds an animation every 30 ticks ({@code wasMoved}), saved with the link. Disabled by
 * logic, a bridge doesn't update.
 *
 * <p>Configs ({@code ItemBridge.java:66}): a {@code Point2} is relative to this bridge, an {@code Integer} a packed
 * position; neither is checked, so a bridge may hold an invalid link, which {@link #linkValid} then ignores.
 *
 * <p>Not ported, as a dedicated server never reaches them: {@code playerPlaced} and the block's static
 * {@code lastBuild} (the autolink to the last bridge placed), which vanilla runs only on the placing client
 * (ConstructBlock.java:107: {@code !headless && builder == player.unit()}); the client then sends the link as a
 * {@code tileConfig}. {@code pickedUp} (payloads, unported) and the flow statistics ({@code undoFlow}).
 */
public abstract class ItemBridgeBuild extends Building implements Checkpointable{
    private static final KindKey ITEM_BRIDGE = KindKey.of("item-bridge");

    static final Configs<ItemBridgeBuild> CONFIGS = Configs.of(ItemBridgeBuild.class)
        // point2 config is relative
        .on(Point2.class, (bridge, p) -> bridge.link = Point2.pack(p.x() + bridge.tileX(), p.y() + bridge.tileY()))
        // integer is not
        .on(Integer.class, (bridge, i) -> bridge.link = i);

    /** The linked tile, {@link Point2#pack}ed, or -1. */
    public int link = -1;
    /** The packed positions of the bridges that linked here, in vanilla's unordered {@code IntSeq}'s order. */
    public IntSeq incoming = new IntSeq(false, 4);
    public float warmup;
    public float time = -8f, timeSpeed;
    public boolean wasMoved, moved, hadValidLink;
    public float transportCounter;

    @Derived("the block's parameters, read on first use")
    private BridgeParams params;

    /** {@code range}, {@code transportTime}, {@code linkSameType}, {@code noAcceptDisabled}, {@code timerCheckMoved}. */
    record BridgeParams(int range, float transportTime, boolean linkSameType, boolean noAcceptDisabled, int timerCheckMoved){
        static BridgeParams of(BlockDef block){
            if(!block.baked()) return new BridgeParams(4, 2f, true, false, 1);
            var f = block.fields();
            return new BridgeParams(f.i("range"), f.f("transportTime"), f.b("linkSameType"), f.b("noAcceptDisabled"), f.i("timerCheckMoved"));
        }
    }

    final BridgeParams params(){
        if(params == null) params = BridgeParams.of(block());
        return params;
    }

    /** The block's {@code range}, in tiles. */
    public final int bridgeRange(){
        return params().range();
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config()}: the link relative to this bridge; an unlinked one gives (-1, -1) minus its own tile. */
    @Override
    public Point2 config(){
        return Point2.unpack(link).add(-tileX(), -tileY());
    }

    // ---------------------------------------------------------------- links

    /** {@code world.tile(pos)}: the tile at a packed position, {@code y * width + x}, or -1 outside the map. */
    final int tileAt(int packed){
        TileGrid tiles = world().tiles();
        int x = Point2.x(packed), y = Point2.y(packed);
        return tiles.inBounds(x, y) ? tiles.pos(x, y) : -1;
    }

    /** {@code linkValid(tile, other, true)}. */
    public final boolean linkValid(int tile, int other){
        return linkValid(tile, other, true);
    }

    /**
     * {@code linkValid(tile, other, checkDouble)} (ItemBridge.java:166), with this bridge's block as vanilla's
     * {@code this}: tiles as {@code y * width + x}, -1 for null. In line and range, the same bridge block on both
     * (or any bridge across blocks without {@code linkSameType}), the same team, and, with {@code checkDouble}, not
     * already linked back.
     */
    public final boolean linkValid(int tile, int other, boolean checkDouble){
        if(other == -1 || tile == -1) return false;
        World world = world();
        TileGrid tiles = world.tiles();
        BridgeParams params = params();
        BlockDef tileBlock = world.content().block(tiles.block(tile)), otherBlock = world.content().block(tiles.block(other));
        boolean tileBridge = isBridge(tileBlock), otherBridge = isBridge(otherBlock);
        int range = params.linkSameType() ? params.range()
            : Math.max(tileBridge ? BridgeParams.of(tileBlock).range() : params.range(), otherBridge ? BridgeParams.of(otherBlock).range() : params.range());
        if(!positionsValid(tiles.x(tile), tiles.y(tile), tiles.x(other), tiles.y(other), range)) return false;
        if(!params.linkSameType()){
            // false if at least 1 module isn't shared
            if(!(tileBlock.hasItems() && otherBlock.hasItems()) && !(tileBlock.hasLiquids() && otherBlock.hasLiquids())) return false;
        }
        BlockDef self = block();
        Building otherBuild = world.buildings().at(other);
        return ((params.linkSameType() ? otherBlock == tileBlock && tileBlock == self : (otherBridge && tileBridge))
            || (!tileBridge && otherBlock == self))
            && (team(other) == team(tile) || tileBlock != self)
            && (!checkDouble || ((ItemBridgeBuild)otherBuild).link != Point2.pack(tiles.x(tile), tiles.y(tile)));
    }

    /** {@code Tile.team()}: its building's, or derelict. */
    private int team(int tile){
        Building build = world().buildings().at(tile);
        return build == null ? com.xpdustry.shard.sim.team.Team.DERELICT : build.team;
    }

    private static boolean isBridge(BlockDef block){
        return block.baked() && block.isA(ITEM_BRIDGE);
    }

    /** {@code positionsValid(x1, y1, x2, y2, range)}: in one row or column, at most {@code range} apart. */
    public static boolean positionsValid(int x1, int y1, int x2, int y2, int range){
        if(x1 == x2){
            return Math.abs(y1 - y2) <= range;
        }else if(y1 == y2){
            return Math.abs(x1 - x2) <= range;
        }
        return false;
    }

    /** {@code checkIncoming()}: drops the incoming bridges that no longer link here. */
    public final void checkIncoming(){
        int pos = pos();
        int idx = 0;
        while(idx < incoming.size){
            int i = incoming.items[idx];
            int other = tileAt(i);
            if(!linkValid(tile(), other, false) || ((ItemBridgeBuild)world().buildings().at(other)).link != pos){
                incoming.removeIndex(idx);
                idx--;
            }
            idx++;
        }
    }

    // ---------------------------------------------------------------- update

    @Override
    public void updateTile(){
        if(timer(params().timerCheckMoved(), 30f)){
            wasMoved = moved;
            moved = false;
        }
        // smooth out animation, so it doesn't stop/start immediately
        timeSpeed = Mathf.approachDelta(timeSpeed, wasMoved ? 1f : 0f, 1f / 60f);
        time += timeSpeed * delta();
        checkIncoming();
        int other = tileAt(link);
        hadValidLink = linkValid(tile(), other);
        if(!hadValidLink){
            doDump();
            warmup = 0f;
        }else{
            var target = (ItemBridgeBuild)world().buildings().at(other);
            IntSeq inc = target.incoming;
            int pos = pos();
            if(!inc.contains(pos)) inc.add(pos);
            warmup = Mathf.approachDelta(warmup, efficiency, 1f / 30f);
            updateTransport(target);
        }
    }

    /** {@code doDump()}: what an unlinked bridge does with what it holds; by default dumps several times a frame. */
    protected void doDump(){
        // allow dumping multiple times per frame
        dumpAccumulate(-1);
    }

    /** {@code updateTransport(other)}: moves what this bridge holds to its link, one item per {@code transportTime}. */
    protected void updateTransport(Building other){
        float transportTime = params().transportTime();
        transportCounter += edelta();
        while(transportCounter >= transportTime){
            int item = items.take();
            if(item != -1 && other.acceptItem(this, item)){
                other.handleItem(this, item);
                moved = true;
            }else if(item != -1){
                items.add(item, 1);
            }
            transportCounter -= transportTime;
        }
    }

    @Override
    public boolean shouldConsume(){
        return hadValidLink && enabled;
    }

    // ---------------------------------------------------------------- items and liquids

    @Override
    public boolean acceptItem(Building source, int item){
        return block().hasItems() && team == source.team && items.total() < info().itemCapacity && checkAccept(source, tileAt(link))
            && (!params().noAcceptDisabled() || enabled);
    }

    @Override
    public boolean canDumpLiquid(Building to, int liquid){
        return checkDump(to);
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return block().hasLiquids() && team == source.team && (!params().noAcceptDisabled() || enabled)
            && (liquids.current() == liquid || liquids.get(liquids.current()) < 0.2f)
            && checkAccept(source, tileAt(link));
    }

    /**
     * {@code checkAccept(source, link)} (ItemBridge.java:439): from the bridge linking here, or, with a valid link,
     * from any side but the link's. Vanilla's check against incoming bridges' sides is commented out "for
     * compatibility" (issue 9257), so a bridge takes items from a side a bridge feeds it from.
     */
    public boolean checkAccept(Building source, int link){
        if(linked(source)) return true;
        if(linkValid(tile(), link)){
            TileGrid tiles = world().tiles();
            int rel = relativeTo(tiles.x(link), tiles.y(link));
            int facing = Blending.facingEdge(source, this);
            int rel2 = relativeTo(tiles.x(facing), tiles.y(facing));
            return rel != rel2;
        }
        return false;
    }

    /** {@code linked(source)}: {@code source} is a bridge whose valid link is this one. */
    public boolean linked(Building source){
        return source instanceof ItemBridgeBuild bridge && linkValid(source.tile(), tile()) && bridge.link == pos();
    }

    @Override
    public boolean canDump(Building to, int item){
        return checkDump(to);
    }

    /**
     * {@code checkDump(to)} (ItemBridge.java:472): an unlinked bridge dumps anywhere but toward a bridge feeding it; a
     * linked one anywhere but toward its link.
     */
    public boolean checkDump(Building to){
        TileGrid tiles = world().tiles();
        int other = tileAt(link);
        if(!linkValid(tile(), other)){
            int edge = Blending.facingEdge(to, this);
            int i = relativeTo(tiles.x(edge), tiles.y(edge));
            for(int j = 0; j < incoming.size; j++){
                int v = incoming.items[j];
                if(relativeTo(Point2.x(v), Point2.y(v)) == i) return false;
            }
            return true;
        }
        int rel = relativeTo(tiles.x(other), tiles.y(other));
        int rel2 = relativeTo(to.tileX(), to.tileY());
        return rel != rel2;
    }

    // ---------------------------------------------------------------- state

    @Override
    public void write(CheckpointOut out){
        out.i("link", link).intSeq("incoming", incoming).f("warmup", warmup).f("time", time).f("timeSpeed", timeSpeed)
            .bool("wasMoved", wasMoved).bool("moved", moved).bool("hadValidLink", hadValidLink).f("transportCounter", transportCounter);
    }

    @Override
    public void read(CheckpointIn in){
        link = in.i("link");
        incoming = new IntSeq(false, 4);
        in.intSeq("incoming", incoming);
        warmup = in.f("warmup");
        time = in.f("time");
        timeSpeed = in.f("timeSpeed");
        wasMoved = in.bool("wasMoved");
        moved = in.bool("moved");
        hadValidLink = in.bool("hadValidLink");
        transportCounter = in.f("transportCounter");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ItemBridgeBuild)into;
        out.link = link;
        out.incoming = new IntSeq(false, Math.max(4, incoming.size));
        out.incoming.addAll(incoming);
        out.warmup = warmup;
        out.time = time;
        out.timeSpeed = timeSpeed;
        out.wasMoved = wasMoved;
        out.moved = moved;
        out.hadValidLink = hadValidLink;
        out.transportCounter = transportCounter;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(link).i(incoming.size);
        for(int i = 0; i < incoming.size; i++) hasher.i(incoming.items[i]);
        hasher.f(warmup).f(time).f(timeSpeed).bool(wasMoved).bool(moved).bool(hadValidLink).f(transportCounter);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and its link goes. */
    @Override
    public void pickedUp(){
        link = -1;
    }
}
