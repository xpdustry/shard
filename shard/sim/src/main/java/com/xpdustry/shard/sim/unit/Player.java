package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A player's world state (vanilla's {@code PlayerComp}): what {@code Player.writeSync} sends and the server keeps
 * between input packets. The connection, chat and plan previews belong to {@code :server}.
 *
 * <p>Lifecycle, as vanilla's generated {@code Player}: {@link #create} draws the entity id; {@link #add()} joins
 * {@code Groups.player} ({@link Players}) and {@code Groups.sync}; {@link #remove()} leaves them and lets go of the
 * unit. {@link #update()} runs in the {@code PLAYERS} phase: it follows the controlled unit, or, when dead, waits
 * {@value #DEATH_DELAY} ticks and asks the world's {@link PlayerSpawns} for a new unit.
 *
 * <p>Owner: WP21 (state, unit binding); WP23 (spawning at cores, through {@link PlayerSpawns}).
 */
public final class Player extends Entity implements Checkpointable{
    /** {@code PlayerComp.deathDelay}. */
    public static final float DEATH_DELAY = 60f;

    static final ActiveList.Slot<Player> SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(Player player){
            return player.playerIndex;
        }

        @Override
        public void set(Player player, int position){
            player.playerIndex = position;
        }
    };

    @Derived("Players.read: the position in Groups.player")
    int playerIndex = -1;

    // PosComp
    public float x, y;
    /** The controlled unit, or null. Check {@link #dead()}. */
    Unit unit;
    public int team = VanillaIds.SHARDED;
    public boolean typing, shooting, boosting;
    /** The selected block's content id, or -1. */
    public int selectedBlock = -1;
    public int selectedRotation;
    public float mouseX, mouseY;
    /** The command id the last controlled unit had before this player took it, or -1. Not synced. */
    public int lastCommand = -1;
    public boolean admin;
    public String name = "frog";
    /** RGBA8888. */
    public int color;
    public float deathTimer;

    public Player(){
    }

    /** Vanilla's {@code Player.create()}: a new player with a fresh entity id, not yet added. */
    public static Player create(World world){
        var player = new Player();
        player.created(world);
        return player;
    }

    /** The controlled unit, or null. */
    public Unit unit(){
        return unit;
    }

    /** Vanilla's {@code dead()}: no unit, or one that is dead or gone. */
    public boolean dead(){
        return unit == null || !unit.isValid();
    }

    /**
     * Vanilla's {@code unit(unit)}: takes control of {@code next}, or of nothing. The unit taken over hands its
     * command to {@link #lastCommand}; the unit let go of returns to its type's controller and gets that command back.
     */
    public void unit(Unit next){
        if(unit == next) return;
        if(next != null && next.controller instanceof CommandAi ai) lastCommand = ai.command;
        if(unit != null){
            unit.resetController();
            if(lastCommand != -1 && unit.controller instanceof CommandAi ai) ai.command(unit, lastCommand);
        }
        unit = next;
        if(next != null){
            next.team(team);
            next.controller(new PlayerControl(this));
            // this player just became remote: snap the interpolation so it doesn't go wild (PlayerComp.java:308)
            if(next.isRemote()) RemoteSync.snap(next);
        }
    }

    /** Vanilla's {@code clearUnit()}. */
    public void clearUnit(){
        unit(null);
    }

    /** Vanilla's {@code team(team)}: the unit follows. */
    public void team(int team){
        this.team = team;
        if(unit != null) unit.team(team);
    }

    @Override
    public void add(){
        if(isAdded()) return;
        Players players = Players.of(world());
        if(players != null) players.list().add(this);
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.addSync(this);
        added(true);
    }

    @Override
    public void remove(){
        if(!isAdded()) return;
        Players players = Players.of(world());
        if(players != null) players.list().remove(this);
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.removeSync(this);
        added(false);
        // PlayerComp.remove
        if(unit != null) clearUnit();
    }

    /** {@code PlayerComp.update}, once per tick from {@code Groups.player.update()}. */
    @Override
    public void update(){
        if(unit != null && !unit.isValid()) clearUnit();
        if(!dead()){
            x = unit.x;
            y = unit.y;
            // a block unit passes the player's team on to its turret
            unit.team(team);
            deathTimer = 0;
            UnitParams params = unit.params();
            if(params.canBoost()){
                boolean boost = unit.onSolid() || boosting || (unit.isFlying() && !unit.canLand());
                unit.elevation = Mathf.approachDelta(unit.elevation, boost ? 1f : 0f, boost ? params.riseSpeed() : params.descentSpeed());
            }
        }else{
            PlayerSpawns spawns = PlayerSpawns.of(world());
            if(spawns.hasCore(this)){
                // a small delay, so the camera doesn't jump around
                deathTimer += 1f;
                if(deathTimer >= DEATH_DELAY){
                    spawns.requestSpawn(this);
                    deathTimer = 0;
                }
            }
        }
    }

    @Override
    public Copyable blank(){
        return new Player();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Player)into;
        out.playerIndex = playerIndex;
        out.x = x;
        out.y = y;
        out.unit = context.map(unit);
        out.team = team;
        out.typing = typing;
        out.shooting = shooting;
        out.boosting = boosting;
        out.selectedBlock = selectedBlock;
        out.selectedRotation = selectedRotation;
        out.mouseX = mouseX;
        out.mouseY = mouseY;
        out.lastCommand = lastCommand;
        out.admin = admin;
        out.name = name;
        out.color = color;
        out.deathTimer = deathTimer;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(x).f(y).i(unit == null ? -1 : unit.id()).i(team).bool(typing).bool(shooting).bool(boosting);
        hasher.i(selectedBlock).i(selectedRotation).f(mouseX).f(mouseY).i(lastCommand).bool(admin);
        hasher.bool(name != null);
        if(name != null) hasher.str(name);
        hasher.i(color).f(deathTimer);
        // a block unit is in no world list: its turret hashes it while it stands, and only this reference holds it
        // between the turret's removal and the player's next update (review 40)
        if(unit != null && unit.blockTile != null) unit.hashState(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("x", x).f("y", y).ref("unit", unit).i("team", team).bool("typing", typing).bool("shooting", shooting)
            .bool("boosting", boosting).i("selectedBlock", selectedBlock).i("selectedRotation", selectedRotation)
            .f("mouseX", mouseX).f("mouseY", mouseY).i("lastCommand", lastCommand).bool("admin", admin).str("name", name)
            .i("color", color).f("deathTimer", deathTimer);
    }

    @Override
    public void read(CheckpointIn in){
        x = in.f("x");
        y = in.f("y");
        unit = in.ref("unit", Unit.class);
        team = in.i("team");
        typing = in.bool("typing");
        shooting = in.bool("shooting");
        boosting = in.bool("boosting");
        selectedBlock = in.i("selectedBlock");
        selectedRotation = in.i("selectedRotation");
        mouseX = in.f("mouseX");
        mouseY = in.f("mouseY");
        lastCommand = in.i("lastCommand");
        admin = in.bool("admin");
        name = in.str("name");
        color = in.i("color");
        deathTimer = in.f("deathTimer");
    }

    @Override
    public String toString(){
        return "Player#" + id() + ":" + name;
    }
}
