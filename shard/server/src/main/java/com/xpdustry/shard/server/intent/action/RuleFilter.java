package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.common.registry.*;

/** Enforces the world's {@link ActionRules}, after vanilla's rate limit, as a server owner's filter would run. */
@Register
public final class RuleFilter implements ActionFilter{
    @Override
    public int order(){
        return 100;
    }

    @Override
    public boolean allow(PlayerAction action, ActionContext context){
        ActionRules rules = ActionRules.of(context.world());
        if(rules == null) return true;
        ActionRules.Rule rule = rules.denying(action.type(), action.player().team, action.session());
        if(rule == null) return true;
        if(!rule.message().isEmpty()) context.message(rule.message());
        return false;
    }
}
