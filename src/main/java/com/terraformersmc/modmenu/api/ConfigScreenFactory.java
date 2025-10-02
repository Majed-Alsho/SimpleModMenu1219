package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screen.Screen;

/**
 * Minimal stub of Mod Menu's ConfigScreenFactory.
 * Mods compiled against Mod Menu will link to this at runtime.
 */
@FunctionalInterface
public interface ConfigScreenFactory<T extends Screen> {
    Screen create(Screen parent);
}
