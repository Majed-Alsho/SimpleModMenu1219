package com.majed.simplemenu.integrations;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helper that discovers config screen factories exposed via the Mod Menu API.
 * Compatible with Mod Menu builds that ONLY expose getModConfigScreenFactory() AND
 * those that ALSO expose getProvidedConfigScreenFactories() (if present).
 */
public final class ModMenuShim {
    private ModMenuShim() {}

    /**
     * Returns a map of factories keyed by mod id for a single mod id.
     * If the Mod Menu entrypoint exposes a single factory, it will be
     * returned under the provider's mod id.
     */
    public static Map<String, ConfigScreenFactory<?>> collectFactoriesForMod(String modId) {
        Map<String, ConfigScreenFactory<?>> out = new HashMap<>();

        List<EntrypointContainer<Object>> eps =
                FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class);

        for (EntrypointContainer<Object> c : eps) {
            String providerId = c.getProvider().getMetadata().getId();
            if (!providerId.equals(modId)) continue;

            Object ep = safeGetEntrypoint(c);
            if (ep == null) continue;

            if (ep instanceof ModMenuApi api) {
                // Always try the single factory
                try {
                    ConfigScreenFactory<?> f = api.getModConfigScreenFactory();
                    if (f != null) out.put(providerId, f);
                } catch (Throwable ignored) {}

                // Try optional "provided map" (not present in all versions)
                putProvidedMapIfPresent(ep, out);
            } else if (ep instanceof ConfigScreenFactory<?>) {
                out.put(providerId, (ConfigScreenFactory<?>) ep);
            } else {
                // Last resort reflection for single-factory method
                try {
                    Method m = ep.getClass().getMethod("getModConfigScreenFactory");
                    Object res = m.invoke(ep);
                    if (res instanceof ConfigScreenFactory<?> f) {
                        out.put(providerId, f);
                    }
                } catch (Throwable ignored) {}

                // And optional provided map via reflection
                putProvidedMapIfPresent(ep, out);
            }
        }
        return out;
    }

    /**
     * Returns a map of factories from ALL providers keyed by provider mod id.
     */
    public static Map<String, ConfigScreenFactory<?>> collectAllFactories() {
        Map<String, ConfigScreenFactory<?>> out = new HashMap<>();

        List<EntrypointContainer<Object>> eps =
                FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class);

        for (EntrypointContainer<Object> c : eps) {
            String providerId = c.getProvider().getMetadata().getId();
            Object ep = safeGetEntrypoint(c);
            if (ep == null) continue;

            if (ep instanceof ModMenuApi api) {
                try {
                    ConfigScreenFactory<?> f = api.getModConfigScreenFactory();
                    if (f != null) out.put(providerId, f);
                } catch (Throwable ignored) {}

                putProvidedMapIfPresent(ep, out);
            } else if (ep instanceof ConfigScreenFactory<?>) {
                out.put(providerId, (ConfigScreenFactory<?>) ep);
            } else {
                try {
                    Method m = ep.getClass().getMethod("getModConfigScreenFactory");
                    Object res = m.invoke(ep);
                    if (res instanceof ConfigScreenFactory<?> f) {
                        out.put(providerId, f);
                    }
                } catch (Throwable ignored) {}

                putProvidedMapIfPresent(ep, out);
            }
        }
        return out;
    }

    // ---- helpers ------------------------------------------------------------

    private static Object safeGetEntrypoint(EntrypointContainer<Object> c) {
        try {
            return c.getEntrypoint();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * If the entrypoint class has a method named "getProvidedConfigScreenFactories"
     * that returns a Map<String, ConfigScreenFactory<?>>, merge it into 'out'.
     * This method is ABSENT in some Mod Menu versions; we use reflection and ignore failures.
     */
    @SuppressWarnings("unchecked")
    private static void putProvidedMapIfPresent(Object ep, Map<String, ConfigScreenFactory<?>> out) {
        try {
            Method m = ep.getClass().getMethod("getProvidedConfigScreenFactories");
            Object res = m.invoke(ep);
            if (res instanceof Map<?, ?> mp) {
                for (Map.Entry<?, ?> e : mp.entrySet()) {
                    Object k = e.getKey();
                    Object v = e.getValue();
                    if (k instanceof String key && v instanceof ConfigScreenFactory<?> f) {
                        out.put(key, f);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Method not present or incompatible — that's fine.
        }
    }
}
