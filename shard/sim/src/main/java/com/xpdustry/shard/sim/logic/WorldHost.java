package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The world side of mlog ({@link LogicHost}) on a Shard {@link World}: vanilla's {@code SenseI}, {@code ControlI},
 * {@code ReadI}, {@code WriteI}, {@code PrintFlushI}, {@code DrawFlushI} (LExecutor.java:558-724, 1032, 1167) and the
 * world processors' tile, flag, property and rule statements.
 *
 * <p>Values cross between the VM's terms and {@code :sim}'s here: the VM holds the logic table's content definitions
 * and {@link LogicTeam}s, the sim holds ids ({@link ContentRef}, team ids), so a sensor hands back
 * {@link Globals#content()}'s definitions and {@link Globals#team(int)}, and a {@code control config} hands a building
 * a {@code ContentRef}. Buildings and units cross as themselves.
 *
 * <p>The unit statements and {@code query} run in {@link UnitStatements}; {@code fetch}, {@code spawn}, {@code bullet},
 * {@code status}, {@code explosion}, {@code spawnwave} and the weather in {@link WorldStatements}.
 *
 * <p>{@code makemarker} and {@code setmarker} change the world's markers ({@link ObjectiveState#markers}) and send
 * nothing, as in vanilla; {@code localeprint} reads the map's locales ({@link ObjectiveState#locales}).
 *
 * <p>Every statement runs, the payload ones ({@code ucontrol payDrop}, {@code payTake}, {@code payEnter},
 * {@code setprop @payloadType}) included. The client-only statements ({@code cutscene}, {@code effect}, sounds,
 * {@code clientdata}, {@code message} other than {@code mission}) do nothing on a server, as in vanilla.
 */
public final class WorldHost implements LogicHost{
    private static final KindKey CONSTRUCT = KindKey.of("construct-block");
    /** {@code Vars.tilePayload}: one tile's payload area. */
    private static final float TILE_PAYLOAD = Footprint.TILE_SIZE * Footprint.TILE_SIZE;

    final World world;
    /** The rules statements read and change instead of the world's: a loading map's own, as vanilla's state.rules. */
    Rules rules;
    private final UnitStatements units;
    private final WorldStatements statements;

    WorldHost(World world){
        this.world = world;
        this.units = new UnitStatements(this);
        this.statements = new WorldStatements(this);
    }

    Rules rules(){
        return rules != null ? rules : world.buildings().rules();
    }

    private Globals table(Processor p){
        return p.world().table;
    }

    //region values

    /** A sim value as the VM holds it: content as the table's definitions, the rest as itself. */
    Object vm(Processor p, Object value){
        return switch(value){
            case ContentRef ref -> table(p).content().get(ref.type(), ref.id());
            case ContentDef def -> table(p).content().get(def.type(), def.id());
            case null, default -> value;
        };
    }

    /** A VM value as the sim holds it: content as {@link ContentRef}s. */
    static Object sim(Object value){
        return value instanceof ContentDef def ? new ContentRef(def.type(), def.id()) : value;
    }

    /** {@code LVar.team()}: a team object, or a number that is a team id. */
    static int team(Processor p, int slot){
        if(p.isObj(slot)) return p.obj(slot) instanceof LogicTeam team ? team.id() : -1;
        int id = (int)p.num(slot);
        return id < 0 || id >= 256 ? -1 : id;
    }

    @Override
    public String toString(Object value){
        if(value instanceof Building building) return building.block().name();
        if(value instanceof Unit unit) return unit.type().name();
        return null;
    }

    //endregion
    //region sensors

    @Override
    public boolean sensor(Processor p, Object target, Object property, int to){
        if(property instanceof LAccess access && access.privileged && !p.privileged) return false;
        return switch(target){
            case Building building -> senseBuilding(p, building, property, to);
            case Unit unit -> senseUnit(p, unit, property, to);
            case ContentDef def -> senseContent(p, def, property, to);
            case LogicTeam team -> senseTeam(p, team, property, to);
            default -> false;
        };
    }

    private boolean senseBuilding(Processor p, Building building, Object property, int to){
        if(property instanceof ContentDef content){
            p.setnum(to, building.sense(content.type(), content.id()));
            return true;
        }
        if(property instanceof LAccess access){
            Object out = building.senseObject(access);
            if(out == Building.NO_SENSED) p.setnum(to, building.sense(access));
            else p.setobj(to, vm(p, out));
            return true;
        }
        return false;
    }

    /**
     * {@code UnitComp.sense}, {@code senseObject} and {@code sense(Content)} (UnitComp.java:265-369). Players' cameras
     * and pings aren't in the sim: a player's camera reads 0 and its ping as none.
     */
    private boolean senseUnit(Processor p, Unit unit, Object property, int to){
        UnitDef type = unit.type();
        UnitParams params = unit.params();
        if(property instanceof ContentDef content){
            p.setnum(to, senseContent(unit, content));
            return true;
        }
        if(!(property instanceof LAccess access)) return false;
        Player player = unit.player();
        switch(access){
            case type -> p.setobj(to, vm(p, type));
            case name -> p.setobj(to, player == null ? null : player.name);
            case firstItem -> p.setobj(to, unit.stackAmount == 0 ? null : table(p).content().get(ContentType.ITEM, unit.stackItem));
            case controller -> p.setobj(to, !unit.isValid() ? null : unit.controller instanceof LogicAi ai ? ai.controller : unit);
            case payloadType -> {
                Payload top = unit.payloads == null || unit.payloads.isEmpty() ? null : unit.payloads.get(unit.payloads.size() - 1);
                p.setobj(to, switch(top){
                    case Payload.UnitPayload carried -> vm(p, carried.unit().type());
                    case Payload.BuildPayload carried -> vm(p, carried.building().block());
                    case null -> null;
                });
            }
            case building, breaking -> {
                BuildPlan plan = Builder.isBuilding(unit) ? unit.plans.first() : null;
                boolean breaking = access == LAccess.breaking;
                TileGrid tiles = world.tiles();
                p.setobj(to, plan == null || plan.breaking != breaking || !tiles.inBounds(plan.x, plan.y) ? null
                    : world.buildings().at(tiles.pos(plan.x, plan.y)));
            }
            case selectedBlock -> p.setobj(to, player == null || player.selectedBlock < 0 ? null : table(p).content().get(ContentType.BLOCK, player.selectedBlock));
            case pingText -> p.setobj(to, null);
            default -> p.setnum(to, switch(access){
                case totalItems -> unit.stackAmount;
                case itemCapacity -> params.itemCapacity();
                case rotation -> unit.rotation;
                case health -> unit.health;
                case shield -> unit.shield;
                case maxHealth -> unit.maxHealth;
                case flying -> unit.isFlying() ? 1 : 0;
                case x -> unit.x / Footprint.TILE_SIZE;
                case y -> unit.y / Footprint.TILE_SIZE;
                case velocityX -> unit.vel.x * 60f / Footprint.TILE_SIZE;
                case velocityY -> unit.vel.y * 60f / Footprint.TILE_SIZE;
                case dead -> unit.dead || !unit.isAdded() ? 1 : 0;
                case team -> unit.team;
                case shooting -> unit.isShooting ? 1 : 0;
                case boosting -> params.canBoost() && unit.isFlying() ? 1 : 0;
                case range -> world.unitTable().ai(type).range / Footprint.TILE_SIZE;
                case shootX -> unit.aimX / Footprint.TILE_SIZE;
                case shootY -> unit.aimY / Footprint.TILE_SIZE;
                // a player's camera comes from its client's snapshots, which the sim doesn't keep
                case cameraX, cameraY, cameraWidth, cameraHeight -> 0;
                case mining -> Miner.mining(unit) ? 1 : 0;
                case mineX -> Miner.mining(unit) ? Point2.x(unit.mineTile) : -1;
                case mineY -> Miner.mining(unit) ? Point2.y(unit.mineTile) : -1;
                case buildX -> Builder.isBuilding(unit) ? unit.plans.first().x : -1;
                case buildY -> Builder.isBuilding(unit) ? unit.plans.first().y : -1;
                case armor -> unit.statuses.armorOverride >= 0f ? unit.statuses.armorOverride : unit.armor;
                case flag -> unit.flag;
                case speed -> params.speed() * 60f / Footprint.TILE_SIZE * unit.statuses.speedMultiplier;
                case controlled -> !unit.isValid() ? 0
                    : unit.controller instanceof LogicAi ? Globals.ctrlProcessor
                    : unit.isPlayer() ? Globals.ctrlPlayer
                    : unit.controller instanceof CommandAi ai && ai.targetPos != null ? Globals.ctrlCommand
                    : 0;
                case payloadCount -> unit.payloads == null ? 0 : unit.payloads.size();
                case totalPayload -> unit.payloads == null ? 0 : payloadUsed(unit) / TILE_PAYLOAD;
                case payloadCapacity -> type.payloadCapacity() / TILE_PAYLOAD;
                case size -> unit.hitSize / Footprint.TILE_SIZE;
                case color -> TeamColors.color(unit.team);
                case selectedRotation -> player == null ? 0 : player.selectedRotation;
                default -> Double.NaN;
            });
        }
        return true;
    }

    /** {@code UnitComp.sense(content)}: the stack, carried units or blocks of a type, a status's seconds left. */
    private double senseContent(Unit unit, ContentDef content){
        if(content.type() == ContentType.ITEM && content.id() == unit.stackItem) return unit.stackAmount;
        if(content.type() == ContentType.UNIT || content.type() == ContentType.BLOCK){
            if(unit.payloads == null) return 0;
            int count = 0;
            for(int i = 0; i < unit.payloads.size(); i++){
                switch(unit.payloads.get(i)){
                    case Payload.UnitPayload carried when content.type() == ContentType.UNIT && carried.unit().type().id() == content.id() -> count++;
                    case Payload.BuildPayload carried when content.type() == ContentType.BLOCK && carried.building().block().id() == content.id() -> count++;
                    default -> {}
                }
            }
            return count;
        }
        if(content.type() == ContentType.STATUS){
            return unit.statuses.hasEffect(content.id()) ? unit.statuses.getDuration(content.id()) / 60 : 0;
        }
        return Double.NaN;
    }

    /** {@code PayloadComp.payloadUsed()}: the sum of each payload's size squared. */
    private static float payloadUsed(Unit unit){
        float used = 0f;
        for(int i = 0; i < unit.payloads.size(); i++){
            float size = switch(unit.payloads.get(i)){
                case Payload.UnitPayload carried -> carried.unit().hitSize;
                case Payload.BuildPayload carried -> carried.building().block().size() * Footprint.TILE_SIZE;
            };
            used += size * size;
        }
        return used;
    }

    private static boolean unitValid(Unit unit){
        return !unit.dead && unit.isAdded();
    }

    /** {@code Senseable} content: blocks, items, liquids and unit types (Block.java:1674, Item.java:148, UnitType.java:1430). */
    private boolean senseContent(Processor p, ContentDef def, Object property, int to){
        ContentType type = def.type();
        if(type != ContentType.BLOCK && type != ContentType.ITEM && type != ContentType.LIQUID && type != ContentType.UNIT) return false;
        if(property instanceof ContentDef content){
            double out = 0;
            if(type == ContentType.BLOCK && content.type() == ContentType.ITEM){
                Rules rules = rules();
                Integer amount = ((BlockDef)def).requirements().get(content.name());
                out = rules.infiniteResources || amount == null ? 0 : Math.round(amount * rules.buildCostMultiplier);
            }
            p.setnum(to, out);
            return true;
        }
        if(!(property instanceof LAccess access)) return false;
        if(access == LAccess.name){
            p.setobj(to, def.name());
            return true;
        }
        double id = table(p).logicId(def);
        p.setnum(to, switch(type){
            case BLOCK -> {
                var block = (BlockDef)def;
                var info = world.buildings().info(block);
                var power = info.consumers.power();
                yield switch(access){
                    case color -> color(block, "mapColor");
                    case health, maxHealth -> block.health();
                    case armor -> info.armor;
                    case solid -> block.solid() ? 1 : 0;
                    case size -> block.size();
                    case itemCapacity -> info.itemCapacity;
                    case liquidCapacity -> info.liquidCapacity;
                    case powerCapacity -> power != null && power.buffered ? power.capacity : 0f;
                    case id -> id;
                    default -> Double.NaN;
                };
            }
            case UNIT -> {
                var unit = (UnitDef)def;
                yield switch(access){
                    case health, maxHealth -> unit.health();
                    case armor -> unit.armor();
                    case range -> unit.fields().f("maxRange") / Footprint.TILE_SIZE;
                    case size -> unit.hitSize() / Footprint.TILE_SIZE;
                    case flying -> unit.flying() ? 1f : 0f;
                    case itemCapacity -> unit.itemCapacity();
                    case speed -> unit.speed() * 60f / Footprint.TILE_SIZE;
                    case payloadCapacity -> world.unitTable().params(unit).payloads() ? unit.payloadCapacity() / TILE_PAYLOAD : 0f;
                    case id -> id;
                    default -> Double.NaN;
                };
            }
            default -> switch(access){
                case color -> color(def, "color");
                case id -> id;
                default -> Double.NaN;
            };
        });
        return true;
    }

    private static double color(ContentDef def, String field){
        if(!(def instanceof BlockDef || def instanceof ItemDef || def instanceof LiquidDef)) return Double.NaN;
        var fields = switch(def){
            case BlockDef block -> block.baked() ? block.fields() : null;
            case ItemDef item -> item.fields();
            case LiquidDef liquid -> liquid.fields();
            default -> null;
        };
        if(fields == null || !fields.has(field)) return Double.NaN;
        String hex = fields.s(field);
        if(hex.startsWith("#")) hex = hex.substring(1);
        if(hex.length() == 6) hex += "ff";
        int rgba = (int)Long.parseLong(hex, 16);
        // Color.toDoubleBits() of the content's colour, alpha included
        return MlogMath.packColor(rgba);
    }

    private boolean senseTeam(Processor p, LogicTeam team, Object property, int to){
        if(!(property instanceof LAccess access)) return false;
        if(access == LAccess.name){
            p.setobj(to, team.name());
        }else{
            p.setnum(to, switch(access){
                case id -> team.id();
                case color -> TeamColors.color(team.id());
                default -> Double.NaN;
            });
        }
        return true;
    }

    //endregion
    //region buildings

    @Override
    public void control(Processor p, LAccess type, Object target, int p1, int p2, int p3, int p4){
        if(!(target instanceof Building building)) return;
        Building self = p.building() instanceof Building b ? b : null;
        if(!(p.privileged || self instanceof ProcessorBuilding processor && processor.validLink(building))) return;
        if(type == LAccess.enabled){
            if(p.bool(p1)) building.noSleep();
            else building.lastDisabler = self;
        }
        if(type.isObj && p.isObj(p1)){
            building.control(type, sim(p.obj(p1)), p.num(p2), p.num(p3), p.num(p4));
        }else{
            building.control(type, p.num(p1), p.num(p2), p.num(p3), p.num(p4));
        }
    }

    @Override
    public boolean read(Processor p, Object target, int position, int output){
        if(!(target instanceof LogicReadable readable)) return false;
        if(!readable.readable(p)){
            p.setobj(output, null);
            return true;
        }
        readable.read(p, position, output);
        return true;
    }

    @Override
    public void write(Processor p, Object target, int position, int value){
        if(target instanceof LogicWritable writable && writable.writable(p)) writable.write(p, position, value);
    }

    @Override
    public void printflush(Processor p, Object target){
        if(target instanceof LogicPrintable printable && printable.printable(p)) printable.print(p.text());
    }

    @Override
    public void drawflush(Processor p, Object target){
        if(target instanceof LogicDrawable drawable && drawable.drawable(p)) drawable.drawFlush();
    }

    //endregion
    //region links

    @Override
    public Object building(int x, int y){
        return world.buildings().at(x, y);
    }

    @Override
    public boolean validLink(Processor p, Object other){
        return p.building() instanceof ProcessorBuilding processor && processor.validLink((Building)other);
    }

    @Override
    public Object block(Object building){
        return ((Building)building).block();
    }

    @Override
    public String blockName(Object building){
        return ((Building)building).block().name();
    }

    @Override
    public int buildingId(Object building){
        return ((Building)building).id();
    }

    @Override
    public int buildingTile(Object building){
        return ((Building)building).pos();
    }

    /** Whether {@code building} is under construction, which no processor may link to. */
    public static boolean constructing(Building building){
        return building.block().baked() && building.block().isA(CONSTRUCT);
    }

    //endregion
    //region host statements

    @Override
    public void call(Processor p, HostOp op){
        switch(op){
            case GetBlock g -> getBlock(p, g);
            case SetBlock s -> setBlock(p, s);
            case GetFlag g -> {
                if(p.obj(g.flag()) instanceof String flag) p.setbool(g.result(), rules().objectiveFlags.contains(flag));
                else p.setobj(g.result(), null);
            }
            case SetFlag s -> {
                // don't invoke unless the flag state actually changes
                Rules rules = rules();
                if(p.obj(s.flag()) instanceof String flag && rules.objectiveFlags.contains(flag) != p.bool(s.value())){
                    boolean add = p.bool(s.value());
                    if(add) rules.objectiveFlags.add(flag);
                    else rules.objectiveFlags.remove(flag);
                    publish(new LogicEvent.SetFlag(world.ticks(), flag, add));
                }
            }
            case SetProp s -> setProp(p, s);
            case SetRule s -> setRule(p, s);
            case Message m -> {
                // FlushMessageI: a server shows nothing; the mission text is the rules'. Not ported: UI.formatIcons,
                // which turns :name: into icon characters
                rules().mission = p.text().toString();
            }
            case Cutscene c -> {}
            case Effect e -> {}
            case PlaySound s -> {}
            case PlayMusic m -> {}
            case ClientData c -> {}
            case Radar r -> units.radar(p, r);
            case UnitBind u -> units.bind(p, u);
            case UnitControl u -> units.control(p, u);
            case UnitLocate u -> units.locate(p, u);
            case Query q -> units.query(p, q);
            case Fetch f -> statements.fetch(p, f);
            case SpawnUnit s -> statements.spawn(p, s);
            case SpawnBullet s -> statements.bullet(p, s);
            case ApplyStatus s -> statements.status(p, s);
            case Explosion e -> statements.explosion(p, e);
            case SpawnWave s -> statements.spawnWave(p, s);
            case WeatherSense w -> statements.weatherSense(p, w);
            case WeatherSet w -> statements.weatherSet(p, w);
            case SetMarker m -> setMarker(p, m);
            case MakeMarker m -> makeMarker(p, m);
            case Sync s -> {}
            case LocalePrint l -> {}
        }
    }

    /** MakeMarkerI (LExecutor.java:2322): a new marker of the type at (x, y) in tiles, unless the id is taken. */
    private void makeMarker(Processor p, MakeMarker m){
        ObjectiveState state = ObjectiveState.of(world);
        if(state == null) return;
        MapMarkers markers = state.markers;
        ObjectiveMarker marker = ObjectiveMarker.byName(m.type());
        if(marker == null || markers.size() >= MapMarkers.MAX_MARKERS) return;
        int id = p.numi(m.id());
        if(p.bool(m.replace()) || !markers.has(id)){
            marker.control(markers, LMarkerControl.pos, p.num(m.x()), p.num(m.y()), 0);
            markers.add(id, marker);
        }
    }

    /** SetMarkerI (LExecutor.java:2282): {@code flushText} and a text {@code texture} take the text buffer. */
    private void setMarker(Processor p, SetMarker m){
        ObjectiveState state = ObjectiveState.of(world);
        if(state == null) return;
        MapMarkers markers = state.markers;
        if(m.type() == LMarkerControl.remove){
            markers.remove(p.numi(m.id()));
            return;
        }
        ObjectiveMarker marker = markers.get(p.numi(m.id()));
        if(marker == null) return;
        if(m.type() == LMarkerControl.flushText){
            marker.setText(p.text().toString(), p.bool(m.p1()));
            p.text().setLength(0);
        }else if(m.type() == LMarkerControl.texture){
            if(p.bool(m.p1())){
                marker.setTexture(ObjectiveMarker.Texture.string(p.text().toString()));
                p.text().setLength(0);
            }else{
                marker.setTexture(texture(p.obj(m.p2())));
            }
        }else{
            marker.control(markers, m.type(), p.numOrNan(m.p1()), p.numOrNan(m.p2()), p.numOrNan(m.p3()));
        }
    }

    /** {@code TextureHolder.value} of a VM object: a name, content (bullets aren't unlockable), a building, or none. */
    private static ObjectiveMarker.Texture texture(Object value){
        return switch(value){
            case String name -> ObjectiveMarker.Texture.string(name);
            case ContentDef def when def.type() != ContentType.BULLET -> ObjectiveMarker.Texture.content(def.name());
            case Building building -> ObjectiveMarker.Texture.building(building.pos());
            case null, default -> ObjectiveMarker.Texture.NONE;
        };
    }

    @Override
    public String localeString(String name){
        ObjectiveState state = ObjectiveState.of(world);
        return state == null ? null : state.locales.getProperty(name);
    }

    /** GetBlockI (LExecutor.java:1551). */
    private void getBlock(Processor p, GetBlock g){
        TileGrid tiles = world.tiles();
        int x = Mathf.round(p.numf(g.x())), y = Mathf.round(p.numf(g.y()));
        if(!tiles.inBounds(x, y)){
            p.setobj(g.result(), null);
            return;
        }
        int pos = tiles.pos(x, y);
        ContentEpoch content = table(p).content();
        p.setobj(g.result(), switch(g.layer()){
            case floor -> content.block(tiles.floor(pos));
            case ore -> content.block(tiles.overlay(pos));
            case block -> content.block(tiles.block(pos));
            case building -> world.buildings().at(pos);
        });
    }

    /** SetBlockI (LExecutor.java:1582): {@code setOverlayNet}, {@code setFloorNet} (which clears the overlay), {@code setNet}. */
    private void setBlock(Processor p, SetBlock s){
        TileGrid tiles = world.tiles();
        int x = p.numi(s.x()), y = p.numi(s.y());
        if(!tiles.inBounds(x, y) || !(p.obj(s.block()) instanceof BlockDef block) || block.type() != ContentType.BLOCK) return;
        int pos = tiles.pos(x, y), id = block.id();
        BlockTable table = tiles.table();
        Buildings buildings = world.buildings();
        BlockDef air = world.content().block(0);
        switch(s.layer()){
            case ore -> {
                if((table.isOverlay(id) || id == 0) && tiles.overlay(pos) != id){
                    buildings.setOverlay(x, y, world.content().block(id), Cause.SERVER);
                    publish(new LogicEvent.SetOverlay(world.ticks(), pos, id));
                }
            }
            case floor -> {
                if(table.isFloor(id) && tiles.floor(pos) != id && !table.isOverlay(id) && !table.isAir(id)){
                    buildings.setFloor(x, y, world.content().block(id), Cause.SERVER);
                    buildings.setOverlay(x, y, air, Cause.SERVER);
                    publish(new LogicEvent.SetFloor(world.ticks(), pos, id, 0));
                }
            }
            case block -> {
                if(!table.isFloor(id) || id == 0){
                    int team = team(p, s.team());
                    if(team < 0) team = VanillaIds.DERELICT;
                    if(tiles.block(pos) != id || tiles.team(pos) != team){
                        int rotation = Math.clamp(p.numi(s.rotation()), 0, 3);
                        buildings.setBlock(x, y, world.content().block(id), team, rotation, Cause.SERVER);
                        publish(new LogicEvent.SetTile(world.ticks(), pos, id, team, rotation));
                    }
                }
            }
            case building -> {}
        }
    }

    /** SetPropI (LExecutor.java:2216) on a building or a unit. */
    private void setProp(Processor p, SetProp s){
        if(p.obj(s.of()) instanceof Unit unit){
            setProp(p, s, unit);
            return;
        }
        if(!(p.obj(s.of()) instanceof Building building)) return;
        Object key = p.obj(s.type());
        if(key instanceof LAccess property){
            if(p.isObj(s.value())){
                // setProp(LAccess, Object): only the team, as a team object
                if(property == LAccess.team && p.obj(s.value()) instanceof LogicTeam team && building.team != team.id()){
                    building.setProp(LAccess.team, team.id());
                }
            }else{
                building.setProp(property, p.num(s.value()));
            }
        }else if(key instanceof ContentDef content && content.type() != ContentType.BULLET && content.type() != ContentType.WEATHER){
            building.setProp(content.type(), content.id(), p.num(s.value()));
        }
    }

    /** {@code UnitComp.setProp} (UnitComp.java:372-427). */
    private void setProp(Processor p, SetProp s, Unit unit){
        Object key = p.obj(s.type());
        if(key instanceof LAccess property){
            if(p.isObj(s.value())){
                Object value = p.obj(s.value());
                switch(property){
                    case team -> {
                        if(value instanceof LogicTeam team) setTeam(unit, team.id());
                    }
                    case payloadType -> setPayloadType(unit, value);
                    default -> {}
                }
                return;
            }
            double value = p.num(s.value());
            switch(property){
                case health -> {
                    unit.health = (float)Math.clamp(value, 0, unit.maxHealth);
                    if(unit.health <= 0f && !unit.dead) unit.kill();
                }
                case shield -> unit.shield = Math.max((float)value, 0f);
                case x -> {
                    unit.x = (float)value * Footprint.TILE_SIZE;
                    // UnitComp.java:366: a player's unit stays at the new position until its client reports again
                    RemoteSync.snap(unit);
                }
                case y -> {
                    unit.y = (float)value * Footprint.TILE_SIZE;
                    // UnitComp.java:370
                    RemoteSync.snap(unit);
                }
                case velocityX -> unit.vel.x = (float)(value * Footprint.TILE_SIZE / 60d);
                case velocityY -> unit.vel.y = (float)(value * Footprint.TILE_SIZE / 60d);
                case rotation -> unit.rotation = (float)value;
                case team -> setTeam(unit, ((byte)(int)value) & 0xff);
                case flag -> unit.flag = value;
                case speed -> unit.statuses.statusSpeed(unit, Mathf.clamp((float)value, 0f, 1000f));
                case armor -> unit.statuses.statusArmor(unit, Math.max((float)value, 0f));
                default -> {}
            }
        }else if(key instanceof ContentDef content){
            double value = p.num(s.value());
            if(content.type() == ContentType.ITEM){
                unit.stackItem = content.id();
                unit.stackAmount = Mathf.clamp((int)value, 0, unit.params().itemCapacity());
            }else if(content.type() == ContentType.STATUS){
                unit.statuses.setDuration(unit, content.id(), (float)value * 60f);
            }
        }
    }

    /** {@code setProp(team)}: a player's unit takes its player along. */
    /**
     * {@code UnitComp.setProp(payloadType, value)} (UnitComp.java:399), carriers only: a synthetic block makes a new
     * building of the unit's team, a unit type a new unit of it, each carried last if there is room; null drops the last
     * payload into nothing. A refused building or unit was still made, its id drawn.
     */
    private void setPayloadType(Unit unit, Object value){
        if(unit.payloads == null) return;
        if(value instanceof BlockDef block && block.type() == ContentType.BLOCK){
            if(Construction.of(world).info(block.id()).synthetic){
                Building build = world.buildings().createPayload(block, unit.team);
                if(Payloads.canPickup(unit, build)) unit.payloads.add(new Payload.BuildPayload(build));
            }
        }else if(value instanceof UnitDef type && type.type() == ContentType.UNIT){
            Unit made = Unit.create(world, type, unit.team);
            if(Payloads.canPickup(unit, made)) unit.payloads.add(new Payload.UnitPayload(made));
        }else if(value == null && !unit.payloads.isEmpty()){
            unit.payloads.pop();
        }
    }

    private static void setTeam(Unit unit, int team){
        Player player = unit.player();
        if(player != null) player.team(team);
        unit.team = team;
    }

    /** SetRuleI (LExecutor.java:1796); {@code wave} and {@code currentWaveTime} set the {@link WaveState}, if any. */
    private void setRule(Processor p, SetRule s){
        Rules rules = rules();
        int value = s.value();
        switch(s.rule()){
            case waveTimer -> rules.waveTimer = p.bool(value);
            case wave -> {
                WaveState waves = WaveState.of(world);
                if(waves != null) waves.wave = Math.max(p.numi(value), 1);
            }
            case currentWaveTime -> {
                WaveState waves = WaveState.of(world);
                if(waves != null) waves.wavetime = Math.max(p.numf(value) * 60f, 0f);
            }
            case waves -> rules.waves = p.bool(value);
            case waveSending -> rules.waveSending = p.bool(value);
            case attackMode -> rules.attackMode = p.bool(value);
            case waveSpacing -> rules.waveSpacing = p.numf(value) * 60f;
            case enemyCoreBuildRadius -> rules.enemyCoreBuildRadius = p.numf(value) * 8f;
            case dropZoneRadius -> rules.dropZoneRadius = p.numf(value) * 8f;
            case unitCap -> rules.unitCap = Math.max(p.numi(value), 0);
            case lighting -> rules.lighting = p.bool(value);
            case canGameOver -> rules.canGameOver = p.bool(value);
            case pauseDisabled -> rules.pauseDisabled = p.bool(value);
            case musicVolume -> rules.musicVolume = Mathf.clamp(p.numf(value));
            case mapArea -> {
                int x = p.numi(s.p1()), y = p.numi(s.p2()), w = p.numi(s.p3()), h = p.numi(s.p4());
                if(!checkMapArea(x, y, w, h, false)){
                    checkMapArea(x, y, w, h, true);
                    publish(new LogicEvent.SetMapArea(world.ticks(), x, y, w, h));
                }
            }
            case ambientLight -> rules.ambientLight = fromDouble(p.num(value));
            case unitLight -> rules.unitLight = p.bool(value);
            case solarMultiplier -> rules.solarMultiplier = Math.max(p.numf(value), 0f);
            case dragMultiplier -> rules.dragMultiplier = Math.max(p.numf(value), 0f);
            case ban, unban -> {
                boolean ban = s.rule() == LogicRule.ban;
                if(p.obj(value) instanceof ContentDef content && (content.type() == ContentType.BLOCK || content.type() == ContentType.UNIT)){
                    TreeSet<Integer> banned = content.type() == ContentType.BLOCK ? rules.bannedBlocks : rules.bannedUnits;
                    if(ban) banned.add(content.id());
                    else banned.remove(content.id());
                }
            }
            case unitHealth, unitBuildSpeed, unitMineSpeed, unitCost, unitDamage, blockHealth, blockDamage, buildSpeed, rtsMinSquad,
                 rtsMinWeight -> {
                int team = team(p, s.p1());
                if(team >= 0){
                    float num = p.numf(value);
                    TeamRule rule = rules.teams.get(team);
                    switch(s.rule()){
                        case buildSpeed -> rule.buildSpeedMultiplier = Mathf.clamp(num, 0.001f, 50f);
                        case unitHealth -> rule.unitHealthMultiplier = Math.max(num, 0.001f);
                        case unitBuildSpeed -> rule.unitBuildSpeedMultiplier = Mathf.clamp(num, 0f, 50f);
                        case unitMineSpeed -> rule.unitMineSpeedMultiplier = Math.max(num, 0f);
                        case unitCost -> rule.unitCostMultiplier = Math.max(num, 0f);
                        case unitDamage -> rule.unitDamageMultiplier = Math.max(num, 0f);
                        case blockHealth -> rule.blockHealthMultiplier = Math.max(num, 0.001f);
                        case blockDamage -> rule.blockDamageMultiplier = Math.max(num, 0f);
                        case rtsMinWeight -> rule.rtsMinWeight = num;
                        case rtsMinSquad -> rule.rtsMinSquad = (int)num;
                        default -> {}
                    }
                }
            }
        }
    }

    /** {@code Color.fromDouble}: the RGBA8888 in a double's low bits. */
    private static Color fromDouble(double value){
        int rgba = (int)Double.doubleToRawLongBits(value);
        return new Color(((rgba & 0xff000000) >>> 24) / 255f, ((rgba & 0x00ff0000) >>> 16) / 255f, ((rgba & 0x0000ff00) >>> 8) / 255f,
            (rgba & 0x000000ff) / 255f);
    }

    /** {@code LExecutor.checkMapArea}: whether the area is already set; with {@code set}, sets it (World.checkMapArea). */
    private boolean checkMapArea(int x, int y, int w, int h, boolean set){
        Rules rules = rules();
        int width = world.tiles().width(), height = world.tiles().height();
        x = Math.max(x, 0);
        y = Math.max(y, 0);
        w = Math.min(width, w);
        h = Math.min(height, h);
        boolean full = x == 0 && y == 0 && w == width && h == height;
        if(rules.limitMapArea){
            if(rules.limitX == x && rules.limitY == y && rules.limitWidth == w && rules.limitHeight == h){
                return true;
            }else if(full){
                // the area covers the whole map: the rule is off
                if(set){
                    int prevX = rules.limitX, prevY = rules.limitY, prevW = rules.limitWidth, prevH = rules.limitHeight;
                    rules.limitMapArea = false;
                    world.buildings().checkMapArea(prevX, prevY, prevW, prevH);
                    return false;
                }
            }
        }else if(full){
            return true;
        }
        if(set){
            int prevX = rules.limitX, prevY = rules.limitY, prevW = rules.limitWidth, prevH = rules.limitHeight;
            if(!rules.limitMapArea){
                // it was never on, so the old bounds don't apply
                prevW = 0;
                prevH = 0;
                prevX = -1;
                prevY = -1;
            }
            rules.limitMapArea = true;
            rules.limitX = x;
            rules.limitY = y;
            rules.limitWidth = w;
            rules.limitHeight = h;
            world.buildings().checkMapArea(prevX, prevY, prevW, prevH);
        }
        return false;
    }

    @Override
    public Object unbox(Object value){
        return value instanceof Boxed boxed ? boxed.unbox(world) : value;
    }

    @Override
    public void sync(Processor p, int variable, Object value){
        if(p.building() instanceof Building building){
            Object synced = value instanceof LogicTeam team ? new ConfigValue.Team(team.id()) : sim(value);
            publish(new LogicEvent.SyncVariable(world.ticks(), building.tile(), variable, synced));
        }
    }

    /** Map loads send nothing: no client is there yet. */
    void publish(WorldEvent event){
        if(!world.buildings().loading()) world.publish(event);
    }

    //endregion
}
