package com.xpdustry.shard.logic;

import java.util.*;

/**
 * An assembled mlog program: a flat {@code int[]} of fixed-width records, a per-instruction side table and a slot
 * table. Immutable, and independent of any processor: link targets, {@code @this}, {@code @thisx/y} and {@code @ipt}
 * are filled in per {@link Processor}, so every processor with the same code, link names and privilege shares one
 * program ({@link ProgramCache}).
 *
 * <p>Slots are vanilla's {@code LAssembler.vars} in creation order, plus one constant slot per global the code names.
 * Slot 0 is {@code @counter}, 1 is {@code @unit}, 2 is {@code @this}. {@link #exportOrder} is vanilla's
 * {@code LExecutor.vars}: its indices are the {@code syncVariable} ids and the save's variable order.
 */
public final class Program{
    public static final int STRIDE = 8;

    // opcodes; a record is [opcode, operands...], with slot operands unless noted
    public static final int
    NOOP = 0, SET = 1, SELECT = 2, END = 3, WAIT = 4, STOP = 5, PRINT = 6, PRINTCHAR = 7, FORMAT = 8,
    PRINTFLUSH = 9, DRAW = 10, DRAWFLUSH = 11, GETLINK = 12, READ = 13, WRITE = 14, SENSOR = 15, CONTROL = 16,
    SETRATE = 17, PACKCOLOR = 18, UNPACKCOLOR = 19, LOOKUP = 20,
    // everything the world answers: aux holds a HostOp
    HOST = 21,
    JUMP = 24, // + ConditionOp ordinal (8)
    OP = 32;   // + LogicOp ordinal (45)

    /** Slot kinds. */
    public static final byte USER = 0, LITERAL = 1, GLOBAL = 2, DYN_GLOBAL = 3;

    /** The fast loop's marker for instructions that need the exact {@code @counter} path. */
    static final int SLOW = 127;

    /** What it was assembled with: a checkpoint reassembles it from the code and these. */
    public final Assembler.Options options;
    /**
     * The empty program that replaces code that failed to parse: vanilla's {@code updateCode} catch, which also sets
     * the processor's code to {@code ""} and leaves its links alone.
     */
    public final boolean malformed;
    public final int length;
    final int[] code;
    /** Per instruction: the {@code LAccess} of a {@code control}, the {@link HostOp} of a host call, else null. */
    final Object[] aux;
    /** Opcodes for the fast loop, with {@link #SLOW} for anything that reads or writes {@code @counter}. */
    final int[] dispatch;
    public final boolean privileged;

    final String[] slotNames;
    final byte[] slotKind;
    final double[] initNum;
    final Object[] initObj;
    final boolean[] initConst;
    /** Slots of dynamic globals and their ids in {@link LogicWorld#globals()}. */
    final int[] dynSlots, dynIds;
    final int[] exportOrder;
    /** Instruction indices of the {@code wait}s, ascending (their timers are saved). */
    final int[] waits;
    /** Names of the links that were valid at assembly, in link order, and their constant slots. */
    final String[] linkNames;
    final int[] linkSlots;
    final int linksSlot, iptSlot, thisxSlot, thisySlot, queriesSlot;

    private final Map<String, Integer> exportIndex = new HashMap<>(); // lookups only, never iterated
    private final Map<String, Integer> namedSlots = new HashMap<>(); // lookups only, never iterated
    /** Per slot, its export index, or -1. */
    private final int[] slotExport;

    Program(Assembler.Options options, boolean malformed, int length, int[] code, Object[] aux, boolean[] touchesCounter, boolean privileged, String[] slotNames,
            byte[] slotKind, double[] initNum, Object[] initObj, boolean[] initConst, int[] dynSlots, int[] dynIds,
            int[] exportOrder, String[] linkNames, int[] linkSlots, int linksSlot, int iptSlot, int thisxSlot,
            int thisySlot, int queriesSlot){
        this.options = options;
        this.malformed = malformed;
        this.length = length;
        this.code = code;
        this.aux = aux;
        this.privileged = privileged;
        this.slotNames = slotNames;
        this.slotKind = slotKind;
        this.initNum = initNum;
        this.initObj = initObj;
        this.initConst = initConst;
        this.dynSlots = dynSlots;
        this.dynIds = dynIds;
        this.exportOrder = exportOrder;
        this.linkNames = linkNames;
        this.linkSlots = linkSlots;
        this.linksSlot = linksSlot;
        this.iptSlot = iptSlot;
        this.thisxSlot = thisxSlot;
        this.thisySlot = thisySlot;
        this.queriesSlot = queriesSlot;

        dispatch = new int[length];
        int waitCount = 0;
        for(int i = 0; i < length; i++){
            int op = code[i * STRIDE];
            dispatch[i] = touchesCounter[i] || op == WAIT || op == STOP ? SLOW : op;
            if(op == WAIT) waitCount++;
        }
        waits = new int[waitCount];
        for(int i = 0, w = 0; i < length; i++){
            if(code[i * STRIDE] == WAIT) waits[w++] = i;
        }
        slotExport = new int[slotNames.length];
        Arrays.fill(slotExport, -1);
        for(int i = 0; i < exportOrder.length; i++){
            exportIndex.putIfAbsent(slotNames[exportOrder[i]], i);
            slotExport[exportOrder[i]] = i;
        }
        for(int i = 0; i < slotNames.length; i++){
            if(slotKind[i] == USER || slotKind[i] == LITERAL) namedSlots.put(slotNames[i], i);
        }
    }

    /** The number of slots. */
    public int slots(){
        return slotNames.length;
    }

    /** The number of exported variables ({@code LExecutor.vars.length}). */
    public int variables(){
        return exportOrder.length;
    }

    /** The name of exported variable {@code index}. */
    public String variableName(int index){
        return slotNames[exportOrder[index]];
    }

    /** The slot of exported variable {@code index}. */
    public int variableSlot(int index){
        return exportOrder[index];
    }

    /** {@code LExecutor.optionalVar(name)}: the export index of a variable or link, or -1. */
    public int variable(String name){
        Integer index = exportIndex.get(name);
        return index == null ? -1 : index;
    }

    /** The export index of slot {@code slot}, or -1 for literals, globals and constants not exported. */
    public int varIndex(int slot){
        return slotExport[slot];
    }

    /** {@code LAssembler.getVar(name)}: the slot of a variable, link or literal (not a global), or -1. */
    public int namedSlot(String name){
        Integer slot = namedSlots.get(name);
        return slot == null ? -1 : slot;
    }

    /** The opcode of instruction {@code index}. */
    public int opcode(int index){
        return code[index * STRIDE];
    }

    /** Whether the program has {@code @queries}: a privileged one ({@code LExecutor.queryResult}). */
    public boolean hasQueries(){
        return queriesSlot >= 0;
    }

    /** The host call of instruction {@code index}, or null if it is not one. */
    public HostOp hostOp(int index){
        return aux[index] instanceof HostOp op ? op : null;
    }

    /** The instruction indices of this program's {@code wait}s. */
    public int[] waits(){
        return waits.clone();
    }
}
