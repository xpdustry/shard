package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.IntSet;

import java.util.*;

/**
 * One processor's mlog state: vanilla's {@code LExecutor} plus the logic half of {@code LogicBuild} (code, links,
 * {@code ipt}, accumulator, tag). {@code :sim}'s processor building owns one and calls {@link #update(boolean, float)}
 * in its own turn of the building update, which is where vanilla runs it (ARCHITECTURE.md §3).
 *
 * <p>What differs from vanilla is only how the work is done:
 * <ul>
 * <li><b>Links are event-driven.</b> Vanilla re-checks every link every tick. Here the world tells the
 *     {@link LinkIndex} when a linked tile changes, and the next {@link #update} runs vanilla's link loop once
 *     ({@link #refreshLinks()}). Between changes that loop changes nothing, so the result is the same.</li>
 * <li><b>Waiting processors park.</b> After a {@code wait} that hasn't elapsed (or a {@code stop}), the next ticks
 *     are known: the accumulator grows to its cap and the wait timer adds {@code Δ/60} per tick. The processor works
 *     out on which call the wait ends, and until then an update only counts calls. Anything that reads or changes the
 *     state first replays the counted calls with the same float operations, so the state is always vanilla's
 *     ({@link #materialize()}).</li>
 * <li><b>Programs are shared</b> through the {@link ProgramCache}; per processor there are three slot arrays.</li>
 * </ul>
 *
 * <p>Not thread-safe: it belongs to its world's thread.
 */
public final class Processor{
    /** {@code LogicBlock.maxInstructionScale}: the accumulator caps at this many ticks of instructions. */
    public static final int maxInstructionScale = 5;
    /** {@code LogicBlock.maxLinks}. */
    public static final int maxLinks = 6000;
    /** How far ahead a parked processor looks for the end of its wait; a longer wait re-parks. */
    static final int parkHorizon = 4096;
    /** {@code SyncI.syncInterval} is 50 ms of wall clock; in ticks, a variable syncs at most once per 4 ticks. */
    static final int syncTicks = 3;

    final LogicWorld world;
    /** The building, which is {@code @this}; null for a {@link #script}. */
    final Object building;
    public final boolean privileged;
    /** {@code LExecutor.runLogicScript}'s executor: no building, links or {@code updateCode} constants. */
    public final boolean script;
    /** {@code LogicBlock.instructionsPerTick}, and the most {@code setrate} allows. */
    final int blockIpt, maxIpt;
    /** The building's origin tile and {@code World.conv} of its centre, for {@code @thisx}/{@code @thisy} and links. */
    final int tile;
    final double thisx, thisy;

    Program program;
    double[] n;
    Object[] o;
    boolean[] k;
    /** Per instruction, {@code WaitI.curTime}. */
    float[] waitTime;

    /** The next instruction when the counter is integral. */
    int pc;
    /** {@code @counter} is fractional: run one instruction at a time with a double counter. */
    boolean exact;
    double cexact;
    /** Slot 0 holds vanilla's exact {@code @counter} (after a slow instruction); otherwise it is {@link #pc}. */
    boolean rawCounter;
    boolean yield;
    /** The wait or stop the last yield was on, or -1. */
    int yieldedAt = -1;

    final StringBuilder text = new StringBuilder();
    /** {@code LExecutor.links}: the valid links' buildings, which {@code getlink} indexes. */
    Object[] linkTargets = {};

    int ipt;
    float accumulator;
    /** {@code LExecutor.team}, the building's team: it picks the logic budget share, and links depend on it. */
    int team;
    /** World processors only: {@code LogicBuild.tag} and {@code iconTag}. */
    public String tag;
    public char iconTag;

    String code = "";
    final ArrayList<Link> links = new ArrayList<>();
    boolean checkedDuplicates;
    boolean linksDirty = true;
    /** Bumped by every load, so a link's cached variable from an older program is recognisably stale. */
    private int generation;
    /** {@code LogicBuild.linkMap}: link name to index, built on first use and only dropped by a code change. */
    private Map<String, Integer> linkMap; // lookups only, never iterated
    /** The names {@link #linkMap} was built from, which checkpoints keep. */
    private String[] linkMapNames;
    /** The deferred half of a save load ({@code LogicBuild.loadBlock}), or null. */
    private LogicSave pendingLoad;
    private int pendingRevision;
    private int[] watched = {};

    // parking (see the class comment)
    private int parkedAt = -1;
    private int parkCalls, parkWake;
    private float parkDelta, parkWorldDelta;

    // accounting
    long executed;
    long weightExtra;

    /** Host scratch per instruction ({@code RadarI}'s cache and timer, {@code ulocate}'s cache…), allocated on use. */
    private Object[] instructionState;
    /** Per exported variable, the tick it last synced. */
    private long[] syncedAt;
    /** Free for the host ({@code LExecutor.binds}…). Not saved. */
    public Object hostData;
    /** The index of the host statement running now: with {@link #generation()}, it names vanilla's instruction object. */
    int hostInstruction = -1;

    /**
     * A processor of {@code building} on {@code world}, with no code. {@code instructionsPerTick} and
     * {@code maxInstructionsPerTick} are the block's; a normal processor's {@code setrate} caps at the first.
     */
    public Processor(LogicWorld world, Object building, boolean privileged, int instructionsPerTick, int maxInstructionsPerTick,
                     int tile, double thisx, double thisy){
        this(world, building, privileged, false, instructionsPerTick, maxInstructionsPerTick, tile, thisx, thisy);
        load(world.assemble("", options()));
    }

    private Processor(LogicWorld world, Object building, boolean privileged, boolean script, int instructionsPerTick,
                      int maxInstructionsPerTick, int tile, double thisx, double thisy){
        this.world = world;
        this.building = building;
        this.privileged = privileged;
        this.script = script;
        this.blockIpt = instructionsPerTick;
        this.maxIpt = privileged ? maxInstructionsPerTick : instructionsPerTick;
        this.tile = tile;
        this.thisx = thisx;
        this.thisy = thisy;
        this.ipt = instructionsPerTick;
    }

    /**
     * {@code LExecutor.runLogicScript}'s executor for {@code code}: privileged, with no building (so no links,
     * {@code @this} null, {@code @ipt} 0 and {@code setrate} ignored) and the derelict team. Run it with
     * {@link #runScript}; code that fails to parse runs nothing, as vanilla returns then.
     */
    public static Processor script(LogicWorld world, String code){
        var p = new Processor(world, null, true, true, 0, 0, -1, 0, 0);
        p.load(world.assemble("", p.options()));
        p.setCode(code);
        return p;
    }
    //region code and links (LogicBuild)

    /**
     * {@code LogicBuild.updateCode(code)}: reassemble against the current links. All variables reset. Code that fails
     * to parse becomes {@code ""}, as in vanilla's catch, which leaves the links' validity and {@code getlink}'s array
     * as they were.
     */
    public void setCode(String code){
        materialize();
        this.code = code == null ? "" : code;
        linkMap = null;
        boolean[] valid = new boolean[links.size()];
        ArrayList<String> names = new ArrayList<>();
        for(int i = 0; i < valid.length; i++){
            Link link = links.get(i);
            Object target = world.host.building(link.x, link.y);
            valid[i] = target != null && world.host.validLink(this, target);
            if(valid[i]) names.add(link.name);
        }
        Program program = world.assemble(this.code, script ? options() : new Assembler.Options(privileged, world.allowLogicData, names));
        if(program.malformed){
            this.code = "";
            Object[] targets = linkTargets;
            load(world.assemble("", options(links, false)));
            linkTargets = targets;
            return;
        }
        for(int i = 0; i < valid.length; i++) links.get(i).valid = valid[i];
        load(program);
        // the link constants were just created: they are the valid links' variables now (invalid links keep theirs)
        for(int i = 0, v = 0; i < links.size(); i++){
            Link link = links.get(i);
            if(link.valid){
                link.variable = program.variable(program.linkNames[v++]);
                link.generation = generation;
            }
        }
    }

    /**
     * {@code LogicBuild.readCompressed(data, true)}: a config upload, links relative to this building. Invalid data is
     * ignored; data that fails after the links are read keeps them, as vanilla does.
     */
    public void configure(byte[] data){
        readCompressed(data, true);
    }

    void readCompressed(byte[] data, boolean relative){
        materialize();
        LogicConfig.Decoded decoded = LogicConfig.decode(data);
        if(decoded == null) return;
        links.clear();
        // usedBuildings: duplicates of a building are dropped, and names are fixed to match the block
        IntSet used = new IntSet();
        for(LogicConfig.Link raw : decoded.links()){
            int x = raw.x(), y = raw.y();
            if(relative){
                x = (short)(x + Point2.x(tile));
                y = (short)(y + Point2.y(tile));
            }
            String name = raw.name();
            Object target = world.host.building(x, y);
            if(target != null){
                if(!used.add(world.host.buildingId(target))) continue;
                String best = linkName(world.host.blockName(target));
                if(!name.startsWith(best)) name = findLinkName(world.host.blockName(target));
            }
            links.add(new Link(x, y, name, false));
        }
        rewatch();
        if(decoded.code() != null) setCode(decoded.code());
    }

    /**
     * {@code LogicBlock}'s {@code Integer} config: link or unlink {@code target}, which must be a valid link. @return
     * whether a link was removed; then the host re-enables the target if its block auto-resets and this processor
     * disabled it.
     */
    public boolean toggleLink(Object target){
        materialize();
        if(target == null || !world.host.validLink(this, target)) return false;
        int pos = world.host.buildingTile(target);
        int oldSize = links.size();
        for(int i = links.size() - 1; i >= 0; i--){
            Link link = links.get(i);
            if(world.host.building(link.x, link.y) == target){
                setLink(link, null);
                links.remove(i);
            }
        }
        boolean removed = oldSize > links.size();
        if(!removed){
            Link link = new Link(Point2.x(pos), Point2.y(pos), findLinkName(world.host.blockName(target)), true);
            setLink(link, target);
            links.add(link);
        }
        updateLinks();
        rewatch();
        return removed;
    }

    /** Something at one of this processor's link tiles changed: run the link loop at the next update. */
    public void markLinksDirty(){
        linksDirty = true;
    }

    /**
     * The link half of {@code LogicBuild.updateTile}, run when the links may have changed: drop duplicate links once,
     * then follow every link whose building or validity changed, renaming it if its block changed.
     */
    void refreshLinks(){
        linksDirty = false;
        LogicHost host = world.host;
        if(!checkedDuplicates){
            checkedDuplicates = true;
            IntSet seen = new IntSet();
            boolean removed = links.removeIf(link -> {
                Object build = host.building(link.x, link.y);
                return build != null && !seen.add(host.buildingId(build));
            });
            if(removed) rewatch();
        }

        boolean changed = false, updates = true;
        while(updates){
            updates = false;
            for(int i = 0; i < links.size(); i++){
                Link l = links.get(i);
                Object cur = host.building(l.x, l.y);
                boolean valid = cur != null && host.validLink(this, cur);
                Object lastBlock = l.lastBuild == null ? null : host.block(l.lastBuild);
                if(l.lastBuild == null) l.lastBuild = cur;
                if(valid != l.valid || l.lastBuild != cur){
                    materialize();
                    l.lastBuild = cur;
                    changed = true;
                    l.valid = valid;
                    setLink(l, null); // always clear the old variable: it may get a new name

                    if(valid){
                        String blockName = host.blockName(cur);
                        // saves don't store the block a link was made for, so a new link checks its name
                        if((lastBlock != null && host.block(cur) != lastBlock) || (lastBlock == null && !l.name.startsWith(linkName(blockName)))){
                            l.variable = -1;
                            l.name = "";
                            l.name = findLinkName(blockName);
                        }
                        // remove redundant links to the same building
                        for(int j = links.size() - 1; j >= 0; j--){
                            Link other = links.get(j);
                            if(other != l && host.building(other.x, other.y) == cur){
                                setLink(other, null);
                                links.remove(j);
                            }
                        }
                        setLink(l, cur);
                        updates = true;
                        break;
                    }
                }
            }
        }
        if(changed){
            updateLinks();
            rewatch();
        }
    }

    /** {@code LogicLink.trySet}: point the link's variable at {@code value}, or make it a plain variable again. */
    private void setLink(Link link, Object value){
        if(link.variable < 0){
            link.variable = program.variable(link.name);
            link.generation = generation;
            if(link.variable < 0) return;
        }
        // quirk kept: the cached LVar may belong to a program that was since replaced, and setting it does nothing
        if(link.generation != generation) return;
        int s = program.exportOrder[link.variable];
        // LVar.setlink: a linked building is a constant, an unlinked one a writable null
        o[s] = value;
        k[s] = value != null;
    }

    /** {@code LogicBuild.updateLinks}: rebuild {@code getlink}'s array and {@code @links}. */
    private void updateLinks(){
        int valid = 0;
        for(Link link : links) if(link.valid) valid++;
        Object[] targets = new Object[valid];
        int index = 0;
        for(Link link : links){
            if(link.valid) targets[index++] = world.host.building(link.x, link.y);
        }
        linkTargets = targets;
        // past the constant flag, as vanilla writes linksVar.numval
        if(program.linksSlot >= 0) n[program.linksSlot] = valid;
    }

    /** {@code LogicBuild.findLinkName}: the block's link name with the lowest free number. */
    String findLinkName(String blockName){
        String name = linkName(blockName);
        BitSet taken = new BitSet();
        int max = 1;
        for(Link other : links){
            if(other.name.startsWith(name)){
                try{
                    int value = Integer.parseInt(other.name.substring(name.length()));
                    // vanilla's Bits.set(-n) allocates a huge array and sets a bit nobody reads; skip it
                    if(value >= 0) taken.set(value);
                    max = Math.max(value, max);
                }catch(NumberFormatException ignored){
                    // a name that doesn't end in a number takes nothing
                }
            }
        }
        int out = 0;
        for(int i = 1; i < max + 2; i++){
            if(!taken.get(i)){
                out = i;
                break;
            }
        }
        return name + out;
    }

    /** {@code LogicBlock.getLinkName}: the last dash-separated part, skipping a trailing {@code large} or number. */
    static String linkName(String blockName){
        if(blockName.indexOf('-') == -1) return blockName;
        String[] split = blockName.split("-");
        String last = split[split.length - 1];
        if(split.length >= 2 && (last.equals("large") || canParseFloat(last))) return split[split.length - 2];
        return last;
    }

    /** Arc's {@code Strings.canParseFloat}. */
    private static boolean canParseFloat(String text){
        return (float)MlogMath.parseDouble(text, Float.NEGATIVE_INFINITY) != Float.NEGATIVE_INFINITY;
    }

    private Assembler.Options options(){
        return options(links, true);
    }

    /** The options for assembling against {@code links}' valid names, or none. */
    private Assembler.Options options(List<Link> links, boolean named){
        if(script) return Assembler.Options.script(world.allowLogicData);
        ArrayList<String> names = new ArrayList<>();
        if(named) for(Link link : links) if(link.valid) names.add(link.name);
        return new Assembler.Options(privileged, world.allowLogicData, names);
    }

    /** {@code LExecutor.load} plus the per-processor constants {@code updateCode} sets. */
    private void load(Program program){
        this.program = program;
        generation++;
        n = program.initNum.clone();
        o = program.initObj.clone();
        k = program.initConst.clone();
        waitTime = new float[program.length];
        pc = 0;
        exact = false;
        rawCounter = false;
        yield = false;
        yieldedAt = -1;
        parkedAt = -1;
        text.setLength(0);
        instructionState = null;
        syncedAt = null;

        int valid = 0;
        for(Link link : links) if(link.valid) valid++;
        Object[] targets = new Object[valid];
        for(int i = 0, index = 0; i < links.size(); i++){
            Link link = links.get(i);
            if(link.valid){
                Object target = world.host.building(link.x, link.y);
                targets[index] = target;
                // the empty program that replaces malformed code has no link constants
                if(index < program.linkSlots.length) o[program.linkSlots[index]] = target;
                index++;
            }
        }
        linkTargets = targets;
        if(program.linksSlot >= 0) n[program.linksSlot] = valid;
        n[program.iptSlot] = script ? 0 : ipt;
        o[2] = building;
        if(program.thisxSlot >= 0) n[program.thisxSlot] = thisx;
        if(program.thisySlot >= 0) n[program.thisySlot] = thisy;
    }

    /**
     * After any change to the link list: re-registers the link tiles with the world's {@link LinkIndex}, and polls the
     * links in the next turn, as vanilla does every turn (a new link records its building there).
     */
    private void rewatch(){
        linksDirty = true;
        watch();
    }

    /** Registers the link tiles with the world's {@link LinkIndex}, which a copy or a restore rebuilds this way. */
    private void watch(){
        int[] now = new int[links.size()];
        for(int i = 0; i < now.length; i++) now[i] = Point2.pack(links.get(i).x, links.get(i).y);
        world.links.update(this, watched, now);
        watched = now;
    }

    /** The building is gone: stop watching its links. */
    public void dispose(){
        world.links.update(this, watched, new int[0]);
        watched = new int[0];
    }

    //endregion
    //region running (LogicBuild.updateTile)

    /**
     * The building's turn: {@code LogicBuild.updateTile} after {@code checkReadCode}. The links are followed first;
     * then, if {@code run} (vanilla: enabled, and not a world processor while {@code disableWorldProcessors}), the
     * accumulator loop runs. {@code edelta} is the building's {@code efficiency * Δ * timeScale}.
     */
    public void update(boolean run, float edelta){
        checkReadCode();
        if(linksDirty) refreshLinks();
        if(run && program.length > 0) run(edelta);
    }

    /** The accumulator loop, scaled by the world's logic budget factor for this processor (1 by default). */
    void run(float edelta){
        float e = edelta * world.budget.factor(this);
        if(parkedAt >= 0){
            if(e == parkDelta && world.delta == parkWorldDelta && parkCalls + 1 < parkWake){
                parkCalls++;
                world.parkedCalls++;
                return;
            }
            materialize();
        }

        refreshGlobals();
        if(accumulator > maxInstructionScale * ipt) accumulator = maxInstructionScale * ipt;
        int budget = (int)accumulator;
        int done = 0;
        boolean yielded = false;
        if(budget > 0){
            weightExtra = 0;
            yieldedAt = -1;
            done = Interp.run(this, budget); // the yielding instruction is not charged
            yielded = yield;
            yield = false;
            executed += done;
            world.executed(this, done, done + weightExtra);
        }
        accumulator -= done;
        accumulator += e * ipt;
        if(yielded) tryPark(e);
    }

    /**
     * {@code LExecutor.runLogicScript}'s loop, for a {@link #script}: up to {@code maxInstructions - 1} instructions,
     * one at a time, stopping once the counter leaves the program (unless {@code loop}, which wraps it) or an
     * instruction yields ({@code wait}, {@code stop}). The dynamic globals are the world's as they are now.
     */
    public void runScript(int maxInstructions, boolean loop){
        materialize();
        refreshGlobals();
        int length = program.length;
        for(int i = 1; i < maxInstructions; i++){
            double counter = counterNum();
            if((!loop && counter >= length || counter < 0) || yield) break;
            // runOnce: an empty program does nothing however long it loops
            if(length == 0) continue;
            executed += Interp.run(this, 1);
        }
    }

    /** {@code counter.numval}: the counter as a number, which an object written to it leaves as it was. */
    double counterNum(){
        if(rawCounter) return n[0];
        return exact ? cexact : pc;
    }

    private void refreshGlobals(){
        int[] slots = program.dynSlots, ids = program.dynIds;
        double[] values = world.globals;
        for(int i = 0; i < slots.length; i++) n[slots[i]] = values[ids[i]];
    }

    /**
     * After a run that yielded on an unfinished {@code wait} or a {@code stop}, which rewound the counter: until the
     * wait ends, each call either has no budget or runs that one instruction, which yields uncharged again. Find the
     * call on which it ends, at most {@link #parkHorizon} ahead.
     */
    private void tryPark(float e){
        int at = yieldedAt;
        if(at < 0) return;
        boolean isWait = program.opcode(at) == Program.WAIT;
        int valueSlot = isWait ? program.code[at * Program.STRIDE + 1] : -1;
        // a dynamic global (wait @tick) changes without anyone writing it: don't park on one
        if(isWait && program.slotKind[valueSlot] == Program.DYN_GLOBAL) return;
        double value = isWait ? num(valueSlot) : 0;
        float cap = maxInstructionScale * ipt, step = world.delta / 60f;
        float acc = accumulator, cur = isWait ? waitTime[at] : 0f;
        int wake = parkHorizon + 1;
        for(int call = 1; call <= parkHorizon; call++){
            if(acc > cap) acc = cap;
            if(acc >= 1f && isWait){
                if(cur >= value){
                    wake = call;
                    break;
                }
                cur += step;
            }
            acc += e * ipt;
        }
        if(wake == 1) return;
        parkedAt = at;
        parkDelta = e;
        parkWorldDelta = world.delta;
        parkCalls = 0;
        parkWake = wake;
    }

    /**
     * Applies the calls a parked processor only counted, with the float operations vanilla's loop does on them, and
     * unparks it. Everything that reads or changes the processor's state calls this first.
     */
    public void materialize(){
        if(parkedAt < 0) return;
        int at = parkedAt, calls = parkCalls;
        parkedAt = -1;
        parkCalls = 0;
        boolean isWait = program.opcode(at) == Program.WAIT;
        float cap = maxInstructionScale * ipt, step = parkWorldDelta / 60f, e = parkDelta;
        for(int call = 0; call < calls; call++){
            if(accumulator > cap) accumulator = cap;
            // the instruction runs, isn't done, rewinds and yields: uncharged
            if(accumulator >= 1f && isWait) waitTime[at] += step;
            accumulator += e * ipt;
        }
    }

    /** Whether the processor is parked on a wait or stop right now. */
    public boolean parked(){
        return parkedAt >= 0;
    }

    /** {@code step} wrote slot 0: derive the int counter, or switch to exact mode for a fractional one. */
    void counterWritten(){
        double next = n[0];
        int len = program.length;
        if(next == Math.rint(next) || next >= len || next < 0){
            exact = false;
            pc = next >= len || next < 0 ? len : (int)next;
        }else{
            exact = true;
            cexact = next;
        }
        rawCounter = true;
    }

    /** A host statement counts as its {@link LogicBudget.Cost} weight instead of one instruction. */
    void charge(LogicBudget.Cost cost){
        weightExtra += world.budget.weight(cost) - 1;
    }

    /** {@code SyncI}: rate-limited per variable, in ticks instead of wall-clock milliseconds. */
    void sync(int slot){
        if(k[slot] || building == null) return;
        int variable = program.varIndex(slot);
        if(variable < 0) return;
        if(syncedAt == null){
            syncedAt = new long[program.variables()];
            Arrays.fill(syncedAt, Long.MIN_VALUE / 2);
        }
        if(world.tick - syncedAt[variable] <= syncTicks) return;
        syncedAt[variable] = world.tick;
        world.host.sync(this, variable, value(slot));
    }

    //endregion
    //region other processors (LogicBuild.read/write)

    /**
     * {@code LogicBuild.read(position, output)}, for {@code reader}'s {@code read … this …}: a variable by name (or a
     * link of that name), or a link by index. The host checks {@code readable} first.
     */
    public void externalRead(Processor reader, int position, int output){
        materialize();
        if(reader.isObj(position) && reader.obj(position) instanceof String name){
            int variable = program.variable(name);
            if(variable < 0){
                reader.setobj(output, optionalLink(name));
                return;
            }
            if(reader.k[output]) return;
            int s = program.exportOrder[variable];
            if(s == 0){
                Object counter = counterValue();
                if(counter instanceof Double d) reader.setRaw(output, d);
                else reader.o[output] = counter;
            }else{
                Object x = o[s];
                reader.o[output] = x;
                if(x == Rt.NUM) reader.n[output] = n[s];
            }
        }else{
            int index = (int)reader.num(position);
            reader.setobj(output, index >= 0 && index < linkTargets.length ? linkTargets[index] : null);
        }
    }

    /** {@code LogicBuild.write(position, value)}: {@code writer} sets a non-constant variable of this one by name. */
    public void externalWrite(Processor writer, int position, int value){
        if(!(writer.isObj(position) && writer.obj(position) instanceof String name)) return;
        int variable = program.variable(name);
        if(variable < 0) return;
        int s = program.exportOrder[variable];
        if(k[s]) return;
        materialize();
        Object x = writer.o[value];
        if(s == 0) prepareCounterWrite();
        o[s] = x;
        if(x == Rt.NUM) n[s] = writer.n[value];
        if(s == 0) counterWritten();
    }

    /** {@code LogicBuild.optionalLink}: the building of the valid link named {@code name}, or null. */
    private Object optionalLink(String name){
        if(name == null || name.isEmpty()) return null;
        char ch = name.charAt(name.length() - 1);
        if(ch < '0' || ch > '9') return null;
        // quirk kept: the map is only rebuilt after a code change, so later link changes can make its indices stale
        if(linkMap == null){
            String[] names = new String[links.size()];
            for(int i = 0; i < names.length; i++) names[i] = links.get(i).name;
            buildLinkMap(names);
        }
        int index = linkMap.getOrDefault(name, -1);
        return index >= 0 && index < links.size() && links.get(index).valid ? links.get(index).lastBuild : null;
    }

    /** The link names at the time the map is built, in link order: ObjectIntMap.put keeps the last index of a name. */
    private void buildLinkMap(String[] names){
        linkMapNames = names;
        linkMap = new HashMap<>();
        for(int i = 0; i < names.length; i++) linkMap.put(names[i], i);
    }

    /** Before an outside write to {@code @counter}: make slot 0 hold vanilla's counter, which the write then changes. */
    private void prepareCounterWrite(){
        if(!rawCounter){
            n[0] = exact ? cexact : pc;
            o[0] = Rt.NUM;
        }
    }

    private void setRaw(int s, double value){
        o[s] = Rt.NUM;
        n[s] = Double.isFinite(value) ? value : 0; // LVar.set turns an invalid number into 0
    }

    //endregion
    //region state for hosts

    public LogicWorld world(){
        return world;
    }

    public Object building(){
        return building;
    }

    public Program program(){
        return program;
    }

    public String code(){
        return code;
    }

    /** The text buffer ({@code print}, {@code format}), at most 400 characters. */
    public StringBuilder text(){
        return text;
    }

    public void clearText(){
        text.setLength(0);
    }

    /** {@code @unit}. */
    public Object unit(){
        return o[1] == Rt.NUM ? null : o[1];
    }

    /** {@code ubind}'s {@code exec.unit.setconst(unit)}. */
    public void bindUnit(Object unit){
        o[1] = unit;
    }

    /** World processors only: the {@code @queries} value, a list the host owns, or null. */
    public Object queries(){
        int s = program.queriesSlot;
        return s < 0 ? null : obj(s);
    }

    public void queries(Object value){
        int s = program.queriesSlot;
        if(s >= 0) o[s] = value;
    }

    /** {@code LExecutor.links}. */
    public Object link(int index){
        return index >= 0 && index < linkTargets.length ? linkTargets[index] : null;
    }

    public int linkCount(){
        return linkTargets.length;
    }

    /** The link table, in order. */
    public List<Link> links(){
        return Collections.unmodifiableList(links);
    }

    public int ipt(){
        return ipt;
    }

    public int team(){
        return team;
    }

    /** {@code executor.team = team}, which {@code updateTile} sets each turn: a new team re-checks the links. */
    public void team(int team){
        if(this.team == team) return;
        this.team = team;
        linksDirty = true;
    }

    /**
     * Sets {@code LogicBuild.ipt} directly, as map tools and old saves do (vanilla doesn't clamp here); {@code @ipt}
     * follows. {@code setrate} is the clamped path.
     */
    public void ipt(int ipt){
        materialize();
        this.ipt = ipt;
        n[program.iptSlot] = ipt;
    }

    public float accumulator(){
        materialize();
        return accumulator;
    }

    /** Instructions this processor has run (yields are not instructions). */
    public long executed(){
        return executed;
    }

    /** Vanilla's {@code @counter} as {@code LExecutor.counter} holds it: a {@code Double}, or the object written to it. */
    public Object counterValue(){
        if(rawCounter) return o[0] == Rt.NUM ? (Object)n[0] : o[0];
        return (double)pc;
    }

    /** {@code WaitI.curTime} of instruction {@code index}, which must be a {@code wait}. */
    public float waitTime(int index){
        materialize();
        return waitTime[index];
    }

    /**
     * The host statement running now, as an index into the program: vanilla keys caches by the instruction object
     * ({@code LogicAI.execCache}, {@code radars}), which is this index in this {@link #generation()} of the code.
     */
    public int hostInstruction(){
        return hostInstruction;
    }

    /** Bumped by every load of code, as vanilla makes new instruction objects at every {@code load}. */
    public int generation(){
        return generation;
    }

    /** Host scratch for instruction {@code index} of the current program; it resets with the code. */
    public Object instructionState(int index){
        return instructionState == null ? null : instructionState[index];
    }

    public void instructionState(int index, Object value){
        if(instructionState == null) instructionState = new Object[program.length];
        instructionState[index] = value;
    }

    // slot access, with LVar's rules

    public double num(int s){
        return Rt.num(n, o, s);
    }

    /** {@code LVar.numf()}. */
    public float numf(int s){
        return (float)Rt.num(n, o, s);
    }

    /** {@code LVar.numi()}. */
    public int numi(int s){
        return (int)Rt.num(n, o, s);
    }

    /** {@code LVar.numOrNan()}: null reads as NaN. */
    public double numOrNan(int s){
        Object x = o[s];
        return x == Rt.NUM ? n[s] : x == null ? Double.NaN : 1;
    }

    public Object obj(int s){
        return Rt.obj(o, s);
    }

    public boolean bool(int s){
        return Rt.bool(n, o, s);
    }

    public boolean isObj(int s){
        return o[s] != Rt.NUM;
    }

    public boolean isConstant(int s){
        return k[s];
    }

    public void setnum(int s, double v){
        Rt.setnum(n, o, k, s, v);
    }

    public void setobj(int s, Object v){
        Rt.setobj(o, k, s, v);
    }

    public void setbool(int s, boolean v){
        Rt.setnum(n, o, k, s, v ? 1 : 0);
    }

    /** The value: a {@code Double} for numbers, else the object. Slot 0 is {@link #counterValue()}. */
    public Object value(int s){
        if(s == 0) return counterValue();
        return o[s] == Rt.NUM ? (Object)n[s] : o[s];
    }

    /** Exported variable {@code index}'s value, as {@code LExecutor.vars[index]} holds it. */
    public Object variable(int index){
        materialize();
        return value(program.exportOrder[index]);
    }

    //endregion
    //region saves (LogicBuild.write/read, version 5)

    /**
     * {@code LogicBuild.write}'s state after {@code writeBase}; {@code :compat} encodes it (Tier A). It changes nothing,
     * not even the parking: a joining player's world stream saves the live world's processors, which a replay of the
     * world's inputs doesn't, and an unparked processor counts and hashes otherwise from then on.
     */
    public LogicSave save(){
        if(parkedAt < 0) return saved();
        // the counted calls applied for the save only, then parked again as it was
        int at = parkedAt, calls = parkCalls;
        float acc = accumulator, wait = waitTime[at];
        materialize();
        LogicSave save = saved();
        parkedAt = at;
        parkCalls = calls;
        accumulator = acc;
        waitTime[at] = wait;
        return save;
    }

    private LogicSave saved(){
        ArrayList<LogicSave.Variable> variables = new ArrayList<>();
        Object unit = unit();
        // the unit is a constant outside the variables, so it goes first, on its own
        if(unit != null) variables.add(new LogicSave.Variable("@unit", unit));
        for(int i = 0; i < program.variables(); i++){
            int s = program.exportOrder[i];
            Object value = value(s);
            if(value == null) continue; // null is the default, so it is not written
            variables.add(new LogicSave.Variable(program.slotNames[s], value));
        }
        ArrayList<LogicSave.Link> savedLinks = new ArrayList<>();
        for(Link link : links) savedLinks.add(new LogicSave.Link(link.name, link.x, link.y));
        int savedIpt = privileged ? Math.clamp(ipt, 1, maxIpt) : ipt == blockIpt ? 0 : Math.clamp(ipt, 1, blockIpt);
        int[] waits = program.waits;
        float[] times = new float[waits.length];
        for(int i = 0; i < waits.length; i++) times[i] = waitTime[waits[i]];
        return new LogicSave(code, savedLinks, variables, (short)savedIpt, tag, iconTag, waits.clone(), times, accumulator);
    }

    /**
     * {@code LogicBuild.read}: the links and code (as {@code readCompressed(bytes, false)} applies them: links are
     * absolute and resolve against the buildings that exist now), {@code ipt}, the tag and the accumulator. The
     * variables and wait timers are applied by {@link #checkReadCode()}, which vanilla defers to {@code afterReadAll}.
     * Call it where vanilla reads the building, so {@link LogicHost#building(int, int)} answers as vanilla's world
     * does at that point (in tile order, only the buildings read so far exist). {@code revision} is the building's save
     * revision (Shard writes 5). A null {@link LogicSave#code()} is compressed data that failed after its links.
     */
    public void load(LogicSave save, int revision){
        materialize();
        links.clear();
        IntSet used = new IntSet();
        for(LogicSave.Link raw : save.links()){
            String name = raw.name();
            Object target = world.host.building(raw.x(), raw.y());
            if(target != null){
                if(!used.add(world.host.buildingId(target))) continue;
                String best = linkName(world.host.blockName(target));
                if(!name.startsWith(best)) name = findLinkName(world.host.blockName(target));
            }
            links.add(new Link(raw.x(), raw.y(), name, false));
        }
        rewatch();
        if(save.code() != null) setCode(save.code());

        if(privileged && revision >= 2) ipt = Math.clamp(save.ipt(), 1, maxIpt);
        if(!privileged && revision >= 5 && save.ipt() != 0) ipt = Math.clamp(save.ipt(), 1, blockIpt);
        if(revision >= 3){
            tag = save.tag();
            iconTag = save.iconTag();
        }
        if(revision >= 4) accumulator = save.accumulator();
        pendingLoad = save;
        pendingRevision = revision;
    }

    /**
     * {@code LogicBuild.checkReadCode}: after a {@link #load}, reassemble against the links as they are now and restore
     * the saved variables and wait timers. The world calls it from {@code afterReadAll}; {@link #update} calls it too.
     */
    public void checkReadCode(){
        LogicSave save = pendingLoad;
        if(save == null) return;
        pendingLoad = null;
        setCode(code);

        for(LogicSave.Variable variable : save.variables()){
            String name = variable.name();
            int s = program.namedSlot(name);
            if(s < 0) continue;
            Object value = world.host.unbox(variable.value());
            if(s == 1){
                // @unit is a constant that is restored anyway, and only to a unit: updateCode sets it to objval
                if(!(value instanceof Number)) o[1] = value;
                continue;
            }
            if(k[s]) continue;
            if(value instanceof Number number){
                n[s] = number.doubleValue();
                o[s] = Rt.NUM;
            }else{
                o[s] = value;
            }
            if(s == 0) counterWritten();
        }
        if(pendingRevision >= 4){
            int[] waits = save.waitIndices();
            float[] times = save.waitTimes();
            for(int i = 0; i < waits.length; i++){
                int index = waits[i];
                if(index >= 0 && index < program.length && program.opcode(index) == Program.WAIT) waitTime[index] = times[i];
            }
        }
    }

    //endregion
    //region live state (Shard's checkpoints, forks and hashes; see LogicState)

    /**
     * Writes everything this processor needs to continue exactly: the program as code and the options it was assembled
     * with, every slot, the counter's mode, waits, parking, links as last followed, rate limits and the host's
     * per-instruction state. Construction parameters (the block's, the building, the tile) are the caller's. The same
     * traversal feeds hashes, so a parked processor hashes as parked: a fork and a restore copy it as it is.
     *
     * @throws IllegalStateException between a save load and its {@link #checkReadCode()}, which is not a tick boundary
     */
    public void writeState(LogicState.Out out){
        if(pendingLoad != null) throw new IllegalStateException("a processor's save load is still pending");
        out.str(code);
        Assembler.Options options = program.options;
        out.bool(options.allowLogicData());
        out.i(options.links().size());
        for(String name : options.links()) out.str(name);
        out.i(generation);
        int slots = n.length;
        for(int i = 0; i < slots; i++){
            out.d(n[i]);
            LogicState.value(out, o[i]);
            out.bool(k[i]);
        }
        LogicState.floats(out, waitTime);
        out.i(pc);
        out.bool(exact);
        out.d(cexact);
        out.bool(rawCounter);
        out.bool(yield);
        out.i(yieldedAt);
        out.str(text.toString());
        out.i(linkTargets.length);
        for(Object target : linkTargets) LogicState.value(out, target);
        out.i(ipt);
        out.f(accumulator);
        out.i(team);
        out.str(tag);
        out.i(iconTag);
        out.i(links.size());
        for(Link link : links){
            out.i(link.x);
            out.i(link.y);
            out.str(link.name);
            out.bool(link.valid);
            LogicState.value(out, link.lastBuild);
            out.i(link.variable);
            out.i(link.generation);
        }
        out.bool(checkedDuplicates);
        out.bool(linksDirty);
        // the link map is stale on purpose (see optionalLink): kept as the names it was built from
        out.bool(linkMap != null);
        if(linkMap != null){
            out.i(linkMapNames.length);
            for(String name : linkMapNames) out.str(name);
        }
        out.i(parkedAt);
        out.i(parkCalls);
        out.i(parkWake);
        out.f(parkDelta);
        out.f(parkWorldDelta);
        out.l(executed);
        out.l(weightExtra);
        out.bool(instructionState != null);
        if(instructionState != null) for(Object value : instructionState) LogicState.value(out, value);
        out.bool(syncedAt != null);
        if(syncedAt != null) for(long at : syncedAt) out.l(at);
        LogicState.value(out, hostData);
    }

    /**
     * A processor of {@code building} on {@code world} with the state {@link #writeState} wrote, constructed with the
     * same block parameters as the one written. Its links are watched again on {@code world}'s {@link LinkIndex}.
     */
    public static Processor readState(LogicWorld world, Object building, boolean privileged, int instructionsPerTick,
                                      int maxInstructionsPerTick, int tile, double thisx, double thisy, LogicState.In in){
        var p = new Processor(world, building, privileged, false, instructionsPerTick, maxInstructionsPerTick, tile, thisx, thisy);
        p.read(in);
        return p;
    }

    private void read(LogicState.In in){
        code = in.str();
        boolean allowLogicData = in.bool();
        var names = new ArrayList<String>();
        for(int i = in.i(); i > 0; i--) names.add(in.str());
        program = world.assemble(code, script ? Assembler.Options.script(allowLogicData) : new Assembler.Options(privileged, allowLogicData, names));
        generation = in.i();
        Globals table = world.table;
        int slots = program.slots();
        n = new double[slots];
        o = new Object[slots];
        k = new boolean[slots];
        for(int i = 0; i < slots; i++){
            n[i] = in.d();
            o[i] = LogicState.value(in, table);
            k[i] = in.bool();
        }
        waitTime = LogicState.floats(in);
        pc = in.i();
        exact = in.bool();
        cexact = in.d();
        rawCounter = in.bool();
        yield = in.bool();
        yieldedAt = in.i();
        text.setLength(0);
        text.append(in.str());
        linkTargets = new Object[in.i()];
        for(int i = 0; i < linkTargets.length; i++) linkTargets[i] = LogicState.value(in, table);
        ipt = in.i();
        accumulator = in.f();
        team = in.i();
        tag = in.str();
        iconTag = (char)in.i();
        links.clear();
        for(int i = in.i(); i > 0; i--){
            int x = in.i(), y = in.i();
            var link = new Link(x, y, in.str(), in.bool());
            link.lastBuild = LogicState.value(in, table);
            link.variable = in.i();
            link.generation = in.i();
            links.add(link);
        }
        checkedDuplicates = in.bool();
        linksDirty = in.bool();
        if(in.bool()){
            var mapNames = new String[in.i()];
            for(int i = 0; i < mapNames.length; i++) mapNames[i] = in.str();
            buildLinkMap(mapNames);
        }
        parkedAt = in.i();
        parkCalls = in.i();
        parkWake = in.i();
        parkDelta = in.f();
        parkWorldDelta = in.f();
        executed = in.l();
        weightExtra = in.l();
        if(in.bool()){
            instructionState = new Object[program.length];
            for(int i = 0; i < instructionState.length; i++) instructionState[i] = LogicState.value(in, table);
        }
        if(in.bool()){
            syncedAt = new long[program.variables()];
            for(int i = 0; i < syncedAt.length; i++) syncedAt[i] = in.l();
        }
        hostData = LogicState.value(in, table);
        watch();
    }

    /**
     * A copy of this processor for {@code building} on {@code world}, another world's logic: the program is shared,
     * the host's objects are mapped through {@code remap}, and the copy watches its links on {@code world}.
     */
    public Processor copy(LogicWorld world, Object building, java.util.function.UnaryOperator<Object> remap){
        if(pendingLoad != null) throw new IllegalStateException("a processor's save load is still pending");
        var p = new Processor(world, building, privileged, script, blockIpt, maxIpt, tile, thisx, thisy);
        p.program = program;
        p.generation = generation;
        p.n = n.clone();
        p.o = new Object[o.length];
        for(int i = 0; i < o.length; i++) p.o[i] = i == 2 && o[2] == this.building ? building : LogicState.copy(o[i], remap);
        p.k = k.clone();
        p.waitTime = waitTime.clone();
        p.pc = pc;
        p.exact = exact;
        p.cexact = cexact;
        p.rawCounter = rawCounter;
        p.yield = yield;
        p.yieldedAt = yieldedAt;
        p.text.append(text);
        p.linkTargets = new Object[linkTargets.length];
        for(int i = 0; i < linkTargets.length; i++) p.linkTargets[i] = LogicState.copy(linkTargets[i], remap);
        p.ipt = ipt;
        p.accumulator = accumulator;
        p.team = team;
        p.tag = tag;
        p.iconTag = iconTag;
        p.code = code;
        for(Link link : links){
            var copy = new Link(link.x, link.y, link.name, link.valid);
            copy.lastBuild = LogicState.copy(link.lastBuild, remap);
            copy.variable = link.variable;
            copy.generation = link.generation;
            p.links.add(copy);
        }
        p.checkedDuplicates = checkedDuplicates;
        p.linksDirty = linksDirty;
        if(linkMap != null) p.buildLinkMap(linkMapNames);
        p.parkedAt = parkedAt;
        p.parkCalls = parkCalls;
        p.parkWake = parkWake;
        p.parkDelta = parkDelta;
        p.parkWorldDelta = parkWorldDelta;
        p.executed = executed;
        p.weightExtra = weightExtra;
        if(instructionState != null){
            p.instructionState = new Object[instructionState.length];
            for(int i = 0; i < instructionState.length; i++) p.instructionState[i] = LogicState.copy(instructionState[i], remap);
        }
        p.syncedAt = syncedAt == null ? null : syncedAt.clone();
        p.hostData = LogicState.copy(hostData, remap);
        p.watch();
        return p;
    }

    //endregion

    /** {@code LogicBlock.LogicLink}. */
    public static final class Link{
        public final int x, y;
        String name;
        boolean valid;
        Object lastBuild;
        /** {@code LogicLink.logicVar}, as an export index of program {@link #generation}, or -1 until looked up. */
        int variable = -1;
        int generation;

        Link(int x, int y, String name, boolean valid){
            this.x = x;
            this.y = y;
            this.name = name;
            this.valid = valid;
        }

        public String name(){
            return name;
        }

        public boolean valid(){
            return valid;
        }

        @Override
        public String toString(){
            return name + "@" + x + "," + y + (valid ? "" : " (invalid)");
        }
    }
}
