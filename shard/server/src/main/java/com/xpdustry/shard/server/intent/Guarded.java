package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.sim.world.*;

/**
 * An intent whose admission can't fail its world. Every input is checked as vanilla checks it, so an exception here
 * is a Shard bug: it is logged with its stack and the intent rejected, where vanilla's server logs a handler's
 * failure and carries on, instead of a client's packet stopping the world.
 */
interface Guarded extends Intent{
    System.Logger LOG = System.getLogger(Guarded.class.getName());

    /** The admission itself. */
    Verdict admit(World world, IntentEnvelope envelope);

    @Override
    default Verdict apply(World world, IntentEnvelope envelope){
        try{
            return admit(world, envelope);
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.ERROR, "an intent failed: " + this, e);
            return Verdict.rejected("failed: " + e);
        }
    }
}
