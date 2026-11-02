package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;

import java.util.*;

/** The definitions an {@link Epoch} holds: a baked struct with its id and name, and references resolved by the epoch. */
final class Defs{
    private Defs(){
    }

    static Def create(Epoch epoch, ContentType type, int id, String name, Struct fields, boolean added){
        return switch(type){
            case BLOCK -> fields.kind().isA(FloorDef.FLOOR.name())
                ? new Floor(epoch, id, name, fields, added)
                : new Block(epoch, id, name, fields, added);
            case UNIT -> new Unit(epoch, id, name, fields, added);
            case ITEM -> new Item(epoch, id, name, fields, added);
            case LIQUID -> new Liquid(epoch, id, name, fields, added);
            case STATUS -> new Status(epoch, id, name, fields, added);
            case BULLET -> new Bullet(epoch, id, name, fields, added);
            case WEATHER -> new Weather(epoch, id, name, fields, added);
        };
    }

    abstract static sealed class Def implements ContentDef{
        final Epoch epoch;
        private final ContentType type;
        private final int id;
        private final String name;
        private final Struct fields;
        private final boolean added;

        Def(Epoch epoch, ContentType type, int id, String name, Struct fields, boolean added){
            this.epoch = epoch;
            this.type = type;
            this.id = id;
            this.name = name;
            this.fields = fields;
            this.added = added;
        }

        /** Resolves references to other content; runs once every definition of the epoch exists. */
        void link(){
        }

        @Override
        public final int id(){
            return id;
        }

        @Override
        public final String name(){
            return name;
        }

        @Override
        public final ContentType type(){
            return type;
        }

        @Override
        public final Struct fields(){
            return fields;
        }

        @Override
        public final boolean added(){
            return added;
        }

        @Override
        public final boolean baked(){
            return true;
        }

        @Override
        public int emoji(){
            return type == ContentType.BULLET ? 0 : ClientTables.v160_7().emoji(name);
        }

        StatusDef status(String field){
            String ref = fields.s(field);
            return ref == null ? null : epoch.status(ref);
        }

        List<StatusDef> statuses(String field){
            List<StatusDef> out = new ArrayList<>();
            for(Object ref : fields.list(field)) out.add(epoch.status((String)ref));
            return List.copyOf(out);
        }

        @Override
        public String toString(){
            return type.yamlName() + " " + name + " (" + id + ")";
        }
    }

    static sealed class Block extends Def implements BlockDef permits Floor{
        private final KindKey kind, buildKind;
        private final int size;
        private final float health;
        private final boolean update, noUpdateDisabled, hasItems, hasLiquids, hasPower;
        private ItemDef itemDrop;

        Block(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.BLOCK, id, name, fields, added);
            KindSchema root = fields.kind().schema().root(ContentType.BLOCK);
            kind = fields.kind().kindKey();
            buildKind = fields.kind().buildKind().kindKey();
            // Block's own fields: a subclass may hide them (Cliff.size is a float)
            size = (Integer)fields.get(root.field("size"));
            health = (Integer)fields.get(root.field("health")); // an int in vanilla's Block
            update = (Boolean)fields.get(root.field("update"));
            noUpdateDisabled = (Boolean)fields.get(root.field("noUpdateDisabled"));
            hasItems = (Boolean)fields.get(root.field("hasItems"));
            hasLiquids = (Boolean)fields.get(root.field("hasLiquids"));
            hasPower = (Boolean)fields.get(root.field("hasPower"));
        }

        @Override
        public KindKey kind(){
            return kind;
        }

        @Override
        public KindKey buildKind(){
            return buildKind;
        }

        @Override
        public int size(){
            return size;
        }

        @Override
        public float health(){
            return health;
        }

        @Override
        public boolean update(){
            return update;
        }

        @Override
        public boolean noUpdateDisabled(){
            return noUpdateDisabled;
        }

        @Override
        public boolean hasItems(){
            return hasItems;
        }

        @Override
        public boolean hasLiquids(){
            return hasLiquids;
        }

        @Override
        public boolean hasPower(){
            return hasPower;
        }

        @Override
        public ItemDef itemDrop(){
            return itemDrop;
        }

        @Override
        void link(){
            String drop = fields().s("itemDrop");
            itemDrop = drop == null ? null : epoch.item(drop);
        }
    }

    static final class Floor extends Block implements FloorDef{
        private StatusDef status;
        private LiquidDef liquidDrop;

        Floor(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, id, name, fields, added);
        }

        @Override
        void link(){
            super.link();
            status = status("status");
            String liquid = fields().s("liquidDrop");
            liquidDrop = liquid == null ? null : epoch.liquid(liquid);
        }

        @Override
        public StatusDef status(){
            return status;
        }

        @Override
        public LiquidDef liquidDrop(){
            return liquidDrop;
        }
    }

    static final class Unit extends Def implements UnitDef{
        private final float health;
        private List<WeaponDef> weapons;
        // Read on first use, as hot paths call them per unit per tick or per client snapshot (W2.21): NaN, 0 and null
        // are "not read yet". A def is shared by every world's threads; a race only reads the same value twice.
        private float buildSpeed = Float.NaN, aimDst = Float.NaN;
        /** 0: not read yet, 1: false, 2: true. */
        private byte isEnemy;
        private Schema.Entity entityClass;

        Unit(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.UNIT, id, name, fields, added);
            health = fields.f("health");
        }

        @Override
        public float buildSpeed(){
            float value = buildSpeed;
            if(Float.isNaN(value)) buildSpeed = value = UnitDef.super.buildSpeed();
            return value;
        }

        @Override
        public float aimDst(){
            float value = aimDst;
            if(Float.isNaN(value)) aimDst = value = UnitDef.super.aimDst();
            return value;
        }

        @Override
        public boolean isEnemy(){
            byte value = isEnemy;
            if(value == 0) isEnemy = value = UnitDef.super.isEnemy() ? (byte)2 : (byte)1;
            return value == 2;
        }

        @Override
        public Schema.Entity entityClass(){
            Schema.Entity value = entityClass;
            if(value == null) entityClass = value = UnitDef.super.entityClass();
            return value;
        }

        @Override
        void link(){
            List<WeaponDef> out = new ArrayList<>();
            for(Struct w : fields().structs("weapons")){
                String bullet = w.s("bullet");
                String status = w.s("shootStatus");
                out.add(new Weapon(w, bullet == null ? null : epoch.bullet(bullet), status == null ? null : epoch.status(status), w.b("controllable")));
            }
            weapons = List.copyOf(out);
        }

        @Override
        public float health(){
            return health;
        }

        @Override
        public List<WeaponDef> weapons(){
            return weapons;
        }
    }

    /** {@code controllable}, read at link: aiming reads it per weapon per client snapshot. */
    record Weapon(Struct fields, BulletDef bullet, StatusDef shootStatus, boolean controllable) implements WeaponDef{
    }

    static final class Item extends Def implements ItemDef{
        Item(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.ITEM, id, name, fields, added);
        }
    }

    static final class Liquid extends Def implements LiquidDef{
        Liquid(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.LIQUID, id, name, fields, added);
        }

        @Override
        public StatusDef effect(){
            return status("effect");
        }
    }

    static final class Status extends Def implements StatusDef{
        private List<StatusDef> affinities, opposites;

        Status(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.STATUS, id, name, fields, added);
        }

        @Override
        void link(){
            affinities = statuses("affinities");
            opposites = statuses("opposites");
        }

        @Override
        public List<StatusDef> affinities(){
            return affinities;
        }

        @Override
        public List<StatusDef> opposites(){
            return opposites;
        }
    }

    static final class Bullet extends Def implements BulletDef{
        private UnitDef spawnUnit, despawnUnit;
        private BulletDef intervalBullet, lightningType;
        private List<BulletDef> spawnBullets;
        private LiquidDef puddleLiquid, liquid;

        Bullet(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.BULLET, id, name, fields, added);
        }

        @Override
        void link(){
            spawnUnit = unit("spawnUnit");
            despawnUnit = unit("despawnUnit");
            intervalBullet = bullet("intervalBullet");
            lightningType = bullet("lightningType");
            List<BulletDef> spawns = new ArrayList<>();
            for(Object ref : fields().list("spawnBullets")) spawns.add(epoch.bullet((String)ref));
            spawnBullets = List.copyOf(spawns);
            puddleLiquid = liquid("puddleLiquid");
            liquid = fields().has("liquid") ? liquid("liquid") : null;
        }

        private UnitDef unit(String field){
            String ref = fields().s(field);
            return ref == null ? null : epoch.unit(ref);
        }

        private BulletDef bullet(String field){
            String ref = fields().s(field);
            return ref == null ? null : epoch.bullet(ref);
        }

        private LiquidDef liquid(String field){
            String ref = fields().s(field);
            return ref == null ? null : epoch.liquid(ref);
        }

        @Override
        public StatusDef status(){
            return status("status");
        }

        @Override
        public BulletDef fragBullet(){
            return bullet("fragBullet");
        }

        @Override
        public UnitDef spawnUnit(){
            return spawnUnit;
        }

        @Override
        public UnitDef despawnUnit(){
            return despawnUnit;
        }

        @Override
        public BulletDef intervalBullet(){
            return intervalBullet;
        }

        @Override
        public BulletDef lightningType(){
            return lightningType;
        }

        @Override
        public List<BulletDef> spawnBullets(){
            return spawnBullets;
        }

        @Override
        public LiquidDef puddleLiquid(){
            return puddleLiquid;
        }

        @Override
        public LiquidDef liquid(){
            return liquid;
        }
    }

    static final class Weather extends Def implements WeatherDef{
        Weather(Epoch epoch, int id, String name, Struct fields, boolean added){
            super(epoch, ContentType.WEATHER, id, name, fields, added);
        }

        @Override
        public StatusDef status(){
            return status("status");
        }
    }
}
