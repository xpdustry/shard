package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.Color;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MapObjectives.ObjectiveMarker} (MapObjectives.java:743) and its eight kinds, with their fields,
 * defaults and {@code control} rules. A server keeps only what the JSON carries: drawing, fetched text and texture
 * regions are client-side, and so is everything {@code setTexture} and {@code setUv} do past storing the texture
 * ({@code headless} returns early).
 *
 * <p>{@link #world}, {@link #minimap} and {@link #light} are vanilla's {@code @IndexBool}s: -1 when hidden, otherwise
 * the marker's index in the {@link MapMarkers} list of that kind, which the JSON carries too.
 */
public abstract sealed class ObjectiveMarker implements Checkpointable
    permits ObjectiveMarker.PosMarker, ObjectiveMarker.Quad{
    /** {@code Layer.overlayUI}. */
    public static final float OVERLAY_UI = 120f;
    /** {@code WorldLabel.flagBackground} and {@code flagOutline}. */
    public static final byte FLAG_BACKGROUND = 1, FLAG_OUTLINE = 2;
    /** {@code Align.center}. */
    public static final int ALIGN_CENTER = 1;
    /** {@code Vars.tilesize}. */
    static final float TILE = 8f;

    public int world = 1;
    public int minimap = -1;
    public int light = -1;
    public boolean autoscale;
    public float drawLayer = OVERLAY_UI;

    /** The JSON class tag vanilla writes: the class's simple name without "Marker", or "Minimap" for points. */
    public abstract String tag();

    /** A new marker of the same kind with its defaults. */
    public abstract ObjectiveMarker blank();

    /** A deep copy. */
    public final ObjectiveMarker copy(){
        ObjectiveMarker out = blank();
        out.world = world;
        out.minimap = minimap;
        out.light = light;
        out.autoscale = autoscale;
        out.drawLayer = drawLayer;
        copyFields(out);
        return out;
    }

    abstract void copyFields(ObjectiveMarker into);

    /**
     * {@code control(type, p1, p2, p3)}: a world processor's {@code setmarker}. NaN is "no value"; the visibility
     * flags move the marker in and out of {@code markers}' lists ({@code state.markers.updateMarker}).
     */
    public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
        if(Double.isNaN(p1)) return;
        switch(type){
            case world -> markers.updateMarker(MapMarkers.WORLD, this, !Mathf.equal((float)p1, 0f));
            case minimap -> markers.updateMarker(MapMarkers.MINIMAP, this, !Mathf.equal((float)p1, 0f));
            case light -> markers.updateMarker(MapMarkers.LIGHT, this, !Mathf.equal((float)p1, 0f));
            case autoscale -> autoscale = !Mathf.equal((float)p1, 0f);
            case drawLayer -> drawLayer = (float)p1;
            default -> {}
        }
    }

    /** {@code setText(text, fetch)}: only text markers keep text; fetching it is the client's. */
    public void setText(String text, boolean fetch){
    }

    /** {@code setTexture(texture)}: only texture and quad markers keep one. */
    public void setTexture(Texture texture){
    }

    /** {@code world}, {@code minimap} or {@code light} by {@link MapMarkers} list kind. */
    int index(int kind){
        return switch(kind){
            case MapMarkers.WORLD -> world;
            case MapMarkers.MINIMAP -> minimap;
            default -> light;
        };
    }

    void index(int kind, int index){
        switch(kind){
            case MapMarkers.WORLD -> world = index;
            case MapMarkers.MINIMAP -> minimap = index;
            default -> light = index;
        }
    }

    public void hash(Hasher hasher){
        hasher.str(tag()).i(world).i(minimap).i(light).bool(autoscale).f(drawLayer);
        hashFields(hasher);
    }

    abstract void hashFields(Hasher hasher);

    @Override
    public void write(CheckpointOut out){
        out.i("world", world).i("minimap", minimap).i("light", light).bool("autoscale", autoscale).f("drawLayer", drawLayer);
    }

    @Override
    public void read(CheckpointIn in){
        world = in.i("world");
        minimap = in.i("minimap");
        light = in.i("light");
        autoscale = in.bool("autoscale");
        drawLayer = in.f("drawLayer");
    }

    /** A marker by vanilla's {@code markerNameToType} name ({@code makemarker}'s type, JSON class tags), or null. */
    public static ObjectiveMarker byName(String name){
        return switch(name){
            case "ShapeText", "shapeText" -> new ShapeText();
            case "Point", "point", "Minimap", "minimap" -> new Point();
            case "Shape", "shape" -> new Shape();
            case "Text", "text" -> new Text();
            case "Line", "line" -> new Line();
            case "Texture", "texture" -> new TextureMarker();
            case "Quad", "quad" -> new Quad();
            case "Light", "light" -> new Light();
            case null, default -> null;
        };
    }

    // region colours

    /** {@code Color.fromDouble}: the RGBA8888 in a double's low 32 bits. */
    public static Color fromDouble(double value){
        int rgba = (int)Double.doubleToRawLongBits(value);
        return new Color(((rgba & 0xff000000) >>> 24) / 255f, ((rgba & 0x00ff0000) >>> 16) / 255f, ((rgba & 0x0000ff00) >>> 8) / 255f,
            (rgba & 0x000000ff) / 255f);
    }

    /** {@code Color.toFloatBits()}: ABGR packed into a float, its lowest alpha bit dropped ({@code NumberUtils.intToFloatColor}). */
    public static float toFloatBits(Color color){
        int abgr = (int)(255 * color.a()) << 24 | (int)(255 * color.b()) << 16 | (int)(255 * color.g()) << 8 | (int)(255 * color.r());
        return Float.intBitsToFloat(abgr & 0xfeffffff);
    }

    static void hash(Hasher hasher, Color color){
        hasher.f(color.r()).f(color.g()).f(color.b()).f(color.a());
    }

    static void hash(Hasher hasher, String text){
        hasher.bool(text != null);
        if(text != null) hasher.str(text);
    }

    // endregion

    /**
     * {@code TextureHolder.value} as its JSON keeps it: a texture name, content by name (any {@code UnlockableContent}),
     * a building by tile ({@code Building.pos()}), or {@link #NONE} for anything else, which writes nothing and reads
     * back as "white".
     */
    public record Texture(Kind kind, String name, int pos){
        public enum Kind{NONE, STRING, CONTENT, BUILDING}

        public static final Texture NONE = new Texture(Kind.NONE, null, 0);
        /** {@code TextureHolder}'s default: {@code "white"}. */
        public static final Texture WHITE = string("white");

        public static Texture string(String name){
            return new Texture(Kind.STRING, name, 0);
        }

        public static Texture content(String name){
            return new Texture(Kind.CONTENT, name, 0);
        }

        public static Texture building(int pos){
            return new Texture(Kind.BUILDING, null, pos);
        }

        void hash(Hasher hasher){
            hasher.i(kind.ordinal()).i(pos);
            ObjectiveMarker.hash(hasher, name);
        }
    }

    /** {@code PosMarker}: a marker at a position in world units. */
    public abstract static sealed class PosMarker extends ObjectiveMarker
        permits ShapeText, Point, Shape, Text, Line, TextureMarker, Light{
        public Vec2 pos = new Vec2();

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1) && type == LMarkerControl.pos) pos.x = (float)p1 * TILE;
            if(!Double.isNaN(p2) && type == LMarkerControl.pos) pos.y = (float)p2 * TILE;
        }

        @Override
        void copyFields(ObjectiveMarker into){
            ((PosMarker)into).pos = pos.cpy();
        }

        @Override
        void hashFields(Hasher hasher){
            hasher.f(pos.x).f(pos.y);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.obj("pos", pos);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            pos = in.obj("pos", Vec2.class);
        }
    }

    /** {@code ShapeTextMarker}: text above a polygon. */
    public static final class ShapeText extends PosMarker{
        public String text = "frog";
        public float fontSize = 1f, textHeight = 7f;
        public byte flags = FLAG_BACKGROUND | FLAG_OUTLINE;
        public int textAlign = ALIGN_CENTER;
        public int lineAlign = ALIGN_CENTER;
        public float radius = 6f, rotation = 0f;
        public int sides = 4;
        public Color color = Color.valueOf("ffd37f");

        @Override
        public String tag(){
            return "ShapeText";
        }

        @Override
        public ObjectiveMarker blank(){
            return new ShapeText();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case fontSize -> fontSize = (float)p1;
                    case textHeight -> textHeight = (float)p1;
                    case textAlign -> textAlign = (int)p1;
                    case lineAlign -> lineAlign = (int)p1;
                    case outline -> flags = (byte)bitmask(flags, FLAG_OUTLINE, !Mathf.equal((float)p1, 0f));
                    case labelFlags -> flags = (byte)bitmask(flags, FLAG_BACKGROUND, !Mathf.equal((float)p1, 0f));
                    case radius -> radius = (float)p1;
                    case rotation -> rotation = (float)p1;
                    case color -> color = fromDouble(p1);
                    case shape -> sides = (int)p1;
                    default -> {}
                }
            }
            if(!Double.isNaN(p2) && type == LMarkerControl.labelFlags){
                flags = (byte)bitmask(flags, FLAG_OUTLINE, !Mathf.equal((float)p2, 0f));
            }
        }

        @Override
        public void setText(String text, boolean fetch){
            this.text = text;
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (ShapeText)into;
            out.text = text;
            out.fontSize = fontSize;
            out.textHeight = textHeight;
            out.flags = flags;
            out.textAlign = textAlign;
            out.lineAlign = lineAlign;
            out.radius = radius;
            out.rotation = rotation;
            out.sides = sides;
            out.color = color;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hash(hasher, text);
            hasher.f(fontSize).f(textHeight).i(flags).i(textAlign).i(lineAlign).f(radius).f(rotation).i(sides);
            hash(hasher, color);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.str("text", text).f("fontSize", fontSize).f("textHeight", textHeight).b("flags", flags).i("textAlign", textAlign)
                .i("lineAlign", lineAlign).f("radius", radius).f("rotation", rotation).i("sides", sides).obj("color", color);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            text = in.str("text");
            fontSize = in.f("fontSize");
            textHeight = in.f("textHeight");
            flags = in.b("flags");
            textAlign = in.i("textAlign");
            lineAlign = in.i("lineAlign");
            radius = in.f("radius");
            rotation = in.f("rotation");
            sides = in.i("sides");
            color = in.obj("color", Color.class);
        }
    }

    /** {@code PointMarker}: a pulsing circle. Vanilla writes it with the legacy tag "Minimap" (MapObjectives.java:77). */
    public static final class Point extends PosMarker{
        public float radius = 5f, stroke = 11f;
        public Color color = Color.valueOf("f25555");

        @Override
        public String tag(){
            // registerLegacyMarker("Minimap", ...) runs last, so its tag is the one written
            return "Minimap";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Point();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case radius -> radius = (float)p1;
                    case stroke -> stroke = (float)p1;
                    case color -> color = fromDouble(p1);
                    default -> {}
                }
            }
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (Point)into;
            out.radius = radius;
            out.stroke = stroke;
            out.color = color;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hasher.f(radius).f(stroke);
            hash(hasher, color);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("radius", radius).f("stroke", stroke).obj("color", color);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            radius = in.f("radius");
            stroke = in.f("stroke");
            color = in.obj("color", Color.class);
        }
    }

    /** {@code ShapeMarker}: a polygon or arc, outlined or filled. */
    public static final class Shape extends PosMarker{
        public float radius = 8f, rotation = 0f, stroke = 1f, startAngle = 0f, endAngle = 360f;
        public boolean fill = false, outline = true;
        public int sides = 4;
        public Color color = Color.valueOf("ffd37f");

        @Override
        public String tag(){
            return "Shape";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Shape();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case radius -> radius = (float)p1;
                    case stroke -> stroke = (float)p1;
                    case outline -> outline = !Mathf.equal((float)p1, 0f);
                    case rotation -> rotation = (float)p1;
                    case color -> color = fromDouble(p1);
                    case shape -> sides = (int)p1;
                    case arc -> startAngle = (float)p1;
                    default -> {}
                }
            }
            if(!Double.isNaN(p2)){
                switch(type){
                    case shape -> fill = !Mathf.equal((float)p2, 0f);
                    case arc -> endAngle = (float)p2;
                    default -> {}
                }
            }
            if(!Double.isNaN(p3) && type == LMarkerControl.shape) outline = !Mathf.equal((float)p3, 0f);
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (Shape)into;
            out.radius = radius;
            out.rotation = rotation;
            out.stroke = stroke;
            out.startAngle = startAngle;
            out.endAngle = endAngle;
            out.fill = fill;
            out.outline = outline;
            out.sides = sides;
            out.color = color;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hasher.f(radius).f(rotation).f(stroke).f(startAngle).f(endAngle).bool(fill).bool(outline).i(sides);
            hash(hasher, color);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("radius", radius).f("rotation", rotation).f("stroke", stroke).f("startAngle", startAngle).f("endAngle", endAngle)
                .bool("fill", fill).bool("outline", outline).i("sides", sides).obj("color", color);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            radius = in.f("radius");
            rotation = in.f("rotation");
            stroke = in.f("stroke");
            startAngle = in.f("startAngle");
            endAngle = in.f("endAngle");
            fill = in.bool("fill");
            outline = in.bool("outline");
            sides = in.i("sides");
            color = in.obj("color", Color.class);
        }
    }

    /** {@code TextMarker}: text at a position. */
    public static final class Text extends PosMarker{
        public String text = "uwu";
        public float fontSize = 1f;
        public byte flags = FLAG_BACKGROUND | FLAG_OUTLINE;
        public int textAlign = ALIGN_CENTER;
        public int lineAlign = ALIGN_CENTER;

        @Override
        public String tag(){
            return "Text";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Text();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case fontSize -> fontSize = (float)p1;
                    case textAlign -> textAlign = (int)p1;
                    case lineAlign -> lineAlign = (int)p1;
                    case outline -> flags = (byte)bitmask(flags, FLAG_OUTLINE, !Mathf.equal((float)p1, 0f));
                    case labelFlags -> flags = (byte)bitmask(flags, FLAG_BACKGROUND, !Mathf.equal((float)p1, 0f));
                    default -> {}
                }
            }
            if(!Double.isNaN(p2) && type == LMarkerControl.labelFlags){
                flags = (byte)bitmask(flags, FLAG_OUTLINE, !Mathf.equal((float)p2, 0f));
            }
        }

        @Override
        public void setText(String text, boolean fetch){
            this.text = text;
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (Text)into;
            out.text = text;
            out.fontSize = fontSize;
            out.flags = flags;
            out.textAlign = textAlign;
            out.lineAlign = lineAlign;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hash(hasher, text);
            hasher.f(fontSize).i(flags).i(textAlign).i(lineAlign);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.str("text", text).f("fontSize", fontSize).b("flags", flags).i("textAlign", textAlign).i("lineAlign", lineAlign);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            text = in.str("text");
            fontSize = in.f("fontSize");
            flags = in.b("flags");
            textAlign = in.i("textAlign");
            lineAlign = in.i("lineAlign");
        }
    }

    /** {@code LineMarker}: a line from {@code pos} to {@link #endPos}, coloured from one end to the other. */
    public static final class Line extends PosMarker{
        public Vec2 endPos = new Vec2();
        public float stroke = 1f;
        public boolean outline = true;
        public Color color1 = Color.valueOf("ffd37f");
        public Color color2 = Color.valueOf("ffd37f");

        @Override
        public String tag(){
            return "Line";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Line();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case endPos -> endPos.x = (float)p1 * TILE;
                    case stroke -> stroke = (float)p1;
                    // color1.set(color2.fromDouble(p1)): both ends
                    case color -> color1 = color2 = fromDouble(p1);
                    case outline -> outline = !Mathf.equal((float)p1, 0f);
                    default -> {}
                }
            }
            if(!Double.isNaN(p2) && type == LMarkerControl.endPos) endPos.y = (float)p2 * TILE;
            // an index other than 0 or 1 writes to a scratch value (Tmp.v1, Tmp.c1)
            if(!Double.isNaN(p1) && !Double.isNaN(p2)){
                int i = (int)p1;
                switch(type){
                    case posi -> {
                        if(i == 0) pos.x = (float)p2 * TILE;
                        else if(i == 1) endPos.x = (float)p2 * TILE;
                    }
                    case colori -> {
                        if(i == 0) color1 = fromDouble(p2);
                        else if(i == 1) color2 = fromDouble(p2);
                    }
                    default -> {}
                }
            }
            if(!Double.isNaN(p1) && !Double.isNaN(p3) && type == LMarkerControl.posi){
                int i = (int)p1;
                if(i == 0) pos.y = (float)p3 * TILE;
                else if(i == 1) endPos.y = (float)p3 * TILE;
            }
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (Line)into;
            out.endPos = endPos.cpy();
            out.stroke = stroke;
            out.outline = outline;
            out.color1 = color1;
            out.color2 = color2;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hasher.f(endPos.x).f(endPos.y).f(stroke).bool(outline);
            hash(hasher, color1);
            hash(hasher, color2);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.obj("endPos", endPos).f("stroke", stroke).bool("outline", outline).obj("color1", color1).obj("color2", color2);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            endPos = in.obj("endPos", Vec2.class);
            stroke = in.f("stroke");
            outline = in.bool("outline");
            color1 = in.obj("color1", Color.class);
            color2 = in.obj("color2", Color.class);
        }
    }

    /** {@code TextureMarker}: a named texture, content icon or display at a position; size 0 is the texture's own. */
    public static final class TextureMarker extends PosMarker{
        public float rotation = 0f, width = 0f, height = 0f;
        public Texture texture = Texture.WHITE;
        public Color color = new Color(1f, 1f, 1f, 1f);

        @Override
        public String tag(){
            return "Texture";
        }

        @Override
        public ObjectiveMarker blank(){
            return new TextureMarker();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case rotation -> rotation = (float)p1;
                    case textureSize -> width = (float)p1 * TILE;
                    case color -> color = fromDouble(p1);
                    default -> {}
                }
            }
            if(!Double.isNaN(p2) && type == LMarkerControl.textureSize) height = (float)p2 * TILE;
        }

        @Override
        public void setTexture(Texture texture){
            this.texture = texture;
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (TextureMarker)into;
            out.rotation = rotation;
            out.width = width;
            out.height = height;
            out.texture = texture;
            out.color = color;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hasher.f(rotation).f(width).f(height);
            texture.hash(hasher);
            hash(hasher, color);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("rotation", rotation).f("width", width).f("height", height).obj("texture", texture).obj("color", color);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            rotation = in.f("rotation");
            width = in.f("width");
            height = in.f("height");
            texture = in.obj("texture", Texture.class);
            color = in.obj("color", Color.class);
        }
    }

    /**
     * {@code QuadMarker}: a textured quad of four vertices, six floats each (x, y, colour bits, u, v, mix colour bits).
     * A server never maps UVs ({@code setUv} returns when headless), so {@link #mapRegion} keeps what the JSON said.
     */
    public static final class Quad extends ObjectiveMarker{
        public Texture texture = Texture.WHITE;
        public float[] vertices = new float[24];
        public boolean mapRegion = true;

        public Quad(){
            float white = toFloatBits(new Color(1f, 1f, 1f, 1f));
            for(int i = 0; i < 4; i++){
                vertices[i * 6 + 2] = white;
                // Color.clearFloatBits
                vertices[i * 6 + 5] = 0f;
            }
        }

        @Override
        public String tag(){
            return "Quad";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Quad();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case color -> {
                        float color = toFloatBits(fromDouble(p1));
                        for(int i = 0; i < 4; i++) vertices[i * 6 + 2] = color;
                    }
                    case pos -> vertices[0] = (float)p1 * TILE;
                    case posi -> setPos((int)p1, p2, p3);
                    // setUv returns at once on a server
                    case uvi -> {}
                    default -> {}
                }
            }
            // Vanilla quirk (MapObjectives.java:1342): pos sets the first vertex's y from p1, not p2.
            if(!Double.isNaN(p2) && type == LMarkerControl.pos) vertices[1] = (float)p1 * TILE;
            if(!Double.isNaN(p1) && !Double.isNaN(p2) && type == LMarkerControl.colori){
                int i = (int)p1;
                if(i >= 0 && i < 4) vertices[i * 6 + 2] = toFloatBits(fromDouble(p2));
            }
        }

        private void setPos(int i, double x, double y){
            if(i >= 0 && i < 4){
                if(!Double.isNaN(x)) vertices[i * 6] = (float)x * TILE;
                if(!Double.isNaN(y)) vertices[i * 6 + 1] = (float)y * TILE;
            }
        }

        @Override
        public void setTexture(Texture texture){
            this.texture = texture;
        }

        @Override
        void copyFields(ObjectiveMarker into){
            var out = (Quad)into;
            out.texture = texture;
            out.vertices = vertices.clone();
            out.mapRegion = mapRegion;
        }

        @Override
        void hashFields(Hasher hasher){
            texture.hash(hasher);
            hasher.floats(vertices).bool(mapRegion);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.obj("texture", texture).floats("vertices", vertices).bool("mapRegion", mapRegion);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            texture = in.obj("texture", Texture.class);
            vertices = in.floats("vertices");
            mapRegion = in.bool("mapRegion");
        }
    }

    /** {@code LightMarker}: a point light, which only clients draw. */
    public static final class Light extends PosMarker{
        public float radius = 5f;
        public Color color = Color.valueOf("ffd37f");

        @Override
        public String tag(){
            return "Light";
        }

        @Override
        public ObjectiveMarker blank(){
            return new Light();
        }

        @Override
        public void control(MapMarkers markers, LMarkerControl type, double p1, double p2, double p3){
            super.control(markers, type, p1, p2, p3);
            if(!Double.isNaN(p1)){
                switch(type){
                    case radius -> radius = (float)p1;
                    case color -> color = fromDouble(p1);
                    default -> {}
                }
            }
        }

        @Override
        void copyFields(ObjectiveMarker into){
            super.copyFields(into);
            var out = (Light)into;
            out.radius = radius;
            out.color = color;
        }

        @Override
        void hashFields(Hasher hasher){
            super.hashFields(hasher);
            hasher.f(radius);
            hash(hasher, color);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("radius", radius).obj("color", color);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            radius = in.f("radius");
            color = in.obj("color", Color.class);
        }
    }

    /** {@code Pack.bitmask(bits, mask, set)}. */
    static int bitmask(int bits, int mask, boolean set){
        return set ? bits | mask : bits & ~mask;
    }
}
