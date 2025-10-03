package com.majed.simplemenu;

import com.majed.simplemenu.ui.ModsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class SimpleModMenuClient implements ClientModInitializer {

    private static KeyBinding openModsKey;

    @Override
    public void onInitializeClient() {
        // 1.21 mappings: category is an enum, not a String.
        openModsKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.simple_mod_menu.open",          // translation key
                        InputUtil.Type.KEYSYM,               // key type
                        GLFW.GLFW_KEY_F9,                    // default key
                        KeyBinding.Category.MISC             // <- enum category
                )
        );

        // Open Mods screen when the key is pressed
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openModsKey.wasPressed()) {
                if (client == null) return;
                if (client.currentScreen == null) {
                    client.setScreen(new ModsScreen(null));
                } else {
                    client.setScreen(new ModsScreen(client.currentScreen));
                }
            }
        });
    }
}
