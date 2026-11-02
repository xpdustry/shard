package com.xpdustry.shard.logic.oracle;

import com.xpdustry.shard.logic.lang.*;
import mindustry.content.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.logic.MemoryBlock.*;
import mindustry.world.blocks.logic.MessageBlock.*;

import java.util.*;

/**
 * The differential fuzzer from note 28, extended to the whole VM: random programs run on vanilla's {@code LExecutor}
 * inside a real {@code LogicBuild} and on a Shard {@link com.xpdustry.shard.logic.Processor}, in two copies of a
 * world region with a memory cell and a message linked. Half the programs are compared after every tick; the other
 * half only every few dozen ticks, so waiting processors park in between.
 */
final class MlogFuzz{
    static final String[] VARS = {"a", "b", "c", "d", "e", "f"};
    static final String[] LITS = {"0", "1", "-1", "2", "7", "0.5", "-0.25", "3.14159", "1e3", "1e-7", "123456789", "1e308",
    "-1e308", "0x1F", "-0x10", "0b101", "%ff8800", "%ff880080", ".5", "5.", "+3", "1.5e2", "2E-3", "0.1", "0.30000000000000004",
    "9007199254740993", "-0", "\"abc\"", "\"\"", "\"x\\ny\"", "\"{0} and {1}\"", "null", "true", "false", "@pi", "@e", "@counter",
    "@ipt", "@links", "@this", "@thisx", "@thisy", "@time", "@tick", "@second", "@copper", "@sharded", "@unit", "%[red]",
    "@colorRed", "@health", "@memory-cell", "@dagger", "@water", "@center", "cell1", "message1", "@status-wet"};
    static final String[] SENSES = {"@type", "@size", "@enabled", "@health", "@maxHealth", "@team", "@memoryCapacity",
    "@bufferSize", "@copper", "@totalItems", "@dead", "@config", "@name", "@controlled", "@operations"};
    static final String[] LOOKUPS = {"block", "unit", "item", "liquid", "team"};

    final Pair logic, world;
    final Random random;
    int failures, ticks;
    final List<String> reports = new ArrayList<>();

    /** A 40x60 world with a logic and a world processor per region, each linked to a cell and a message. */
    MlogFuzz(long seed){
        random = new Random(seed);
        VanillaLogic.world(40, 60);
        VanillaHost host = new VanillaHost();
        var shardWorld = VanillaLogic.logicWorld(host);
        logic = new Pair(region(Blocks.logicProcessor, 5, 5), region(Blocks.logicProcessor, 5, 35), shardWorld, host);
        world = new Pair(region(Blocks.worldProcessor, 5, 15), region(Blocks.worldProcessor, 5, 45), shardWorld, host);
    }

    private static LogicBuild region(mindustry.world.Block block, int x, int y){
        LogicBuild build = VanillaLogic.place(block, x, y);
        VanillaLogic.link(build, Blocks.memoryCell, x + 2, y, "cell1");
        VanillaLogic.link(build, Blocks.message, x + 3, y, "message1");
        build.updateCode("");
        return build;
    }

    /** Runs {@code programs} random programs; failures are counted and the first ten reported. */
    void run(int programs){
        for(int i = 0; i < programs; i++){
            boolean privileged = random.nextInt(4) == 0;
            Pair pair = privileged ? world : logic;
            String code = generate();
            boolean sparse = random.nextBoolean();
            int length = sparse ? 120 + random.nextInt(240) : 30 + random.nextInt(30);
            reset(pair);
            pair.load(code);
            String bad = pair.compare();
            for(int t = 0; t < length && bad == null; t++){
                pair.tick();
                ticks++;
                if(!sparse || t == length - 1 || random.nextInt(40) == 0){
                    bad = pair.compare();
                    if(bad != null) bad = "tick " + t + ": " + bad;
                }
            }
            if(bad != null){
                failures++;
                if(reports.size() < 10){
                    reports.add((privileged ? "world" : "logic") + " processor" + (sparse ? ", sparse" : "") + ": " + bad + "\n---\n" + code + "---");
                }
            }
        }
    }

    /** Clears the linked buildings, so each program starts from the same world. */
    private static void reset(Pair pair){
        for(LogicBuild build : new LogicBuild[]{pair.vanilla, pair.shard}){
            for(LogicLink link : build.links){
                var target = mindustry.Vars.world.build(link.x, link.y);
                if(target instanceof MemoryBuild cell){
                    mindustry.logic.LVar position = new mindustry.logic.LVar("p"), zero = new mindustry.logic.LVar("z");
                    for(int i = 0; i < ((MemoryBlock)cell.block).memoryCapacity; i++){
                        position.numval = i;
                        cell.write(position, zero);
                    }
                }
                if(target instanceof MessageBuild message) message.message.setLength(0);
                if(target != null) target.enabled = true;
            }
            build.enabled = true;
        }
    }

    String operand(){
        return random.nextInt(3) == 0 ? LITS[random.nextInt(LITS.length)] : VARS[random.nextInt(VARS.length)];
    }

    String dest(){
        int k = random.nextInt(40);
        if(k == 0) return "@counter";
        if(k == 1) return LITS[random.nextInt(LITS.length)]; // writes to constants must be ignored
        return VARS[random.nextInt(VARS.length)];
    }

    String address(){
        return random.nextInt(3) == 0 ? String.valueOf(random.nextInt(70) - 3) : operand();
    }

    String generate(){
        Random r = random;
        int n = 3 + r.nextInt(40);
        StringBuilder sb = new StringBuilder();
        int labels = 0;
        for(int i = 0; i < n; i++){
            if(r.nextInt(12) == 0) sb.append("l").append(labels++).append(":\n");
            int k = r.nextInt(120);
            if(k < 34){
                sb.append("op ").append(r.nextInt(30) == 0 ? (r.nextBoolean() ? "atan2" : "dst") : LogicOp.values()[r.nextInt(LogicOp.values().length)].name()).append(' ').append(dest()).append(' ').append(operand()).append(' ').append(operand());
            }else if(k < 46){
                sb.append("set ").append(dest()).append(' ').append(operand());
            }else if(k < 58){
                String target = labels > 0 && r.nextBoolean() ? "l" + r.nextInt(labels) : String.valueOf(r.nextInt(n + 3) - (r.nextInt(10) == 0 ? 2 : 0));
                sb.append("jump ").append(target).append(' ').append(ConditionOp.values()[r.nextInt(ConditionOp.values().length)].name()).append(' ').append(operand()).append(' ').append(operand());
            }else if(k < 62){
                sb.append("select ").append(dest()).append(' ').append(ConditionOp.values()[r.nextInt(ConditionOp.values().length)].name());
                for(int j = 0; j < 4; j++) sb.append(' ').append(operand());
            }else if(k < 69){
                sb.append("print ").append(operand());
            }else if(k < 71){
                sb.append("format ").append(operand());
            }else if(k < 73){
                sb.append("printchar ").append(operand());
            }else if(k < 75){
                sb.append("packcolor ").append(dest());
                for(int j = 0; j < 4; j++) sb.append(' ').append(operand());
            }else if(k < 76){
                sb.append("unpackcolor ").append(dest()).append(' ').append(dest()).append(' ').append(dest()).append(' ').append(dest()).append(' ').append(operand());
            }else if(k < 79){
                sb.append("wait ").append(switch(r.nextInt(5)){
                    case 0 -> "0";
                    case 1 -> "0.05";
                    case 2 -> "0.5";
                    case 3 -> "1.5";
                    default -> operand();
                });
            }else if(k < 80){
                sb.append(r.nextInt(4) == 0 ? "stop" : "end");
            }else if(k < 81){
                sb.append("setrate ").append(operand());
            }else if(k < 83){
                sb.append("printflush ").append(r.nextBoolean() ? "message1" : operand());
            }else if(k < 87){
                // computed jumps and fractional counters
                sb.append(switch(r.nextInt(5)){
                    case 0 -> "op add @counter @counter " + (1 + r.nextInt(3));
                    case 1 -> "set @counter " + r.nextInt(n + 2) + (r.nextBoolean() ? ".5" : "");
                    case 2 -> "set @counter \"s\"";
                    case 3 -> "op div @counter 1 0";
                    default -> "op add " + VARS[r.nextInt(VARS.length)] + " @counter 0";
                });
            }else if(k < 88){
                sb.append("draw rect ").append(operand()).append(" 0 4 4 0 0");
            }else if(k < 89){
                sb.append(r.nextBoolean() ? "noop" : "bogus " + operand());
            }else if(k < 90){
                sb.append("op rand ").append(dest()).append(' ').append(operand()).append(" 0");
            }else if(k < 94){
                sb.append("sensor ").append(dest()).append(' ').append(switch(r.nextInt(4)){
                    case 0 -> "cell1";
                    case 1 -> "message1";
                    case 2 -> "\"abcd\"";
                    default -> operand();
                }).append(' ').append(r.nextInt(4) == 0 ? operand() : SENSES[r.nextInt(SENSES.length)]);
            }else if(k < 99){
                sb.append("read ").append(dest()).append(' ').append(r.nextInt(5) == 0 ? "\"hello\"" : "cell1").append(' ').append(address());
            }else if(k < 104){
                sb.append("write ").append(operand()).append(" cell1 ").append(address());
            }else if(k < 108){
                // another processor's variables by name: here, this processor's own, @counter included
                String name = switch(r.nextInt(4)){
                    case 0 -> "\"@counter\"";
                    case 1 -> "\"cell1\"";
                    case 2 -> String.valueOf(r.nextInt(3));
                    default -> "\"" + VARS[r.nextInt(VARS.length)] + "\"";
                };
                if(r.nextBoolean()) sb.append("read ").append(dest()).append(" @this ").append(name);
                else sb.append("write ").append(r.nextBoolean() ? String.valueOf(r.nextInt(n + 2)) : operand()).append(" @this ").append(name);
            }else if(k < 110){
                sb.append("getlink ").append(dest()).append(' ').append(address());
            }else if(k < 113){
                sb.append("lookup ").append(LOOKUPS[r.nextInt(LOOKUPS.length)]).append(' ').append(dest()).append(' ').append(address());
            }else if(k < 115){
                sb.append("control enabled ").append(r.nextBoolean() ? "message1" : operand()).append(' ').append(operand()).append(" 0 0 0");
            }else{
                sb.append("op ").append(r.nextBoolean() ? "sin" : r.nextBoolean() ? "log" : "pow").append(' ').append(dest()).append(' ').append(operand()).append(' ').append(operand());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
