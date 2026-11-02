import arc.struct.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.core.*;
import mindustry.net.Packets.*;

import java.io.*;
import java.util.*;

/**
 * Records {@code ConnectPacket} bodies as one vanilla build's client writes them, with the fields its server reads
 * back, for :wire's BootstrapTest. Runs against the build's server jar, so only on a throwaway VM (record.sh):
 * {@code java -cp server-release.jar RecordConnect.java > v146.txt}. The source compiles against v146 and later.
 */
public class RecordConnect{
    public static void main(String[] args) throws Exception{
        Version.init();
        System.out.println("# ConnectPacket bodies written by build " + Version.build + " (" + Version.buildString() + "), and the fields its");
        System.out.println("# server reads back. Recorded by tools/wirelift/fixtures/bootstrap/record.sh; do not edit.");
        byte[] id = {1, 2, 3, 4, 5, 6, 7, (byte)0xF8};
        record("official", Version.type, "frog", "en", "AAAAAAAAAAA=", id, false, 0xffd37fff, List.of());
        record("unicode", Version.type, "[accent]日本\u0000🐸 é", "pt_BR", "c2hhcmQhIQ==", id, true, -1,
            List.of("mod-a:1.0", "été", "x"));
        record("nulls", null, null, null, null, new byte[8], false, 0, Arrays.asList("custom", null));
    }

    static void record(String name, String versionType, String player, String locale, String usid, byte[] id, boolean mobile,
                       int color, List<String> mods) throws IOException{
        ConnectPacket packet = new ConnectPacket();
        packet.versionType = versionType;
        packet.name = player;
        packet.locale = locale;
        packet.usid = usid;
        packet.uuid = new String(Base64Coder.encode(id));
        packet.mobile = mobile;
        packet.color = color;
        packet.mods = new Seq<>();
        for(String mod : mods) packet.mods.add(mod);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        packet.write(new Writes(new DataOutputStream(bytes)));
        byte[] body = bytes.toByteArray();

        ConnectPacket back = new ConnectPacket();
        back.read(new Reads(new DataInputStream(new ByteArrayInputStream(body))));
        System.out.println();
        System.out.println("case=" + name);
        System.out.println("build=" + back.version);
        System.out.println("versionType=" + quote(back.versionType));
        System.out.println("name=" + quote(back.name));
        System.out.println("locale=" + quote(back.locale));
        System.out.println("usid=" + quote(back.usid));
        System.out.println("uuid=" + quote(back.uuid));
        System.out.println("mobile=" + back.mobile);
        System.out.println("color=" + back.color);
        System.out.println("mods=" + back.mods.size);
        for(String mod : back.mods) System.out.println("mod=" + quote(mod));
        System.out.println("body=" + HexFormat.of().formatHex(body));
    }

    /** Null, or a quoted string with every character outside printable ASCII as a backslash-u escape. */
    static String quote(String s){
        if(s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for(char c : s.toCharArray()){
            if(c == '"' || c == '\\') out.append('\\').append(c);
            else if(c >= 0x20 && c < 0x7F) out.append(c);
            else out.append(String.format("\\u%04x", (int)c));
        }
        return out.append('"').toString();
    }
}
