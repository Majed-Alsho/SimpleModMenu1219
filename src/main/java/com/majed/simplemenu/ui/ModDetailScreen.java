package com.majed.simplemenu.ui;

import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.stream.Collectors;

public class ModDetailScreen extends Screen {
    private final Screen parent;
    private final ModsScreen.ModRow row; // keep ModRow (original)

    public ModDetailScreen(Screen parent, ModsScreen.ModRow row) {
        super(Text.literal(row.name));
        this.parent = parent;
        this.row = row;
    }

    @Override
    protected void init() {
        int bw = 100, bh = 20;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                b -> MinecraftClient.getInstance().setScreen(parent))
            .dimensions(this.width / 2 - bw / 2, this.height - 28, bw, bh).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // dark backdrop
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        int y = 10;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(row.name), 10, y, 0xFFFFFF); y += 14;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("ID: " + row.id), 10, y, 0xDDDDDD); y += 12;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Version: " + row.version), 10, y, 0xDDFFDD); y += 14;

        // Description (wrapped)
        String desc = row.meta.getDescription() == null ? "" : row.meta.getDescription().trim();
        if (!desc.isEmpty()) {
            y += 6;
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(desc), this.width - 20);
            for (OrderedText line : wrapped) {
                ctx.drawTextWithShadow(this.textRenderer, line, 10, y, 0xDDDDDD);
                y += 12;
            }
        }

        // Authors
        if (!row.meta.getAuthors().isEmpty()) {
            y += 10;
            String authors = row.meta.getAuthors().stream()
                    .map(Person::getName)
                    .map(n -> (n == null || n.isBlank()) ? "unknown" : n)
                    .collect(Collectors.joining(", "));
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Authors: " + authors), 10, y, 0xCCCCFF);
            y += 12;
        }

        // Contact (homepage/source/issues) — unwrap Optionals safely for current mappings
        String homepage = row.meta.getContact().get("homepage").orElse(null);
        String sources  = row.meta.getContact().get("sources").orElse(null);
        String issues   = row.meta.getContact().get("issues").orElse(null);

        if ((homepage != null && !homepage.isBlank()) ||
            (sources  != null && !sources.isBlank())  ||
            (issues   != null && !issues.isBlank())) {

            y += 6;
            if (homepage != null && !homepage.isBlank()) {
                ctx.drawTextWithShadow(this.textRenderer, Text.literal("Homepage: " + homepage), 10, y, 0xAAAAFF);
                y += 12;
            }
            if (sources != null && !sources.isBlank()) {
                ctx.drawTextWithShadow(this.textRenderer, Text.literal("Sources: " + sources), 10, y, 0xAAAAFF);
                y += 12;
            }
            if (issues != null && !issues.isBlank()) {
                ctx.drawTextWithShadow(this.textRenderer, Text.literal("Issues: " + issues), 10, y, 0xAAAAFF);
                y += 12;
            }
        }

        super.render(ctx, mouseX, mouseY, delta);
    }
}
