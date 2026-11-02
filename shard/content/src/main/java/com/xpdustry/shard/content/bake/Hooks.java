package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.content.yaml.*;

/**
 * The hooks the bake evaluates: consumer filters, which {@code Consume.apply} runs over every item or liquid to fill
 * a block's filter. A filter is either its consumer kind's own lambda (the kind default) or one a block kind
 * installs ({@code item-turret.filter}); anything else can't be baked.
 */
final class Hooks{
    private Hooks(){
    }

    static boolean itemFilter(Bake bake, Mut block, Mut consume, Mut item){
        String hook = consume.s("filter");
        if(isDefault(consume, "filter")){
            if(consume.is("consume-item-flammable")) return item.f("flammability") >= consume.f("minFlammability");
            if(consume.is("consume-item-radioactive")) return item.f("radioactivity") >= consume.f("minRadioactivity");
            if(consume.is("consume-item-explosive")) return item.f("explosiveness") >= consume.f("minExplosiveness");
            if(consume.is("consume-item-charged")) return item.f("charge") >= consume.f("minCharge");
            if(consume.is("consume-item-explode")) return item.f("explosiveness") >= consume.f("threshold");
            // ConsumeItemFilter.filter starts as i -> false
            if(consume.kind.key().equals("consume-item-filter") || consume.kind.key().equals("consume-item-efficiency")) return false;
        }
        if("hook:item-turret.filter".equals(hook)) return block.base.map("ammoTypes").containsKey(bake.name(item));
        return unknown(bake, block, hook);
    }

    static boolean liquidFilter(Bake bake, Mut block, Mut consume, Mut liquid){
        String hook = consume.s("filter");
        if(isDefault(consume, "filter")){
            if(consume.is("consume-coolant")){
                boolean gas = liquid.b("gas");
                return liquid.b("coolant") && (consume.b("allowLiquid") && !gas || consume.b("allowGas") && gas)
                    && liquid.f("temperature") <= consume.f("maxTemp") && liquid.f("flammability") < consume.f("maxFlammability");
            }
            if(consume.is("consume-liquid-flammable")) return liquid.f("flammability") >= consume.f("minFlammability");
            // ConsumeLiquidFilter.filter starts as l -> false
            if(consume.kind.key().equals("consume-liquid-filter")) return false;
        }
        if("hook:liquid-turret.filter".equals(hook) || "hook:continuous-liquid-turret.filter".equals(hook)){
            return block.base.map("ammoTypes").containsKey(bake.name(liquid));
        }
        return unknown(bake, block, hook);
    }

    /** Whether {@code field} holds its kind's default, the lambda the kind's constructor installs. */
    private static boolean isDefault(Mut m, String field){
        return java.util.Objects.equals(m.get(field), m.kind.defaults()[m.kind.field(field).slot()]);
    }

    private static boolean unknown(Bake bake, Mut block, String hook){
        bake.diagnostics.error(Mark.none, "block " + bake.name(block) + ": the bake can't evaluate filter " + hook);
        return false;
    }
}
