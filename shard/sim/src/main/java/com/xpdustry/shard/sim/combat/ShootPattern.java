package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ShootPattern} family ({@code entities/pattern/*}): how one trigger pull turns into bullets, each
 * with an offset, a rotation offset, a delay and maybe a {@link Mover}. The pattern is data, read from the weapon's
 * or turret's {@code shoot} struct by kind ({@link #of}); mirrored weapons hold it already flipped. Immutable.
 *
 * <p>{@code ShootSine} is ported but no vanilla content uses it, so the pack has no kind for it.
 */
public class ShootPattern{
    /** Shots per trigger pull. */
    public final int shots;
    /** Ticks before the first shot, and between shots. */
    public final float firstShotDelay, shotDelay;

    ShootPattern(Struct fields){
        shots = fields.i("shots");
        firstShotDelay = fields.f("firstShotDelay");
        shotDelay = fields.f("shotDelay");
    }

    ShootPattern(int shots, float firstShotDelay, float shotDelay){
        this.shots = shots;
        this.firstShotDelay = firstShotDelay;
        this.shotDelay = shotDelay;
    }

    /** The pattern {@code fields} describe, by kind; an unknown kind shoots as the base pattern. */
    public static ShootPattern of(Struct fields){
        if(fields == null) return new ShootPattern(1, 0f, 0f);
        return switch(fields.kind().key()){
            case "shoot-alternate" -> new Alternate(fields);
            case "shoot-barrel" -> new Barrel(fields);
            case "shoot-helix" -> new Helix(fields);
            case "shoot-multi" -> new Multi(fields);
            case "shoot-spread" -> new Spread(fields);
            case "shoot-summon" -> new Summon(fields);
            default -> new ShootPattern(fields);
        };
    }

    /**
     * {@code shoot(totalShots, handler, barrelIncrementer)}: calls {@code handler} once per bullet, and
     * {@code barrelIncrementer} (if not null) after each bullet of the patterns that count barrels. The base pattern
     * shoots {@code shots} bullets straight ahead, {@code shotDelay} apart.
     */
    public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
        for(int i = 0; i < shots; i++){
            handler.shoot(0, 0, 0, firstShotDelay + shotDelay * i, null);
        }
    }

    /** {@code BulletHandler}: one bullet, at an offset from the weapon, turned by {@code rotation}, after {@code delay}. */
    @FunctionalInterface
    public interface Handler{
        void shoot(float x, float y, float rotation, float delay, Mover mover);
    }

    /** {@code ShootAlternate}: alternates between {@code barrels} side by side, {@code spread} world units apart. */
    public static final class Alternate extends ShootPattern{
        public final int barrels, barrelOffset;
        public final float spread;
        public final boolean mirror;

        Alternate(Struct fields){
            super(fields);
            barrels = fields.i("barrels");
            barrelOffset = fields.i("barrelOffset");
            spread = fields.f("spread");
            mirror = fields.b("mirror");
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                float index = ((totalShots + i + barrelOffset) % barrels) - (barrels - 1) / 2f;
                handler.shoot(index * spread * -Mathf.sign(mirror), 0, 0f, firstShotDelay + shotDelay * i, null);
                if(barrelIncrementer != null) barrelIncrementer.run();
            }
        }
    }

    /** {@code ShootBarrel}: cycles through barrels given as (x, y, rotation) triples. */
    public static final class Barrel extends ShootPattern{
        private final float[] barrels;
        public final int barrelOffset;

        Barrel(Struct fields){
            super(fields);
            List<Object> values = fields.list("barrels");
            barrels = new float[values.size()];
            for(int i = 0; i < barrels.length; i++) barrels[i] = ((Number)values.get(i)).floatValue();
            barrelOffset = fields.i("barrelOffset");
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                int index = ((i + totalShots + barrelOffset) % (barrels.length / 3)) * 3;
                handler.shoot(barrels[index], barrels[index + 1], barrels[index + 2], firstShotDelay + shotDelay * i, null);
                if(barrelIncrementer != null) barrelIncrementer.run();
            }
        }
    }

    /** {@code ShootHelix}: two bullets per shot, weaving in a helix around the line of fire. */
    public static final class Helix extends ShootPattern{
        public final float scl, mag, offset;

        Helix(Struct fields){
            super(fields);
            scl = fields.f("scl");
            mag = fields.f("mag");
            offset = fields.f("offset");
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                for(int sign : Mathf.signs){
                    handler.shoot(0, 0, 0, firstShotDelay + shotDelay * i, new HelixMover(offset, scl, mag * sign));
                }
            }
        }
    }

    /** {@code ShootHelix}'s mover: {@code b.moveRelative(0f, Mathf.sin(b.time + offset, scl, mag))}. */
    public record HelixMover(float offset, float scl, float mag) implements Mover{
        @Override
        public void move(Bullet b){
            float rot = b.rotation();
            float y = Mathf.sin(b.time() + offset, scl, mag);
            b.trns(Angles.trnsx(rot, 0f, y), Angles.trnsy(rot, 0f, y));
        }

        @Override
        public void hash(Hasher hasher){
            hasher.f(offset).f(scl).f(mag);
        }
    }

    /** {@code ShootMulti}'s combined mover: the source's, then the destination's. */
    public record BothMovers(Mover first, Mover second) implements Mover{
        @Override
        public void move(Bullet b){
            if(first != null) first.move(b);
            if(second != null) second.move(b);
        }

        @Override
        public void hash(Hasher hasher){
            hasher.bool(first != null);
            if(first != null) first.hash(hasher);
            hasher.bool(second != null);
            if(second != null) second.hash(hasher);
        }
    }

    /** {@code ShootMulti}: every shot of {@code source} fires every pattern of {@code dest} from it. */
    public static final class Multi extends ShootPattern{
        public final ShootPattern source;
        public final List<ShootPattern> dest;

        Multi(Struct fields){
            super(fields);
            source = ShootPattern.of(fields.struct("source"));
            var patterns = new ArrayList<ShootPattern>();
            for(Struct pattern : fields.structs("dest")) patterns.add(ShootPattern.of(pattern));
            dest = List.copyOf(patterns);
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            source.shoot(rand, totalShots, (x, y, rotation, delay, move) -> {
                for(ShootPattern pattern : dest){
                    pattern.shoot(rand, totalShots, (x2, y2, rot2, delay2, mover) -> handler.shoot(x + x2, y + y2, rotation + rot2, delay + delay2,
                        move == null && mover == null ? null : new BothMovers(move, mover)), null);
                }
            }, barrelIncrementer);
        }
    }

    /** {@code ShootSpread}: {@code shots} bullets fanned {@code spread} degrees apart. */
    public static final class Spread extends ShootPattern{
        public final float spread;

        Spread(Struct fields){
            super(fields);
            spread = fields.f("spread");
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                float angleOffset = i * spread - (shots - 1) * spread / 2f;
                handler.shoot(0, 0, angleOffset, firstShotDelay + shotDelay * i, null);
            }
        }
    }

    /** {@code ShootSummon}: bullets from random points in a circle around (x, y), randomly turned. */
    public static final class Summon extends ShootPattern{
        public final float x, y, radius, spread;

        Summon(Struct fields){
            super(fields);
            x = fields.f("x");
            y = fields.f("y");
            radius = fields.f("radius");
            spread = fields.f("spread");
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                // Tmp.v1.trns(Mathf.random(360f), Mathf.random(radius))
                float angle = Mathf.random(rand, 360f);
                var offset = new Vec2().trns(angle, Mathf.random(rand, radius));
                handler.shoot(x + offset.x, y + offset.y, Mathf.range(rand, spread), firstShotDelay + shotDelay * i, null);
            }
        }
    }

    /** {@code ShootSine}: each shot turned along a sine of its index. */
    public static final class Sine extends ShootPattern{
        public final float scl, mag;

        public Sine(int shots, float firstShotDelay, float shotDelay, float scl, float mag){
            super(shots, firstShotDelay, shotDelay);
            this.scl = scl;
            this.mag = mag;
        }

        @Override
        public void shoot(Rand rand, int totalShots, Handler handler, Runnable barrelIncrementer){
            for(int i = 0; i < shots; i++){
                float angleOffset = Mathf.sin(i + totalShots, scl, mag);
                handler.shoot(0, 0, angleOffset, firstShotDelay + shotDelay * i, null);
            }
        }
    }
}
