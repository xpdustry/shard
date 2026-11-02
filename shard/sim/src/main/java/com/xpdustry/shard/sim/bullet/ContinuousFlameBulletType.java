package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;

/**
 * {@code ContinuousFlameBulletType}: a continuous flame whose length follows {@code lengthInterp} over its life. Only
 * vanilla's default, {@code Interp.slope}, is ported; another curve fails at load.
 */
@BulletKind("continuous-flame-bullet-type")
public final class ContinuousFlameBulletType extends ContinuousBulletType{
    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        String interp = def.lengthInterp();
        if(interp != null && !interp.equals("interp:slope")){
            throw new IllegalArgumentException(def.name() + ": lengthInterp " + interp + " isn't ported");
        }
    }

    /** {@code length * b.fin(Interp.slope)}. */
    @Override
    public float currentLength(Bullet b){
        float fin = b.fin();
        return length * (1f - Math.abs(fin - 0.5f) * 2f);
    }
}
