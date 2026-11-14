package de.servergrid;

import de.servergrid.mixin.MultiplayerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Zeichnet die Serverliste als 3-Spalten-Raster mit Suchfeld. */
final class GridView {
    private static final Identifier UNKNOWN = Identifier.ofVanilla("textures/misc/unknown_server.png");
    private static int iconCounter = 0;
    private static Screen lastScreen;
    private static String lastQuery = "";

    private static final int COLS = 3, GAP = 8, CARD_H = 64, ICON = 48;
    private static final int ACCENT = 0xFF3D5AFE;

    private record Icon(byte[] data, Identifier id) {}

    private final MinecraftClient mc;
    private final MultiplayerScreen screen;
    private final int sw, sh;
    private final Map<ServerInfo, Icon> icons = new IdentityHashMap<>();

    private MultiplayerServerListWidget list;
    private TextFieldWidget search;
    private int scroll = 0;
    private boolean wasDown = false;
    private long lastClickTime = 0;
    private Object lastClicked = null;

    GridView(MinecraftClient mc, MultiplayerScreen screen, int sw, int sh) {
        this.mc = mc;
        this.screen = screen;
        this.sw = sw;
        this.sh = sh;
    }

    void init() {
        if (lastScreen != screen) {
            lastScreen = screen;
            lastQuery = "";
        }
        MultiplayerScreenAccessor acc = (MultiplayerScreenAccessor) screen;
        list = acc.servergrid$getList();
        list.visible = false; // Vanilla-Liste verstecken
        list.active = false;

        int fw = Math.min(260, sw - 40);
        search = new TextFieldWidget(mc.textRenderer, sw / 2 - fw / 2, 38, fw, 20, Text.literal("Suche"));
        search.setPlaceholder(Text.literal("Server suchen …"));
        search.setText(lastQuery);
        search.setChangedListener(s -> { lastQuery = s; scroll = 0; });
        Screens.getButtons(screen).add(search);

        ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> render(ctx, mx, my, delta));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> {
            scroll -= (int) (v * 24);
            return false;
        });
        ScreenEvents.remove(screen).register(s -> {
            for (Icon ic : icons.values()) {
                if (ic.id != UNKNOWN) mc.getTextureManager().destroyTexture(ic.id);
            }
            icons.clear();
        });
    }

    private List<MultiplayerServerListWidget.ServerEntry> filtered() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<MultiplayerServerListWidget.ServerEntry> out = new ArrayList<>();
        for (Object o : list.children()) {
            if (o instanceof MultiplayerServerListWidget.ServerEntry e) {
                ServerInfo i = e.getServer();
                if (q.isEmpty()
                        || i.name.toLowerCase(Locale.ROOT).contains(q)
                        || i.address.toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private void render(DrawContext ctx, int mx, int my, float delta) {
        int top = 66, bottom = sh - 68;
        int areaW = Math.min(sw - 24, 560);
        int cardW = (areaW - GAP * (COLS - 1)) / COLS;
        int usedW = cardW * COLS + GAP * (COLS - 1);
        int left = (sw - usedW) / 2;

        // Panel
        ctx.fill(0, 33, sw, sh - 64, 0xE60F1117);
        ctx.fill(0, 33, sw, 34, 0xFF2A2F3D);
        ctx.fill(0, sh - 65, sw, sh - 64, 0xFF2A2F3D);
        search.render(ctx, mx, my, delta);

        List<MultiplayerServerListWidget.ServerEntry> entries = filtered();

        // Vanilla-Eintraege unsichtbar rendern, damit Pings/Favicons geladen werden
        ctx.enableScissor(0, 0, 0, 0);
        for (var e : entries) {
            try { e.render(ctx, -1, -1, false, delta); } catch (Throwable ignored) {}
        }
        ctx.disableScissor();

        int rows = (entries.size() + COLS - 1) / COLS;
        int contentH = rows * (CARD_H + GAP);
        int maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        // Klick-Erkennung (links, steigende Flanke)
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean clicked = down && !wasDown;
        wasDown = down;

        Object selected = list.getSelectedOrNull();

        ctx.enableScissor(0, top, sw, bottom);
        if (entries.isEmpty()) {
            String msg = "Keine Server gefunden";
            ctx.drawText(mc.textRenderer, msg, sw / 2 - mc.textRenderer.getWidth(msg) / 2, top + 20, 0xFF8A90A2, false);
        }
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            int x = left + (i % COLS) * (cardW + GAP);
            int y = top + (i / COLS) * (CARD_H + GAP) - scroll;
            if (y + CARD_H < top || y > bottom) continue;

            boolean hover = mx >= x && mx < x + cardW && my >= y && my < y + CARD_H && my >= top && my < bottom;
            boolean sel = e == selected;

            if (hover && clicked) {
                list.setSelected(e);
                long now = System.currentTimeMillis();
                if (lastClicked == e && now - lastClickTime < 350) {
                    ServerInfo si = e.getServer();
                    ConnectScreen.connect(screen, mc, ServerAddress.parse(si.address), si, false, null);
                }
                lastClicked = e;
                lastClickTime = now;
                sel = true;
            }
            drawCard(ctx, e.getServer(), x, y, cardW, hover, sel);
        }
        ctx.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            int trackH = bottom - top;
            int barH = Math.max(20, trackH * trackH / contentH);
            int barY = top + (int) ((trackH - barH) * (scroll / (float) maxScroll));
            ctx.fill(sw - 6, barY, sw - 3, barY + barH, 0xFF3A4156);
        }
    }

    private void drawCard(DrawContext ctx, ServerInfo info, int x, int y, int w, boolean hover, boolean sel) {
        int bg = sel ? 0xFF1C2655 : hover ? 0xFF262C3D : 0xFF1B1F2B;
        int border = sel ? ACCENT : hover ? 0xFF3A4156 : 0xFF2A2F3D;
        ctx.fill(x, y, x + w, y + CARD_H, border);
        ctx.fill(x + 1, y + 1, x + w - 1, y + CARD_H - 1, bg);
        if (sel) ctx.fill(x + 1, y + CARD_H - 3, x + w - 1, y + CARD_H - 1, ACCENT);

        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, iconFor(info), x + 8, y + 7, 0f, 0f, ICON, ICON, 64, 64, 64, 64);

        int tx = x + ICON + 16;
        int avail = w - (ICON + 16) - 6;
        var tr = mc.textRenderer;
        ctx.drawText(tr, tr.trimToWidth(info.name, avail), tx, y + 9, 0xFFFFFFFF, true);
        String players = info.playerCountLabel == null ? "" : info.playerCountLabel.getString();
        ctx.drawText(tr, tr.trimToWidth(players, avail), tx, y + 22, 0xFF9AA1B5, false);
        if (info.ping >= 0) {
            int c = info.ping < 100 ? 0xFF4ADE80 : info.ping < 250 ? 0xFFFACC15 : 0xFFF87171;
            ctx.drawText(tr, info.ping + " ms", tx, y + 35, c, false);
        }
        String motd = info.label == null ? "" : info.label.getString().replace('\n', ' ');
        ctx.drawText(tr, tr.trimToWidth(motd, avail), tx, y + 48, 0xFF6F768A, false);
    }

    private Identifier iconFor(ServerInfo info) {
        byte[] fav = info.getFavicon();
        if (fav == null) return UNKNOWN;
        Icon ic = icons.get(info);
        if (ic != null && ic.data == fav) return ic.id;
        if (ic != null && ic.id != UNKNOWN) mc.getTextureManager().destroyTexture(ic.id);
        Identifier id = UNKNOWN;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(fav));
            id = Identifier.of("servergrid", "icon_" + (iconCounter++));
            mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "servergrid icon", img));
        } catch (Exception ex) {
            id = UNKNOWN;
        }
        icons.put(info, new Icon(fav, id));
        return id;
    }
}
