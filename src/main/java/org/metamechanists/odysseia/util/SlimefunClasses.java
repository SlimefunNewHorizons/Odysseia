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
