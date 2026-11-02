package com.xpdustry.shard.compat.v160.blocks.defense;

import arc.func.*;
import arc.math.geom.*;
import arc.struct.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Bullets#intersectQuad} against Arc's own {@code QuadTree}, as {@code Groups.bullet} fills it: the same
 * bullets, filled in the same order over the same bounds, visited in the same order for every query, including after
 * they moved since the fill. The projectors' fixtures can't see the order of bullets that are treated alike; this can.
 */
class BulletTreeOracleTest{
    static final int SIZE = 64, BULLETS = 400, QUERIES = 300;

    /** A bullet's hitbox as {@code HitboxComp.hitbox}: centred on its position. */
    static final class Box implements QuadTree.QuadTreeObject{
        final int id;
        float x, y, size;

        Box(int id, float x, float y, float size){
            this.id = id;
            this.x = x;
            this.y = y;
            this.size = size;
        }

        @Override
        public void hitbox(Rect out){
            out.setCentered(x, y, size, size);
        }
    }

    @Test
    void theTreeVisitsBulletsInArcsOrder(){
        var world = new World(WorldConfig.of(Epoch.vanilla(), SIZE, SIZE, 1).withSystems(Systems.builtIn())
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        Bullets bullets = world.bullets();
        BulletType type = BulletTypes.of(world).get(0);
        var random = new Random(7);
        var boxes = new ArrayList<Box>();
        var byId = new HashMap<Integer, Box>();
        float span = SIZE * 8f;
        for(int i = 0; i < BULLETS; i++){
            // half in clusters, so the tree splits deep; some outside the map, as far as the root's margin
            float x, y;
            if(i % 2 == 0){
                x = -240f + random.nextFloat() * (span + 480f);
                y = -240f + random.nextFloat() * (span + 480f);
            }else{
                int cluster = random.nextInt(4);
                x = 40f + cluster * 110f + random.nextFloat() * 6f;
                y = 60f + cluster * 70f + random.nextFloat() * 6f;
            }
            Bullet bullet = type.create(world, null, 1, x, y, 0f);
            int row = bullet.row();
            float size = 2f + random.nextInt(12);
            bullets.x[row] = x;
            bullets.y[row] = y;
            bullets.hitSize[row] = size;
            var box = new Box(bullets.id[row], x, y, size);
            boxes.add(box);
            byId.put(box.id, box);
        }
        assertEquals(BULLETS, bullets.size(), "every bullet is in the group");
        bullets.updatePhysics(SIZE, SIZE);
        // EntityCollisions.updatePhysics: group.tree().fill(group.rawSeq()), the tree resized as Groups.resize does
        var tree = new QuadTree<Box>(new Rect(-250f, -250f, span + 500f, span + 500f));
        tree.fill(Seq.with(boxes));

        compare(bullets, tree, random, "at the fill");
        // the tree keeps where it put them; a query tests the current hitboxes
        for(int row = 0; row < bullets.size(); row++){
            float dx = random.nextFloat() * 20f - 10f, dy = random.nextFloat() * 20f - 10f;
            bullets.x[row] += dx;
            bullets.y[row] += dy;
            Box box = byId.get(bullets.id[row]);
            box.x += dx;
            box.y += dy;
        }
        compare(bullets, tree, random, "after moving");
    }

    static void compare(Bullets bullets, QuadTree<Box> tree, Random random, String when){
        int visited = 0;
        for(int q = 0; q < QUERIES; q++){
            float w = 4f + random.nextFloat() * 200f, h = 4f + random.nextFloat() * 200f;
            float x = -260f + random.nextFloat() * (SIZE * 8f + 300f), y = -260f + random.nextFloat() * (SIZE * 8f + 300f);
            var vanilla = new ArrayList<Integer>();
            tree.intersect(x, y, w, h, (Cons<Box>)box -> vanilla.add(box.id));
            var shard = new ArrayList<Integer>();
            bullets.intersectQuad(x, y, w, h, (table, row) -> shard.add(table.id[row]));
            assertEquals(vanilla, shard, when + ": query " + q + " (" + x + ", " + y + ", " + w + ", " + h + ")");
            visited += vanilla.size();
        }
        assertTrue(visited > QUERIES, when + ": the queries found bullets (" + visited + ")");
    }
}
