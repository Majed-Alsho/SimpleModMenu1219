package com.majed.simplemenu.mixin;

import com.majed.simplemenu.ui.ModsScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    // Add a "Mods" button after vanilla lays out its widgets
    @Inject(method = "init", at = @At("TAIL"))
    private void simpleMenu$addModsButton(CallbackInfo ci) {
        final int bw = 80, bh = 20;
        // bottom-left margin
        int x = 8;
        int y = this.height - 28;

        this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Mods"), b ->
                MinecraftClient.getInstance().setScreen(new ModsScreen(this)))
            .dimensions(x, y, bw, bh)
            .build()
        );
    }

    // (Optional) tiny "Mods" watermark so we know mixin is active; harmless if you keep it.
    @Inject(method = "render", at = @At("TAIL"))
    private void simpleMenu$renderBadge(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        String s = "Simple Mod Menu";
        int w = this.textRenderer.getWidth(s);
        ctx.drawTextWithShadow(this.textRenderer, s, this.width - w - 6, 6, 0xFFAAAAAA);
    }
}
