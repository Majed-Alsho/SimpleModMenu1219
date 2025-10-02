package com.terraformersmc.modmenu.api;

/**
 * Minimal stub of Mod Menu's API.
 * We only need the config factory.
 */
public interface ModMenuApi {
    ConfigScreenFactory<?> getModConfigScreenFactory();
}
