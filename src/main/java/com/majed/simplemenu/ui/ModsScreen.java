package com.majed.simplemenu.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Simple mod list screen that shows ONLY jars from the user's real .minecraft/mods.
 * Selection is done by clicking a row; details render on the right.
 * Adds: Configure (via ModMenu shim), Reveal File, Copy ID, Copy Version, Export JSON, clickable links.
 * Also shows dependency info (depends/recommends/conflicts/breaks) from fabric.mod.json.
 * NOW: sorting (Name / ID / Version) + asc/desc toggles.
 */
public class ModsScreen extends Screen {
    private final Screen parent;

    private TextFieldWidget search;

    private List<ModRow> allRows = new ArrayList<>();
    private List<ModRow> filtered = new ArrayList<>();

    private int scrollY = 0;                  // pixel offset in the list
    private static final int ROW_H = 20;      // row height
    private static final int LIST_TOP_PAD = 8 + 20 + 6 + 22; // search area + gap + sort buttons
    private static final int FOOTER_H = 36;

    private int hoverIndex = -1;              // row index currently hovered (in filtered)
    private int selectedIndex = -1;           // row index currently selected (in filtered)
    private Path realModsDir;                 // resolved mods dir (real .minecraft/mods)

    // For click edge detection without overriding input methods
    private boolean prevMouseDown = false;

    // Bottom buttons we toggle
    private ButtonWidget configureBtn;
    private ButtonWidget revealBtn;
    private ButtonWidget copyIdBtn;
    private ButtonWidget copyVerBtn;
    private ButtonWidget exportJsonBtn;

    // Sort controls
    private ButtonWidget sortKeyBtn;
    private ButtonWidget sortDirBtn;

    private enum SortKey { NAME, ID, VERSION }
    private SortKey sortKey = SortKey.NAME;
    private boolean sortAsc = true;

    // Tiny status toast at the bottom-left
    private String hudMsg = null;
    private long hudUntilMs = 0;

    // Link hitboxes built during details rendering (right pane)
    private final List<LinkSpan> linkSpans = new ArrayList<>();

    public ModsScreen(Screen parent) {
        super(Text.literal("Mods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Resolve the real mods folder once
        this.realModsDir = resolvePreferredModsDir().toAbsolutePath().normalize();

        // Collect & keep only mods coming from the real mods folder
        this.allRows = FabricLoader.getInstance()
                .getAllMods()
                .stream()
                .filter(this::isFromRealModsDir)
                .map(mc -> new ModRow(mc, mc.getMetadata()))
                .collect(Collectors.toList());

        // Search box
        int boxW = Math.min(this.width - 360, 420);
        this.search = new TextFieldWidget(this.textRenderer, 8, 8, boxW, 20, Text.literal("Search mods"));
        this.search.setDrawsBackground(true);
        this.search.setChangedListener(s -> {
            filterAndSort();
        });
        this.addSelectableChild(this.search);
        this.setInitialFocus(this.search);

        // Sort buttons row (right side of search)
        int sortRowY = 8 + 20 + 6; // under search
        int btnH = 18;
        int btnW1 = 110;
        int btnW2 = 90;

        sortKeyBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal(sortKeyLabel()),
                b -> {
                    // cycle NAME -> ID -> VERSION
                    switch (sortKey) {
                        case NAME -> sortKey = SortKey.ID;
                        case ID -> sortKey = SortKey.VERSION;
                        case VERSION -> sortKey = SortKey.NAME;
                    }
                    sortKeyBtn.setMessage(Text.literal(sortKeyLabel()));
                    filterAndSort();
                }).dimensions(8, sortRowY, btnW1, btnH).build());

        sortDirBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal(sortDirLabel()),
                b -> {
                    sortAsc = !sortAsc;
                    sortDirBtn.setMessage(Text.literal(sortDirLabel()));
                    filterAndSort();
                }).dimensions(8 + btnW1 + 6, sortRowY, btnW2, btnH).build());

        // Initial filter + sort
        filterAndSort();

        // Back button
        int bw = 100, bh = 20;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                b -> MinecraftClient.getInstance().setScreen(parent))
                .dimensions(this.width/2 - bw/2, this.height - 28, bw, bh).build());

        // Open Mods Folder
        int omw = 160;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Mods Folder"),
                b -> {
                    try {
                        Path mods = this.realModsDir;
                        Files.createDirectories(mods);
                        MinecraftClient.getInstance().keyboard.setClipboard(mods.toString());
                        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                        if (os.contains("win")) {
                            new ProcessBuilder("explorer.exe", mods.toString()).start();
                        } else if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().open(mods.toFile());
                        }
                        toast("Opened mods folder");
                    } catch (Exception e) {
                        System.out.println("[SimpleModMenu] Failed to open mods folder: " + e);
                        toast("Failed to open folder");
                    }
                })
                .dimensions(this.width - omw - 8, this.height - 28, omw, bh).build());

        // --- QoL buttons ---
        int y = this.height - 28 - 24; // one row above Back
        int spacing = 6;
        int wCfg = 100, wReveal = 110, wSmall = 90;

        int center = this.width / 2;

        // Configure: centered above Back
        configureBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Configure"),
                b -> openConfigForSelected())
                .dimensions(center - wCfg / 2, y, wCfg, bh).build());

        // Reveal File: to the right of Configure
        revealBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Reveal File"),
                b -> revealSelectedOnDisk())
                .dimensions(center + wCfg / 2 + spacing, y, wReveal, bh).build());

        // Copy ID + Version: to the left of Configure
        copyVerBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy Version"),
                b -> copySelectedVersion())
                .dimensions(center - wCfg / 2 - spacing - 120, y, 120, bh).build());

        copyIdBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy ID"),
                b -> copySelectedId())
                .dimensions(center - wCfg / 2 - spacing - 120 - spacing - wSmall, y, wSmall, bh).build());

        // Export JSON — placed left of "Copy ID"
        int exportW = 110;
        exportJsonBtn = this.addDrawableChild(ButtonWidget.builder(Text.literal("Export JSON"),
                b -> exportFilteredToJson())
                .dimensions(center - wCfg / 2 - spacing - 120 - spacing - wSmall - spacing - exportW, y, exportW, bh)
                .build());
    }

    private String sortKeyLabel() {
        return switch (sortKey) {
            case NAME -> "Sort: Name";
            case ID -> "Sort: ID";
            case VERSION -> "Sort: Version";
        };
    }

    private String sortDirLabel() {
        return sortAsc ? "Asc ▲" : "Desc ▼";
    }

    private void filterAndSort() {
        String q = (search == null) ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<ModRow> base = new ArrayList<>(allRows);
        // filter
        if (!q.isEmpty()) {
            base = base.stream()
                    .filter(r -> r.nameL.contains(q) || r.idL.contains(q) || r.versionL.contains(q))
                    .collect(Collectors.toList());
        }
        // sort
        Comparator<ModRow> cmp = switch (sortKey) {
            case NAME -> Comparator.comparing(r -> r.nameL);
            case ID -> Comparator.comparing(r -> r.idL);
            case VERSION -> Comparator.comparing(r -> r.versionL);
        };
        if (!sortAsc) cmp = cmp.reversed();
        base.sort(cmp);

        this.filtered = base;
        this.scrollY = 0;
        this.hoverIndex = -1;

        // Keep selection sane
        if (this.selectedIndex >= filtered.size()) {
            this.selectedIndex = filtered.isEmpty() ? -1 : filtered.size() - 1;
        }
    }

    private boolean isFromRealModsDir(ModContainer mc) {
        try {
            return mc.getOrigin().getPaths().stream().anyMatch(p -> {
                Path abs = p.toAbsolutePath().normalize();
                // Only count mods physically inside the real mods dir
                return abs.startsWith(this.realModsDir);
            });
        } catch (Throwable t) {
            return false;
        }
    }

    private void scrollBy(int delta) {
        int top = LIST_TOP_PAD;
        int bottom = this.height - FOOTER_H;
        int viewportH = Math.max(0, bottom - top);

        int contentH = filtered.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - viewportH);

        scrollY += delta;
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScroll) scrollY = maxScroll;
    }

    // NOTE: no @Override here (mappings vary across versions)
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollBy((int) (-amount * 24));
        return true;
    }

    // NOTE: no @Override here (mappings vary across versions)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click-to-select within list pane
        int top = LIST_TOP_PAD;
        int bottom = this.height - FOOTER_H;
        int detailPaneW = Math.max(260, this.width / 3);
        int detailLeft = this.width - detailPaneW - 8;
        int listLeft = 8;
        int listRight = Math.max(detailLeft - 6, listLeft + 100);

        if (mouseX >= listLeft && mouseX <= listRight && mouseY >= top && mouseY <= bottom) {
            int relativeY = (int) mouseY - top + scrollY;
            int idx = relativeY / ROW_H;
            if (idx >= 0 && idx < filtered.size()) {
                selectedIndex = idx;
            }
            return true;
        }

        // Don’t call super.mouseClicked(double,double,int) — signature differs in some mappings.
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Simple dark backdrop (no blur)
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        // Title + search
        ctx.drawTextWithShadow(this.textRenderer, this.getTitle(), 8, 2, 0xFFFFFFFF);
        this.search.render(ctx, mouseX, mouseY, delta);

        // Split layout: list on left, details on right
        int detailPaneW = Math.max(260, this.width / 3); // right pane width
        int detailLeft = this.width - detailPaneW - 8;
        int listLeft = 8;
        int listRight = Math.max(detailLeft - 6, listLeft + 100);
        int top = LIST_TOP_PAD;
        int bottom = this.height - FOOTER_H;

        // Panels
        ctx.fill(listLeft, top, listRight, bottom, 0x99000000);
        ctx.fill(detailLeft, top, this.width - 8, bottom, 0x66000000);

        hoverIndex = -1;

        // LIST
        if (filtered.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer,
                    "No mod jars in mods folder.",
                    listLeft + 8, top + 8, 0xFFFFAAAA);
        } else {
            int yStart = top - scrollY;
            for (int i = 0; i < filtered.size(); i++) {
                int rowTop = yStart + i * ROW_H;
                int rowBottom = rowTop + ROW_H;
                if (rowBottom < top || rowTop > bottom) continue;

                boolean insideList =
                        mouseX >= listLeft && mouseX <= listRight &&
                                mouseY >= rowTop && mouseY <= rowBottom;
                if (insideList) hoverIndex = i;

                boolean isSelected = (i == selectedIndex);
                int bg = isSelected
                        ? 0x5533AAFF
                        : (insideList ? 0x33FFFFFF : 0x22000000);
                ctx.fill(listLeft + 1, rowTop, listRight - 1, rowBottom - 1, bg);

                ModRow r = filtered.get(i);
                int iconX = listLeft + 3;
                int iconY = rowTop + 1;

                // Placeholder "logo square" (18x18) + initials
                ctx.fill(iconX, iconY, iconX + 18, iconY + 18, 0xFF444444);
                ctx.drawTextWithShadow(this.textRenderer, initials(r.name), iconX + 3, iconY + 4, 0xFFEFEFEF);

                // Text columns
                int textStart = iconX + 18 + 4;
                int ty = rowTop + 6;

                ctx.drawTextWithShadow(this.textRenderer, r.name, textStart, ty, 0xFFFFFFFF);

                int idX = Math.max(textStart + 6 + this.textRenderer.getWidth(r.name), listLeft + 220);
                ctx.drawTextWithShadow(this.textRenderer, r.id, idX, ty, 0xFFDDDDDD);

                int verX = Math.max(idX + 6 + this.textRenderer.getWidth(r.id), listLeft + 380);
                ctx.drawTextWithShadow(this.textRenderer, r.version, verX, ty, 0xFFA0FFA0);
            }
        }

        // DETAILS PANE (right side) — builds linkSpans every frame
        linkSpans.clear();
        drawDetailsPane(ctx, detailLeft, top, detailPaneW, bottom);

        // ---- CLICK EDGE DETECTION ----
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        if (mouseDown && !prevMouseDown) {
            // 1) If click is on a link, open it.
            for (LinkSpan s : linkSpans) {
                if (s.contains(mouseX, mouseY)) {
                    openUrl(s.url);
                    mouseDown = true;
                    prevMouseDown = true;
                    break;
                }
            }
            // 2) If not on link but on a list row, select it
            if (!linkHit(mouseX, mouseY) && hoverIndex >= 0 && hoverIndex < filtered.size()) {
                selectedIndex = hoverIndex;
            }
        }
        prevMouseDown = mouseDown;

        // Footer line + count
        ctx.fill(listLeft, bottom, this.width - 8, bottom + 1, 0x77FFFFFF);
        String count = "Showing " + filtered.size() + " / " + allRows.size() + " mods";
        ctx.drawTextWithShadow(this.textRenderer, count, 8, this.height - 28 - 14, 0xFFAAAAAA);

        // Enable/disable buttons based on selection and config availability
        boolean hasSelection = (selectedIndex >= 0 && selectedIndex < filtered.size());
        boolean cfgActive = false;
        if (hasSelection) {
            String modId = filtered.get(selectedIndex).id;
            try {
                Map<String, com.terraformersmc.modmenu.api.ConfigScreenFactory<?>> fx =
                        com.majed.simplemenu.integrations.ModMenuShim.collectFactoriesForMod(modId);
                cfgActive = (fx != null && !fx.isEmpty());
            } catch (Throwable ignored) {}
        }
        if (configureBtn != null) configureBtn.active = hasSelection && cfgActive;
        if (revealBtn != null)    revealBtn.active    = hasSelection;
        if (copyIdBtn != null)    copyIdBtn.active    = hasSelection;
        if (copyVerBtn != null)   copyVerBtn.active   = hasSelection;
        if (exportJsonBtn != null) exportJsonBtn.active = true;

        // Tiny toast
        if (hudMsg != null && Util.getMeasuringTimeMs() < hudUntilMs) {
            ctx.drawTextWithShadow(this.textRenderer, hudMsg, 10, this.height - 44, 0xFFECECEC);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    // ---------- Export helpers ----------
    private void exportFilteredToJson() {
        try {
            String json = buildJson(filtered);
            MinecraftClient.getInstance().keyboard.setClipboard(json);

            Path out = FabricLoader.getInstance().getGameDir().resolve("mods-list.json");
            Files.writeString(out, json);
            toast("Exported: " + out.getFileName());
        } catch (Exception e) {
            toast("Export failed");
        }
    }

    private static String buildJson(List<ModRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            ModRow r = rows.get(i);
            String path = "";
            try {
                Optional<Path> p = r.container.getOrigin().getPaths().stream().findFirst();
                path = p.map(Path::toString).orElse("");
            } catch (Throwable ignored) { }

            sb.append("  {")
              .append("\"id\":\"").append(esc(r.id)).append("\",")
              .append("\"name\":\"").append(esc(r.name)).append("\",")
              .append("\"version\":\"").append(esc(r.version)).append("\",")
              .append("\"path\":\"").append(esc(path)).append("\"")
              .append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\"') { out.append('\\').append(c); }
            else if (c == '\n') { out.append("\\n"); }
            else if (c == '\r') { out.append("\\r"); }
            else if (c == '\t') { out.append("\\t"); }
            else { out.append(c); }
        }
        return out.toString();
    }
    // ---------- /Export helpers ----------

    private void drawDetailsPane(DrawContext ctx, int left, int top, int paneW, int bottom) {
        int x = left + 8;
        int y = top + 8;

        if (selectedIndex < 0 || selectedIndex >= filtered.size()) {
            ctx.drawTextWithShadow(this.textRenderer, "Click a mod to view its details", x, y, 0xFFCCCCCC);
            return;
        }

        ModRow r = filtered.get(selectedIndex);

        // Placeholder large logo
        ctx.fill(x, y, x + 48, y + 48, 0xFF444444);
        ctx.drawTextWithShadow(this.textRenderer, initials(r.name), x + 10, y + 18, 0xFFEFEFEF);

        int tx = x + 48 + 8;
        ctx.drawTextWithShadow(this.textRenderer, r.name, tx, y + 2, 0xFFFFFFFF); y += 18;
        ctx.drawTextWithShadow(this.textRenderer, "ID: " + r.id, tx, y + 2, 0xFFDDDDDD); y += 14;
        ctx.drawTextWithShadow(this.textRenderer, "Version: " + r.version, tx, y + 2, 0xFFAAFFAA); y += 16;

        // Description
        String desc = r.meta.getDescription() == null ? "" : r.meta.getDescription().trim();
        if (!desc.isEmpty()) {
            y += 6;
            y = wrapAndDraw(ctx, desc, x, y, paneW - 16, 0xFFDDDDDD);
        }

        // Authors
        if (!r.meta.getAuthors().isEmpty()) {
            y += 8;
            String authors = r.meta.getAuthors().stream()
                    .map(a -> a.getName() == null ? "unknown" : a.getName())
                    .collect(Collectors.joining(", "));
            ctx.drawTextWithShadow(this.textRenderer, "Authors: " + authors, x, y, 0xFFCCCCFF);
            y += 12;
        }

        // Contact links — draw label + blue, underlined URL; add clickable rect
        String homepage = r.meta.getContact().get("homepage").orElse(null);
        String sources  = r.meta.getContact().get("sources").orElse(null);
        String issues   = r.meta.getContact().get("issues").orElse(null);
        String download = r.meta.getContact().get("download").orElse(null); // custom

        if ((homepage != null && !homepage.isBlank())
                || (sources  != null && !sources.isBlank())
                || (issues   != null && !issues.isBlank())
                || (download != null && !download.isBlank())) {
            y += 6;
            if (homepage != null && !homepage.isBlank()) {
                y = drawLinkLine(ctx, "Homepage: ", homepage, x, y);
            }
            if (sources != null && !sources.isBlank()) {
                y = drawLinkLine(ctx, "Sources: ", sources, x, y);
            }
            if (issues != null && !issues.isBlank()) {
                y = drawLinkLine(ctx, "Issues: ", issues, x, y);
            }
            if (download != null && !download.isBlank()) {
                y = drawLinkLine(ctx, "Download: ", download, x, y);
            }
        }

        // -------- Dependencies --------
        DepInfo deps = loadDepInfo(r);
        if (!deps.isEmpty()) {
            y += 8;
            ctx.drawTextWithShadow(this.textRenderer, "Dependencies", x, y, 0xFFFFEE99);
            y += 12;

            if (!deps.depends.isEmpty()) {
                y = wrapAndDraw(ctx, "Depends: " + String.join(", ", deps.depends), x, y, paneW - 16, 0xFFE6E6E6);
                y += 2;
            }
            if (!deps.recommends.isEmpty()) {
                y = wrapAndDraw(ctx, "Recommends: " + String.join(", ", deps.recommends), x, y, paneW - 16, 0xFFE6E6E6);
                y += 2;
            }
            if (!deps.conflicts.isEmpty()) {
                y = wrapAndDraw(ctx, "Conflicts: " + String.join(", ", deps.conflicts), x, y, paneW - 16, 0xFFFFBBBB);
                y += 2;
            }
        }
        // -----------------------------------
    }

    // Draw a label + clickable URL, underline the URL, register a hitbox, advance Y
    private int drawLinkLine(DrawContext ctx, String label, String url, int x, int y) {
        int labelColor = 0xFFAAAAFF;
        int linkColor = 0xFF55AAFF;

        ctx.drawTextWithShadow(this.textRenderer, label, x, y, labelColor);
        int urlX = x + this.textRenderer.getWidth(label);
        ctx.drawTextWithShadow(this.textRenderer, url, urlX, y, linkColor);

        int urlW = this.textRenderer.getWidth(url);
        // underline (a thin line just under the text)
        ctx.fill(urlX, y + 10, urlX + urlW, y + 11, linkColor);

        // record clickable area (roughly one line high)
        linkSpans.add(new LinkSpan(urlX, y, urlW, 12, url));

        return y + 12;
    }

    // Wrap helper
    private int wrapAndDraw(DrawContext ctx, String text, int x, int startY, int maxW, int color) {
        List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(text), Math.max(20, maxW));
        int y = startY;
        for (OrderedText line : wrapped) {
            ctx.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += 12;
        }
        return y;
    }

    // --------- Dependency reader ---------
    private static class DepInfo {
        final List<String> depends = new ArrayList<>();
        final List<String> recommends = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        boolean isEmpty() {
            return depends.isEmpty() && recommends.isEmpty() && conflicts.isEmpty();
        }
    }

    private DepInfo loadDepInfo(ModRow r) {
        DepInfo out = new DepInfo();
        try {
            Path p = primaryPath(r);
            if (p == null) return out;

            JsonObject root = readFabricJson(p);
            if (root == null) return out;

            // Keys to read. "breaks" is treated as conflicts too.
            collectIds(root, "depends", out.depends);
            collectIds(root, "recommends", out.recommends);
            collectIds(root, "conflicts", out.conflicts);
            collectIds(root, "breaks", out.conflicts);

            // Clean + dedupe in place
            dedupeInPlace(out.depends);
            dedupeInPlace(out.recommends);
            dedupeInPlace(out.conflicts);
        } catch (Throwable ignored) { }
        return out;
    }

    private static void dedupeInPlace(List<String> list) {
        if (list.isEmpty()) return;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : list) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        list.clear();
        list.addAll(set);
    }

    private static void collectIds(JsonObject root, String key, List<String> sink) {
        if (!root.has(key)) return;
        JsonElement el = root.get(key);
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                String id = e.getKey();
                String constraint = constraintToString(e.getValue());
                sink.add(constraint == null || constraint.isBlank() ? id : id + " " + constraint);
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    sink.add(e.getAsString());
                } else if (e.isJsonObject()) {
                    JsonObject obj = e.getAsJsonObject();
                    String id = obj.has("id") ? obj.get("id").getAsString() : null;
                    String c = constraintToString(obj.get("versions"));
                    if (id != null) sink.add(c == null || c.isBlank() ? id : id + " " + c);
                }
            }
        } else if (el.isJsonPrimitive()) {
            sink.add(el.getAsString());
        }
    }

    private static String constraintToString(JsonElement val) {
        if (val == null) return null;
        if (val.isJsonPrimitive()) return "(" + val.getAsString() + ")";
        if (val.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : val.getAsJsonArray()) {
                if (e.isJsonPrimitive()) parts.add(e.getAsString());
            }
            return parts.isEmpty() ? null : "(" + String.join(" & ", parts) + ")";
        }
        return null;
    }

    private static JsonObject readFabricJson(Path origin) {
        try {
            if (Files.isDirectory(origin)) {
                Path fmj = origin.resolve("fabric.mod.json");
                if (!Files.exists(fmj)) return null;
                String s = Files.readString(fmj, StandardCharsets.UTF_8);
                return JsonParser.parseString(s).getAsJsonObject();
            } else {
                try (ZipFile zip = new ZipFile(origin.toFile())) {
                    ZipEntry e = zip.getEntry("fabric.mod.json");
                    if (e == null) return null;
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                        return JsonParser.parseString(sb.toString()).getAsJsonObject();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
    // --------- /Dependency reader ---------

    private void openConfigForSelected() {
        if (selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        String modId = filtered.get(selectedIndex).id;
        try {
            Map<String, com.terraformersmc.modmenu.api.ConfigScreenFactory<?>> fx =
                    com.majed.simplemenu.integrations.ModMenuShim.collectFactoriesForMod(modId);
            if (fx != null && !fx.isEmpty()) {
                com.terraformersmc.modmenu.api.ConfigScreenFactory<?> f = fx.values().iterator().next();
                Screen cfg = f.create(this);
                if (cfg != null) MinecraftClient.getInstance().setScreen(cfg);
            } else {
                toast("No in-game config for this mod");
            }
        } catch (Throwable t) {
            toast("Failed to open config");
        }
    }

    private void revealSelectedOnDisk() {
        if (selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        Path p = primaryPath(filtered.get(selectedIndex));
        if (p == null) { toast("No file for this mod"); return; }
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            File file = p.toFile();
            if (os.contains("win")) {
                if (file.isFile()) {
                    new ProcessBuilder("explorer.exe", "/select," + file.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("explorer.exe", file.getAbsolutePath()).start();
                }
            } else if (os.contains("mac")) {
                if (file.isFile()) {
                    new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("open", file.getAbsolutePath()).start();
                }
            } else { // linux/unix
                new ProcessBuilder("xdg-open", (file.isFile() ? file.getParentFile() : file).getAbsolutePath()).start();
            }
            toast("Revealed on disk");
        } catch (Exception e) {
            toast("Failed to reveal file");
        }
    }

    private void copySelectedId() {
        if (selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        String id = filtered.get(selectedIndex).id;
        MinecraftClient.getInstance().keyboard.setClipboard(id);
        toast("Copied ID: " + id);
    }

    private void copySelectedVersion() {
        if (selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        String v = filtered.get(selectedIndex).version;
        MinecraftClient.getInstance().keyboard.setClipboard(v);
        toast("Copied version: " + v);
    }

    private void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            // Minecraft helper (uses OS)
            Util.getOperatingSystem().open(url);
            toast("Opening: " + url);
        } catch (Throwable t1) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI(url));
                    toast("Opening: " + url);
                    return;
                }
            } catch (Throwable ignored) {}
            // Fallback: copy to clipboard
            MinecraftClient.getInstance().keyboard.setClipboard(url);
            toast("Link copied to clipboard");
        }
    }

    private boolean linkHit(int mx, int my) {
        for (LinkSpan s : linkSpans) {
            if (s.contains(mx, my)) return true;
        }
        return false;
    }

    private void toast(String msg) {
        this.hudMsg = msg;
        this.hudUntilMs = Util.getMeasuringTimeMs() + 2000; // 2s
    }

    private Path primaryPath(ModRow r) {
        try {
            // Prefer something inside the real mods dir, otherwise first origin path
            Optional<Path> insideMods = r.container.getOrigin().getPaths().stream()
                    .map(Path::toAbsolutePath).map(Path::normalize)
                    .filter(p -> p.startsWith(this.realModsDir)).findFirst();
            if (insideMods.isPresent()) return insideMods.get();

            return r.container.getOrigin().getPaths().stream().findFirst().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        String a = parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        String b = parts.length > 1 ? parts[1].substring(0, 1).toUpperCase(Locale.ROOT) : "";
        return (a + b);
    }

    // Prefer the user's real .minecraft\mods on Windows; fallback to dev run\mods
    private static Path resolvePreferredModsDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA"); // e.g. C:\Users\<you>\AppData\Roaming
            if (appdata != null && !appdata.isBlank()) {
                return Paths.get(appdata, ".minecraft", "mods");
            }
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        return gameDir.resolve("mods");
    }

    /** Public so other screens could reference if needed. */
    public static class ModRow {
        public final ModContainer container;
        public final ModMetadata meta;

        public final String name;
        public final String id;
        public final String version;

        public final String nameL;
        public final String idL;
        public final String versionL;

        ModRow(ModContainer container, ModMetadata m) {
            this.container = container;
            this.meta = m;
            this.name = safe(m.getName());
            this.id = safe(m.getId());
            this.version = safe(m.getVersion() != null ? m.getVersion().getFriendlyString() : "unknown");

            this.nameL = name.toLowerCase(Locale.ROOT);
            this.idL = id.toLowerCase(Locale.ROOT);
            this.versionL = version.toLowerCase(Locale.ROOT);
        }

        private static String safe(String s) { return s == null ? "unknown" : s; }
    }

    // Simple clickable rectangle for links
    private static class LinkSpan {
        final int x, y, w, h;
        final String url;
        LinkSpan(int x, int y, int w, int h, String url) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.url = url;
        }
        boolean contains(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
