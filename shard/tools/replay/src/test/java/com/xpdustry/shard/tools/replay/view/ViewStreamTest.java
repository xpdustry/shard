package com.xpdustry.shard.tools.replay.view;

import com.xpdustry.shard.tools.replay.view.ViewSource.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The view stream round-trips: every frame {@link ViewReader} decodes equals, field by quantized field, what the
 * {@link ViewWriter} sampled from a synthetic world in which every kind of thing changes: units and bullets come,
 * go, turn and change speed; buildings are placed, damaged, aimed, rebuilt and removed; terrain changes; events fire.
 */
class ViewStreamTest{
    static final int W = 40, H = 30, TICKS = 2000;

    @Test
    void everyFrameDecodesToWhatWasSampled(){
        var world = new Synthetic(7);
        var writer = new ViewWriter(world, new ViewWriter.Options(3, 300, 6));
        var expected = new ArrayList<Expected>();
        for(int tick = 0; tick <= TICKS; tick++){
            world.tick = tick;
            world.step();
            // the first keyframe carries no events: they go with the next frame
            if(writer.sample()) expected.add(world.expected(!expected.isEmpty()));
        }
        byte[] file = writer.finish();
        var reader = new ViewReader(file);
        assertEquals(W, reader.width);
        assertEquals((TICKS + 299) / 300 + (TICKS % 300 == 0 ? 1 : 0), reader.segments());

        // a segment ends with a frame at the next keyframe's tick, and that keyframe repeats it without events
        var frames = new int[1];
        long[] previous = {-1};
        reader.playAll(state -> {
            boolean repeat = state.tick == previous[0];
            previous[0] = state.tick;
            Expected want = repeat ? expected.get(frames[0] - 1) : expected.get(frames[0]++);
            String at = "frame " + (frames[0] - 1) + " (tick " + state.tick + (repeat ? ", keyframe" : "") + ")";
            assertEquals(want.tick, state.tick, at);
            for(int i = 0; i < W * H; i++){
                assertEquals(want.env[i], reader.blocks.get(state.env[i]), at + " env " + i);
                assertEquals(want.floor[i], reader.blocks.get(state.floor[i]), at + " floor " + i);
            }
            assertEquals(want.builds.keySet(), state.builds.keySet(), at + " buildings");
            want.builds.forEach((tile, b) -> {
                var got = state.builds.get(tile);
                assertEquals(b.block(), reader.blocks.get(got.block()), at + " block at " + tile);
                assertEquals(b.team(), got.team(), at);
                assertEquals(b.rotation(), got.rotation(), at);
                assertEquals(ViewWriter.q8(b.health()), got.health(), at + " health at " + tile);
                assertEquals(b.flags(), got.flags(), at + " flags at " + tile);
                if((b.flags() & BuildView.AIM) != 0) assertEquals(ViewWriter.qrot(b.aim()), got.aim(), at + " aim at " + tile);
                if((b.flags() & BuildView.CONSTRUCT) != 0){
                    assertEquals(b.target(), reader.blocks.get(got.target() - 1), at);
                    assertEquals(ViewWriter.q8(b.progress()), got.progress(), at);
                }
            });
            assertEquals(want.units.keySet(), state.units.keySet(), at + " units");
            want.units.forEach((id, u) -> {
                var got = state.units.get(id);
                assertEquals(u.type(), reader.units.get(got.type), at);
                assertEquals(ViewWriter.qpos(u.x()), got.x, at + " unit " + id + " x");
                assertEquals(ViewWriter.qpos(u.y()), got.y, at + " unit " + id + " y");
                assertEquals(ViewWriter.qrot(u.rotation()), got.rotation, at + " unit " + id + " rotation");
                assertEquals(ViewWriter.q8(u.health()), got.health, at);
                assertEquals(ViewWriter.q8(u.elevation()), got.elevation, at);
                assertEquals(u.player(), (got.flags & ViewWriter.PLAYER) != 0 ? reader.players.get(got.player) : null, at);
            });
            assertEquals(want.bullets.keySet(), state.bullets.keySet(), at + " bullets");
            want.bullets.forEach((id, b) -> {
                var got = state.bullets.get(id);
                assertEquals(ViewWriter.qpos(b.x()), got.x, at + " bullet " + id + " x");
                assertEquals(ViewWriter.qpos(b.y()), got.y, at + " bullet " + id + " y");
                assertEquals(ViewWriter.qrot(b.rotation()), got.rotation, at);
                assertEquals(b.team(), got.team, at + " bullet " + id + " team");
            });
            assertEquals(repeat ? 0 : want.events, state.events.size(), at + " events");
            assertEquals(want.wave, state.wave, at);
        });
        assertEquals(expected.size(), frames[0], "frames");
        assertTrue(file.length < 300_000, "a 2000-tick synthetic match took " + file.length + " bytes");
    }

    @Test
    void namesAreTablesInTheHeader(){
        var world = new Synthetic(3);
        var writer = new ViewWriter(world, ViewWriter.Options.DEFAULT);
        for(int tick = 0; tick <= 120; tick++){
            world.tick = tick;
            world.step();
            writer.sample();
        }
        var reader = new ViewReader(writer.finish());
        assertEquals("air", reader.blocks.getFirst());
        assertTrue(reader.blocks.containsAll(List.of("stone", "sand-floor", "duo", "copper-wall")), reader.blocks.toString());
        assertTrue(reader.units.containsAll(List.of("dagger", "flare")), reader.units.toString());
        assertEquals("v160.7", reader.header.get("game").asString());
        assertEquals(2, reader.header.get("teams").size());
    }

    record Expected(long tick, String[] floor, String[] env, TreeMap<Integer, BuildView> builds, TreeMap<Integer, UnitView> units,
                    TreeMap<Integer, BulletView> bullets, int events, int wave){
    }

    /** A small world with a scripted, seeded life. */
    static final class Synthetic implements ViewSource{
        final Random random;
        long tick;
        final String[] blockNames = {"air", "stone", "sand-floor", "ore-copper", "stone-wall", "boulder", "duo", "copper-wall", "build1"};
        final short[] floor = new short[W * H], overlay = new short[W * H], env = new short[W * H];
        final TreeMap<Integer, BuildView> builds = new TreeMap<>();
        final TreeMap<Integer, UnitView> units = new TreeMap<>();
        final TreeMap<Integer, float[]> velocity = new TreeMap<>();
        final TreeMap<Integer, BulletView> bullets = new TreeMap<>();
        final List<ViewEvent> events = new ArrayList<>();
        int eventsSinceFrame, wave, nextId = 1;
        final BulletStyle style = new BulletStyle("basic", "bullet", 7f, 9f, 0xffffffff, 0xffd37fff, 0.5f, 60f, 0, 0f, 0, 4f);

        Synthetic(long seed){
            random = new Random(seed);
            for(int i = 0; i < W * H; i++){
                floor[i] = (short)(i % 7 == 0 ? 2 : 1);
                overlay[i] = (short)(i % 11 == 0 ? 3 : 0);
                env[i] = (short)(i % 13 == 0 ? 4 : i % 17 == 0 ? 5 : 0);
            }
        }

        void step(){
            // terrain: a boulder breaks now and then
            if(random.nextInt(50) == 0) env[random.nextInt(W * H)] = 0;
            // buildings
            if(random.nextInt(8) == 0){
                int tile = random.nextInt(W * H);
                boolean turret = random.nextBoolean();
                builds.put(tile, new BuildView(tile, turret ? "duo" : "copper-wall", 1 + random.nextInt(2), random.nextInt(4), 1f,
                    turret ? BuildView.AIM : 0, random.nextFloat() * 360f, null, 0f));
            }
            if(random.nextInt(10) == 0 && !builds.isEmpty()) builds.remove(builds.firstKey());
            if(random.nextInt(6) == 0){
                int tile = random.nextInt(W * H);
                builds.put(tile, new BuildView(tile, "build1", 1, 0, 0.5f, BuildView.CONSTRUCT, 0f, "duo", random.nextFloat()));
            }
            for(var entry : builds.entrySet()){
                BuildView b = entry.getValue();
                if(random.nextInt(5) == 0){
                    entry.setValue(new BuildView(b.tile(), b.block(), b.team(), b.rotation(), Math.max(0.01f, b.health() - random.nextFloat() * 0.1f),
                        b.flags() ^ (random.nextInt(4) == 0 ? BuildView.SHOOTING : 0), b.aim() + random.nextFloat() * 30f - 15f, b.target(),
                        Math.min(1f, b.progress() + 0.05f)));
                }
            }
            // units
            if(units.size() < 40 && random.nextInt(4) == 0){
                int id = nextId++;
                units.put(id, new UnitView(id, random.nextBoolean() ? "dagger" : "flare", 1 + random.nextInt(2), random.nextFloat() * W * 8,
                    random.nextFloat() * H * 8, random.nextFloat() * 360f, 1f, random.nextFloat(), 0, random.nextInt(5) == 0 ? "player" + id : null));
                velocity.put(id, new float[]{random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2});
            }
            if(random.nextInt(20) == 0 && !units.isEmpty()){
                int id = units.firstKey();
                UnitView dead = units.remove(id);
                events.add(new ViewEvent("unit-death", dead.x(), dead.y(), dead.rotation(), dead.team(), dead.type(), 8f));
            }
            for(var entry : units.entrySet()){
                UnitView u = entry.getValue();
                float[] v = velocity.get(entry.getKey());
                if(random.nextInt(30) == 0){
                    v[0] = random.nextFloat() * 4 - 2;
                    v[1] = random.nextFloat() * 4 - 2;
                }
                float rotation = random.nextInt(10) == 0 ? u.rotation() + random.nextFloat() * 90 - 45 : u.rotation();
                float health = random.nextInt(40) == 0 ? u.health() * 0.9f : u.health();
                entry.setValue(new UnitView(u.id(), u.type(), u.team(), u.x() + v[0], u.y() + v[1], rotation, health, u.elevation(),
                    random.nextInt(50) == 0 ? UnitView.SHOOTING : u.flags(), u.player()));
                if(random.nextInt(40) == 0){
                    int id = nextId++;
                    bullets.put(id, new BulletView(id, style, u.team(), u.x(), u.y(), rotation, 0f));
                }
            }
            // bullets fly straight and expire; now and then a wall deflects one, turning it and its team around
            var expired = new ArrayList<Integer>();
            for(var entry : bullets.entrySet()){
                BulletView b = entry.getValue();
                if(b.age() > 60) expired.add(entry.getKey());
                boolean deflected = random.nextInt(90) == 0;
                float rotation = deflected ? b.rotation() + 180f : b.rotation();
                float dx = (float)Math.cos(Math.toRadians(rotation)) * 3f, dy = (float)Math.sin(Math.toRadians(rotation)) * 3f;
                entry.setValue(new BulletView(b.id(), b.style(), deflected ? 3 - b.team() : b.team(), b.x() + dx, b.y() + dy, rotation, b.age() + 1));
            }
            expired.forEach(bullets::remove);
            if(tick % 500 == 0){
                wave++;
                events.add(new ViewEvent("wave", 0f, 0f, 0f, 2, null, wave));
            }
        }

        Expected expected(boolean takeEvents){
            var floorNames = new String[W * H];
            var envNames = new String[W * H];
            for(int i = 0; i < W * H; i++){
                floorNames[i] = blockNames[floor[i]];
                envNames[i] = blockNames[env[i]];
            }
            int count = takeEvents ? eventsSinceFrame : 0;
            if(takeEvents) eventsSinceFrame = 0;
            return new Expected(tick, floorNames, envNames, new TreeMap<>(builds), new TreeMap<>(units), new TreeMap<>(bullets), count, wave);
        }

        @Override
        public String producer(){
            return "synthetic";
        }

        @Override
        public String game(){
            return "v160.7";
        }

        @Override
        public String content(){
            return "";
        }

        @Override
        public String map(){
            return "synthetic";
        }

        @Override
        public int width(){
            return W;
        }

        @Override
        public int height(){
            return H;
        }

        @Override
        public long tick(){
            return tick;
        }

        @Override
        public List<TeamView> teams(){
            return List.of(new TeamView(1, "sharded", 0xffd37fff), new TeamView(2, "crux", 0xf25555ff));
        }

        @Override
        public String blockName(int id){
            return blockNames[id];
        }

        @Override
        public void terrain(short[] floor, short[] overlay, short[] env){
            System.arraycopy(this.floor, 0, floor, 0, W * H);
            System.arraycopy(this.overlay, 0, overlay, 0, W * H);
            System.arraycopy(this.env, 0, env, 0, W * H);
        }

        @Override
        public void buildings(Consumer<BuildView> out){
            builds.values().forEach(out);
        }

        @Override
        public void units(Consumer<UnitView> out){
            units.values().forEach(out);
        }

        @Override
        public void bullets(Consumer<BulletView> out){
            bullets.values().forEach(out);
        }

        @Override
        public List<ViewEvent> events(){
            var out = List.copyOf(events);
            eventsSinceFrame += out.size();
            events.clear();
            return out;
        }

        @Override
        public Status status(){
            return new Status(wave, 600f - tick % 600, units.size());
        }
    }
}
