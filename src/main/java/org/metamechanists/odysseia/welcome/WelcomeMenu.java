package org.metamechanists.odysseia.welcome;

import java.util.Locale;

/**
 * Reglas puras del menu de bienvenida: que dialogo corresponde a que idioma del cliente.
 *
 * El servidor es hispanohablante; el ingles existe porque llegan jugadores de fuera, no porque
 * sea un objetivo. Por eso el espanol es el valor por defecto de todo lo que se parezca a
 * hispano o portugues (se entiende), y el ingles solo para el resto.
 */
public final class WelcomeMenu {
    public static final String NAMESPACE = "drakescraft";
    public static final String SPANISH = "welcome_es";
    public static final String ENGLISH = "welcome_en";

    private WelcomeMenu() {
    }

    /** Dialogo que corresponde al idioma del cliente, p. ej. {@code es_cl}, {@code en_us}. */
    public static String dialogFor(String clientLocale) {
        if (clientLocale == null || clientLocale.isBlank()) {
            return SPANISH;
        }
        String language = clientLocale.toLowerCase(Locale.ROOT).replace('-', '_').split("_")[0];
        return switch (language) {
            case "es", "pt", "ca", "gl", "eu" -> SPANISH;
            default -> ENGLISH;
        };
    }

    /** Acepta {@code es} o {@code en} (y sus nombres) para forzar el idioma con el comando. */
    public static String dialogForArgument(String argument) {
        if (argument == null) {
            return null;
        }
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "es", "esp", "spanish", "espanol" -> SPANISH;
            case "en", "eng", "english", "ingles" -> ENGLISH;
            default -> null;
        };
    }
}
