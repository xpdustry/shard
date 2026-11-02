package com.xpdustry.shard.server.admin;

import java.nio.charset.*;
import java.util.*;

/** Player name text: Arc's color-tag stripping, and the plain form used to search names. */
public final class Names{
    /**
     * Arc's named colors, as a headless server has them: the constants of {@code Colors.reset()}
     * ({@code arc/graphics/Colors.java:51}) plus their lower-case forms without underscores. The client adds more
     * ({@code accent}...) in {@code UI}, which a server never loads.
     */
    static final Set<String> COLORS = colors();

    /**
     * Removes color tags, as Arc's {@code Strings.stripColors} ({@code arc/util/Strings.java:191}): {@code [red]},
     * {@code [#ff0000]}, {@code [#ff000080]} and {@code []} go; an unknown tag stays.
     */
    public static String stripColors(CharSequence text){
        var out = new StringBuilder(text.length());
        int i = 0;
        while(i < text.length()){
            char c = text.charAt(i);
            if(c == '['){
                int length = markup(text, i + 1, text.length());
                if(length >= 0){
                    i += length + 2;
                }else{
                    // Arc quirk: "[[" keeps one '[' and then reads the second '[' as the start of a tag
                    out.append(c);
                    i++;
                }
            }else{
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Vanilla's {@code maxNameLength}: a fixed name stops growing once it reaches this many UTF-8 bytes. */
    public static final int MAX_LENGTH = 40;

    /**
     * Vanilla's {@code NetServer.fixName} ({@code NetServer.java:1261}): trims, drops newlines and tabs, removes
     * color tags that would make the rest transparent, and cuts the name once it has {@link #MAX_LENGTH} UTF-8 bytes.
     * Null becomes empty.
     */
    public static String fix(String name){
        if(name == null) return "";
        name = name.trim().replace("\n", "").replace("\t", "");
        if(name.equals("[") || name.equals("]")) return "";
        for(int i = 0; i < name.length(); i++){
            if(name.charAt(i) == '[' && i != name.length() - 1 && name.charAt(i + 1) != '[' && (i == 0 || name.charAt(i - 1) != '[')){
                name = name.substring(0, i) + checkColor(name.substring(i));
            }
        }
        var result = new StringBuilder();
        int at = 0;
        // vanilla quirk: the length is checked before each char, so the last one may pass 40 bytes
        while(at < name.length() && result.toString().getBytes(StandardCharsets.UTF_8).length < MAX_LENGTH) result.append(name.charAt(at++));
        return result.toString();
    }

    /** {@code NetServer.checkColor}: drops a leading tag whose color isn't opaque. */
    private static String checkColor(String text){
        for(int i = 1; i < text.length(); i++){
            if(text.charAt(i) != ']') continue;
            String color = text.substring(1, i);
            if(COLORS.contains(color.toUpperCase(Locale.ROOT)) || COLORS.contains(color.toLowerCase(Locale.ROOT))){
                // CLEAR is the only named color with alpha below 1
                if(color.equalsIgnoreCase("clear")) return text.substring(i + 1);
            }else{
                int alpha = hexAlpha(color);
                // Arc's Color.valueOf threw: the tag stays, and so does the rest
                if(alpha == NOT_HEX) return text;
                if(alpha < 255) return text.substring(i + 1);
            }
        }
        return text;
    }

    private static final int NOT_HEX = Integer.MIN_VALUE;

    /**
     * The alpha Arc's {@code Color.valueOf} reads, which is 255 unless the hex has 8 digits; an invalid digit counts
     * as -1, as in its {@code parseHex}. {@link #NOT_HEX} where it would throw: fewer than 6 characters.
     */
    private static int hexAlpha(String hex){
        int offset = !hex.isEmpty() && hex.charAt(0) == '#' ? 1 : 0;
        if(hex.length() < offset + 6) return NOT_HEX;
        if(hex.length() - offset != 8) return 255;
        return Character.digit(hex.charAt(offset + 6), 16) * 16 + Character.digit(hex.charAt(offset + 7), 16);
    }

    /** The search form of a name: colors stripped, trimmed, lower case. */
    public static String plain(String name){
        return stripColors(name).trim().toLowerCase(Locale.ROOT);
    }

    /** Arc's {@code parseColorMarkup} ({@code arc/util/Strings.java:229}): the tag's length, or negative if none. */
    private static int markup(CharSequence text, int start, int end){
        if(start >= end) return -1;
        switch(text.charAt(start)){
            case '#' -> {
                for(int i = start + 1; i < end; i++){
                    char ch = text.charAt(i);
                    if(ch == ']'){
                        if(i < start + 2 || i > start + 9) break;
                        return i - start;
                    }
                    if(!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F')) break;
                }
                return -1;
            }
            case '[' -> {
                return -2;
            }
            case ']' -> {
                return 0;
            }
            default -> {
            }
        }
        for(int i = start + 1; i < end; i++){
            if(text.charAt(i) != ']') continue;
            return COLORS.contains(text.subSequence(start, i).toString()) ? i - start : -1;
        }
        return -1;
    }

    private static Set<String> colors(){
        var names = List.of("CLEAR", "BLACK", "WHITE", "LIGHT_GRAY", "GRAY", "DARK_GRAY", "LIGHT_GREY", "GREY", "DARK_GREY",
            "BLUE", "NAVY", "ROYAL", "SLATE", "SKY", "CYAN", "TEAL", "GREEN", "ACID", "LIME", "FOREST", "OLIVE", "YELLOW",
            "GOLD", "GOLDENROD", "ORANGE", "BROWN", "TAN", "BRICK", "RED", "SCARLET", "CRIMSON", "CORAL", "SALMON", "PINK",
            "MAGENTA", "PURPLE", "VIOLET", "MAROON");
        var all = new HashSet<String>(names);
        for(String name : names) all.add(name.toLowerCase(Locale.ROOT).replace("_", ""));
        return Collections.unmodifiableSet(all);
    }

    private Names(){}
}
