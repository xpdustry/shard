package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.sim.logic.*;

/**
 * Vanilla's {@code LogicFilter}: {@code logicVars.update()}, then {@code code} runs once as a privileged world
 * processor with no building, for at most {@link #MAX_INSTRUCTIONS} instructions ({@code LExecutor.runLogicScript}),
 * on the loading world's globals and logic generator. Its statements see the map as it loads and the file's own rules,
 * which hosting replaces afterwards. A filter without code does nothing. A script that reaches a statement Shard
 * doesn't run yet ({@link WorldHost}) is refused, so a hosted map never differs from vanilla's silently.
 */
public record LogicFilter(String code, boolean loop) implements GenerateFilter{
    /** {@code maxInstructionsExecution}. */
    public static final int MAX_INSTRUCTIONS = 500 * 500 * 25;

    @Override
    public boolean post(){
        return true;
    }

    @Override
    public void apply(FilterGrid grid, GenerateInput in){
        if(code == null || code.isEmpty()) return;
        LogicPart logic = LogicPart.of(grid.world);
        if(logic == null) throw new MsavException("the map's generation filters run a logic script, and the world has no LogicPart");
        logic.refreshGlobals(grid.world);
        try{
            logic.runScript(code, MAX_INSTRUCTIONS, loop, grid.rules);
        }catch(UnsupportedOperationException e){
            throw new MsavException("the map's generation filters run a logic script that Shard cannot run yet: " + e.getMessage(), e);
        }
    }
}
