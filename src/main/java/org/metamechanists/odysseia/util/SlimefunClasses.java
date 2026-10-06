package org.metamechanists.odysseia.util;

import java.util.List;

/**
 * Resuelve las clases de Slimefun que Odysseia usa por reflexion.
 *
 * En 26.x corre el Slimefun universal (io.github.thebusybiscuit.slimefun4 y el BlockStorage
 * legado en me.mrCookieSlime); el fork propio de 1.21.11 usa com.github.drakescraft_labs.
 * Buscar solo uno de los dos dejaba inertes el limite de spawners, el laboratorio, /sell y
 * la proteccion de FastMachines. Se prueba primero el upstream universal.
 */
public final class SlimefunClasses {
    static final List<String> SLIMEFUN_ITEM = List.of(
            "io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem",
            "com.github.drakescraft_labs.slimefun4.api.items.SlimefunItem");
    static final List<String> SLIMEFUN_ADDON = List.of(
            "io.github.thebusybiscuit.slimefun4.api.SlimefunAddon",
            "com.github.drakescraft_labs.slimefun4.api.SlimefunAddon");
    static final List<String> BLOCK_STORAGE = List.of(
            "me.mrCookieSlime.Slimefun.api.BlockStorage",
            "com.github.drakescraft_labs.slimefun4.legacy.api.BlockStorage");
    /**
     * Paquetes base para la guia. El core de Dallas trae shims io.github para SlimefunItem y
     * SlimefunAddon, pero no para SlimefunGuide/SlimefunGuideMode: modo y guia deben salir del
     * mismo paquete o Enum.valueOf y getItem no encajan.
     */
    static final List<String> GUIDE_BASES = List.of(
            "io.github.thebusybiscuit.slimefun4.core.guide",
            "com.github.drakescraft_labs.slimefun4.core.guide");

    private SlimefunClasses() {
    }

    public static Class<?> slimefunItem() throws ClassNotFoundException {
        return first(SLIMEFUN_ITEM);
    }

    public static Class<?> slimefunAddon() throws ClassNotFoundException {
        return first(SLIMEFUN_ADDON);
    }

    public static Class<?> blockStorage() throws ClassNotFoundException {
        return first(BLOCK_STORAGE);
    }

    /** Devuelve {SlimefunGuideMode, SlimefunGuide} del primer paquete que tenga ambas. */
    public static Class<?>[] guide() throws ClassNotFoundException {
        return firstPair(GUIDE_BASES, "SlimefunGuideMode", "SlimefunGuide");
    }

    /** Visible para pruebas: el primer paquete base donde cargan las dos clases. */
    static Class<?>[] firstPair(List<String> bases, String first, String second) throws ClassNotFoundException {
        for (String base : bases) {
            try {
                return new Class<?>[] {Class.forName(base + "." + first), Class.forName(base + "." + second)};
            } catch (ClassNotFoundException ignored) {
                // Se prueba el siguiente paquete.
            }
        }
        throw new ClassNotFoundException(String.join(" | ", bases) + " (" + first + ", " + second + ")");
    }

    /** Visible para pruebas: devuelve la primera clase cargable de la lista. */
    static Class<?> first(List<String> candidates) throws ClassNotFoundException {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // Se prueba el siguiente paquete.
            }
        }
        throw new ClassNotFoundException(String.join(" | ", candidates));
    }
}
