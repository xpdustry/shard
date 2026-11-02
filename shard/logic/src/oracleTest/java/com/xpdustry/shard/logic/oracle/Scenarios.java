package com.xpdustry.shard.logic.oracle;

import mindustry.*;
import mindustry.content.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import java.util.*;

/**
 * The prototype's benchmark scenarios (note 28 §3), one world region per processor:
 * <ul>
 * <li>{@code math}, {@code arith}: world processors at ipt 1000 running a float-heavy or an integer loop;</li>
 * <li>{@code sorter}: logic processors (ipt 8), each linked to 4 vaults, 2 sorters, a cell and a message;</li>
 * <li>{@code display}: logic processors linked to a display and a cell, drawing bars;</li>
 * <li>{@code wait}: micro processors (ipt 2) with 8 cells each, in a {@code wait 0.5} loop.</li>
 * </ul>
 */
final class Scenarios{
    static final List<String> names = List.of("math", "arith", "sorter", "display", "wait");

    private Scenarios(){
    }

    record Scenario(List<LogicBuild> processors, String code){
    }

    static Scenario create(String name, int count){
        int side = (int)Math.ceil(Math.sqrt(count));
        int cell = 16;
        int size = Math.max(50, side * cell + 10);
        VanillaLogic.world(size, size);
        List<LogicBuild> processors = new ArrayList<>();
        String code = VanillaLogic.program(name);
        for(int i = 0; i < count; i++){
            int ox = 4 + (i % side) * cell, oy = 4 + (i / side) * cell;
            LogicBuild lb;
            switch(name){
                case "math", "arith" -> {
                    lb = VanillaLogic.place(Blocks.worldProcessor, ox, oy);
                    lb.ipt = 1000;
                }
                case "sorter" -> {
                    lb = VanillaLogic.place(Blocks.logicProcessor, ox, oy);
                    for(int v = 0; v < 4; v++){
                        VanillaLogic.link(lb, Blocks.vault, ox + 3 + v * 3, oy + 4, "vault" + (v + 1));
                        var vault = Vars.world.build(ox + 3 + v * 3, oy + 4);
                        vault.items.add(Items.copper, 100 + 250 * v);
                        vault.items.add(Items.lead, 50 * v);
                    }
                    VanillaLogic.link(lb, Blocks.sorter, ox, oy + 7, "sorter1");
                    VanillaLogic.link(lb, Blocks.sorter, ox + 1, oy + 7, "sorter2");
                    VanillaLogic.link(lb, Blocks.memoryCell, ox + 2, oy + 7, "cell1");
                    VanillaLogic.link(lb, Blocks.message, ox + 3, oy + 7, "message1");
                }
                case "display" -> {
                    lb = VanillaLogic.place(Blocks.logicProcessor, ox, oy);
                    VanillaLogic.link(lb, Blocks.logicDisplay, ox + 4, oy + 4, "display1");
                    VanillaLogic.link(lb, Blocks.memoryCell, ox, oy + 4, "cell1");
                }
                case "wait" -> {
                    lb = VanillaLogic.place(Blocks.microProcessor, ox, oy);
                    for(int v = 0; v < 8; v++) VanillaLogic.link(lb, Blocks.memoryCell, ox + 2 + v, oy, "cell" + (v + 1));
                }
                default -> throw new IllegalArgumentException(name);
            }
            lb.updateCode(code);
            processors.add(lb);
        }
        return new Scenario(processors, code);
    }
}
