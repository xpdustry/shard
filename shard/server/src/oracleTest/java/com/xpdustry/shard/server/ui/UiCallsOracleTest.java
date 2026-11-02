package com.xpdustry.shard.server.ui;

import arc.graphics.Color;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.Packet;
import mindustry.content.Fx;
import mindustry.gen.Call;
import mindustry.gen.Sounds;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP52): every {@link Ui} method sends exactly the Call vanilla's of the same name sends: the same packet id and
 * the same body, byte for byte, against the vanilla server's own {@code Call} (to one connection and to everyone,
 * which share a body). Ids, texts and numbers are odd on purpose (nulls, unicode, negative floats) so a field written
 * in the wrong order or width shows.
 */
class UiCallsOracleTest{
    record Sent(To to, Packet packet){
    }

    final List<Sent> sent = new ArrayList<>();
    final Ui ui = new Ui(null, (to, packet) -> sent.add(new Sent(to, packet)));
    final To player = To.everyone();

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @BeforeEach
    void reset(){
        Vanilla.reset();
        sent.clear();
    }

    /** What {@code shard} made Ui send, against the frame {@code vanilla} sends. */
    void same(Runnable shard, Runnable vanilla){
        shard.run();
        assertEquals(1, sent.size(), "one Call");
        Packet packet = sent.getFirst().packet();
        sent.clear();
        Wire.Frame expected = Golden.call(vanilla);
        var out = new Writes(64);
        packet.write(out, true, null);
        assertEquals(expected.id(), packet.packetId(), expected.packet() + " vs " + packet);
        assertEquals(Hex.of(expected.body()), Hex.of(out.toByteArray()), expected.packet() + " vs " + packet);
    }

    static final String[][] OPTIONS = {{"[accent]Yes", "No"}, {}, {"Ünïcode ✓", null}};

    @Test
    void menus(){
        same(() -> ui.menu(player, 7, "Title", "Pick one", OPTIONS), () -> Call.menu(Vanilla.connection(), 7, "Title", "Pick one", OPTIONS));
        same(() -> ui.menu(player, -3, null, "", new String[0][]), () -> Call.menu(-3, null, "", new String[0][]));
        same(() -> ui.followUpMenu(player, 7, "Again", null, OPTIONS), () -> Call.followUpMenu(Vanilla.connection(), 7, "Again", null, OPTIONS));
        same(() -> ui.hideFollowUpMenu(player, 7), () -> Call.hideFollowUpMenu(Vanilla.connection(), 7));
        same(() -> ui.textInput(player, 9, "Name", "Type it", 40, "def", true), () -> Call.textInput(Vanilla.connection(), 9, "Name", "Type it", 40, "def", true));
        same(() -> ui.textInput(player, 9, "Name", null, 0, null, false, true),
            () -> Call.textInput(Vanilla.connection(), 9, "Name", null, 0, null, false, true));
    }

    @Test
    void messagesPopupsAndToasts(){
        same(() -> ui.infoMessage(player, "[scarlet]Game over![]\n"), () -> Call.infoMessage(Vanilla.connection(), "[scarlet]Game over![]\n"));
        same(() -> ui.announce(player, "Wave 12"), () -> Call.announce(Vanilla.connection(), "Wave 12"));
        same(() -> ui.infoToast(player, "toast", 2.5f), () -> Call.infoToast(Vanilla.connection(), "toast", 2.5f));
        same(() -> ui.warningToast(player, 0xe80d, "careful"), () -> Call.warningToast(Vanilla.connection(), 0xe80d, "careful"));
        same(() -> ui.hudText(player, "hud", false), () -> Call.setHudText(Vanilla.connection(), "hud"));
        same(() -> ui.hudText(player, "hud", true), () -> Call.setHudTextReliable(Vanilla.connection(), "hud"));
        same(() -> ui.hideHudText(player), () -> Call.hideHudText(Vanilla.connection()));
        same(() -> ui.infoPopup(player, "popup", null, 3f, 1, 2, -3, 4, 5, false), () -> Call.infoPopup(Vanilla.connection(), "popup", 3f, 1, 2, -3, 4, 5));
        same(() -> ui.infoPopup(player, "popup", null, 3f, 1, 2, -3, 4, 5, true), () -> Call.infoPopupReliable(Vanilla.connection(), "popup", 3f, 1, 2, -3, 4, 5));
        same(() -> ui.infoPopup(player, null, "id-1", -1f, 8, 0, 0, 0, 0, false), () -> Call.infoPopup(Vanilla.connection(), null, "id-1", -1f, 8, 0, 0, 0, 0));
        same(() -> ui.infoPopup(player, "p", "id-1", 1f, 8, 0, 0, 0, 0, true), () -> Call.infoPopupReliable(Vanilla.connection(), "p", "id-1", 1f, 8, 0, 0, 0, 0));
        same(() -> ui.openUri(player, "https://mindustrygame.github.io"), () -> Call.openURI(Vanilla.connection(), "https://mindustrygame.github.io"));
        same(() -> ui.copyToClipboard(player, "copied"), () -> Call.copyToClipboard(Vanilla.connection(), "copied"));
    }

    @Test
    void labels(){
        same(() -> ui.label(player, "here", 4f, 16.5f, -8f, false), () -> Call.label(Vanilla.connection(), "here", 4f, 16.5f, -8f));
        same(() -> ui.label(player, "here", 4f, 16.5f, -8f, true), () -> Call.labelReliable(Vanilla.connection(), "here", 4f, 16.5f, -8f));
        same(() -> ui.label(player, null, 11, 4f, 16.5f, -8f, 3, false), () -> Call.label(Vanilla.connection(), null, 11, 4f, 16.5f, -8f, 3));
        same(() -> ui.label(player, "x", 11, 4f, 16.5f, -8f, 0, true), () -> Call.labelReliable(Vanilla.connection(), "x", 11, 4f, 16.5f, -8f, 0));
        same(() -> ui.removeLabel(player, 11), () -> Call.removeWorldLabel(Vanilla.connection(), 11));
    }

    @Test
    void effectsAndSounds(){
        Color color = Color.valueOf("ff4000c0");
        same(() -> ui.effect(player, Fx.hitBulletSmall.id, 10f, -20.25f, 45f, color.rgba(), false),
            () -> Call.effect(Vanilla.connection(), Fx.hitBulletSmall, 10f, -20.25f, 45f, color));
        same(() -> ui.effect(player, Fx.hitBulletSmall.id, 10f, -20.25f, 45f, color.rgba(), true),
            () -> Call.effectReliable(Vanilla.connection(), Fx.hitBulletSmall, 10f, -20.25f, 45f, color));
        same(() -> ui.effect(player, Fx.spawn.id, 1f, 2f, 3f, color.rgba(), (Object)"data"),
            () -> Call.effect(Vanilla.connection(), Fx.spawn, 1f, 2f, 3f, color, "data"));
        same(() -> ui.effect(player, Fx.spawn.id, 1f, 2f, 3f, color.rgba(), (Object)17),
            () -> Call.effect(Vanilla.connection(), Fx.spawn, 1f, 2f, 3f, color, 17));
        int sound = Sounds.getSoundId(Sounds.click);
        same(() -> ui.sound(player, sound, 0.5f, 1.25f, -1f), () -> Call.sound(Vanilla.connection(), Sounds.click, 0.5f, 1.25f, -1f));
        same(() -> ui.soundAt(player, sound, 8f, 9f, 1f, 0.75f), () -> Call.soundAt(Vanilla.connection(), Sounds.click, 8f, 9f, 1f, 0.75f));
    }

    @Test
    void pluginPackets(){
        same(() -> ui.clientPacket(player, "plugin:hello", "{\"a\":1}", true), () -> Call.clientPacketReliable(Vanilla.connection(), "plugin:hello", "{\"a\":1}"));
        same(() -> ui.clientPacket(player, "plugin:hello", null, false), () -> Call.clientPacketUnreliable(Vanilla.connection(), "plugin:hello", null));
        byte[] bytes = {0, 1, -2, 127, -128};
        same(() -> ui.clientBinaryPacket(player, "bin", bytes, true), () -> Call.clientBinaryPacketReliable(Vanilla.connection(), "bin", bytes));
        same(() -> ui.clientBinaryPacket(player, "bin", new byte[0], false), () -> Call.clientBinaryPacketUnreliable(Vanilla.connection(), "bin", new byte[0]));
    }
}
