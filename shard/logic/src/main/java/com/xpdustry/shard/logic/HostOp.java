package com.xpdustry.shard.logic;

import com.xpdustry.shard.logic.lang.*;

/**
 * A statement the world answers, with its operands as the assembler resolved them: enums as parsed, every {@code int}
 * a slot of the running {@link Processor}. Read a slot with {@link Processor#num(int)}, {@link Processor#obj(int)}…
 * and write one with {@link Processor#setnum(int, double)}, {@link Processor#setobj(int, Object)}…; those apply
 * vanilla's {@code LVar} rules (constants ignore writes, NaN and infinities become null).
 *
 * <p>The VM hands each one to {@link LogicHost#call(Processor, HostOp)} and charges one instruction, whatever the host
 * does. Components are named after vanilla's instruction fields ({@code LExecutor.RadarI}…), where the vanilla
 * semantics are documented.
 */
public sealed interface HostOp{
    /** {@code radar} (any building or unit with a range) and {@code uradar} ({@code radar} is then {@code @unit}). */
    record Radar(RadarTarget target1, RadarTarget target2, RadarTarget target3, RadarSort sort, int radar, int sortOrder,
                 int output) implements HostOp{
    }

    /** {@code ubind}: set {@code @unit} with {@link Processor#bindUnit(Object)}. */
    record UnitBind(int type) implements HostOp{
    }

    record UnitControl(LUnitControl type, int p1, int p2, int p3, int p4, int p5) implements HostOp{
    }

    record UnitLocate(LLocate locate, BlockFlag flag, int enemy, int ore, int outX, int outY, int outFound,
                      int outBuild) implements HostOp{
    }

    // world processors only (vanilla's privileged statements)

    /** {@code query}: results go into {@code @queries} (see {@link Processor#queries()}). */
    record Query(QueryShape shape, QueryType type, int team, int x, int y, int width, int height) implements HostOp{
    }

    record GetBlock(TileLayer layer, int x, int y, int result) implements HostOp{
    }

    record SetBlock(TileLayer layer, int x, int y, int block, int team, int rotation) implements HostOp{
    }

    record SpawnUnit(int type, int x, int y, int rotation, int team, int result, int effect) implements HostOp{
    }

    record SpawnBullet(int result, int from, int index, int x, int y, int rotation, int team, int owner, int damage,
                       int velocityScl, int lifeScl, int aimX, int aimY) implements HostOp{
    }

    record ApplyStatus(boolean clear, int effect, int unit, int duration) implements HostOp{
    }

    record WeatherSense(int weather, int to) implements HostOp{
    }

    record WeatherSet(int weather, int state) implements HostOp{
    }

    record SpawnWave(int natural, int x, int y) implements HostOp{
    }

    record SetRule(LogicRule rule, int value, int p1, int p2, int p3, int p4) implements HostOp{
    }

    /**
     * {@code message}. The VM already set {@code outSuccess} to 1 and, for anything but {@code mission}, cleared the
     * text buffer: a headless server shows nothing. For {@code mission} the host reads {@link Processor#text()}; the VM
     * clears it afterwards.
     */
    record Message(MessageType type, int duration, int outSuccess) implements HostOp{
    }

    /** {@code cutscene}: client-side only; a headless server does nothing. */
    record Cutscene(CutsceneAction action, int p1, int p2, int p3, int p4) implements HostOp{
    }

    /** {@code effect}: {@code type} is the raw effect name (vanilla's {@code LogicFx.get}); visual only. */
    record Effect(String type, int x, int y, int rotation, int color, int data) implements HostOp{
    }

    record Explosion(int team, int x, int y, int radius, int damage, int air, int ground, int pierce,
                     int effect) implements HostOp{
    }

    record Fetch(FetchType type, int result, int team, int extra, int index) implements HostOp{
    }

    /**
     * {@code sync}: never reaches {@link LogicHost#call}. The VM applies vanilla's rate limit per variable and calls
     * {@link LogicHost#sync(Processor, int, Object)}.
     */
    record Sync(int variable) implements HostOp{
    }

    /** {@code clientdata}: assembled only when the rules allow logic data; a server-side call does nothing. */
    record ClientData(int channel, int value, int reliable) implements HostOp{
    }

    record GetFlag(int result, int flag) implements HostOp{
    }

    record SetFlag(int flag, int value) implements HostOp{
    }

    record SetProp(int type, int of, int value) implements HostOp{
    }

    /** {@code playsound}: client-side only. */
    record PlaySound(boolean positional, int id, int volume, int pitch, int pan, int x, int y, int limit) implements HostOp{
    }

    /** {@code playmusic}: client-side only. */
    record PlayMusic(int name, int interrupt) implements HostOp{
    }

    /** {@code setmarker}: {@code flushText} and {@code texture} consume {@link Processor#text()} when the marker exists. */
    record SetMarker(LMarkerControl type, int id, int p1, int p2, int p3) implements HostOp{
    }

    /** {@code makemarker}: {@code type} is the raw marker type name. */
    record MakeMarker(String type, int id, int x, int y, int replace) implements HostOp{
    }

    /**
     * {@code localeprint}: never reaches {@link LogicHost#call}. The VM appends
     * {@link LogicHost#localeString(String)} of the name to the text buffer.
     */
    record LocalePrint(int value) implements HostOp{
    }
}
