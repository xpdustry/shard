package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.rules.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** The content ids {@code sim.rules} hard-codes for the rule defaults are vanilla v160.7's (teams: RulesJsonOracleTest). */
class VanillaIdsTest{
    static final Epoch CONTENT = Worlds.EPOCH;

    @Test
    void contentIdsAreVanillas(){
        assertEquals(CONTENT.id(ContentType.ITEM, "copper"), VanillaIds.COPPER);
        assertEquals(CONTENT.id(ContentType.UNIT, "dagger"), VanillaIds.DAGGER);
        assertEquals(CONTENT.id(ContentType.STATUS, "none"), VanillaIds.STATUS_NONE);
        assertEquals(CONTENT.id(ContentType.STATUS, "boss"), VanillaIds.STATUS_BOSS);
        assertEquals(CONTENT.id(ContentType.WEATHER, "rain"), VanillaIds.RAIN);
        assertEquals(CONTENT.planets().indexOf("erekir"), VanillaIds.EREKIR);
        assertEquals(CONTENT.planets().indexOf("serpulo"), VanillaIds.SERPULO);
    }

}
