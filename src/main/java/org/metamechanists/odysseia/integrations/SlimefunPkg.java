package org.metamechanists.odysseia.integrations;

/**
 * Slimefun vive en {@code com.github.drakescraft_labs.slimefun4} (fork de Dallas, 1.21.11) y en {@code io.github.thebusybiscuit.slimefun4}
 * (Slimefun universal de la Temporada 2, 26.x). Resuelve las clases con el paquete que exista en el servidor.
 */
public final class SlimefunPkg {

    private static final String[] BASES = { "com.github.drakescraft_labs.slimefun4", "io.github.thebusybiscuit.slimefun4" };

    private SlimefunPkg() {
    }

    /** {@code forName("api.items.SlimefunItem")} carga la clase desde el paquete base presente. */
    public static Class<?> forName(String relativa) throws ClassNotFoundException {
        ClassNotFoundException ultimo = null;
        for (String base : BASES) {
            try {
                return Class.forName(base + "." + relativa);
            } catch (ClassNotFoundException e) {
                ultimo = e;
            }
        }
        throw ultimo;
    }
}
