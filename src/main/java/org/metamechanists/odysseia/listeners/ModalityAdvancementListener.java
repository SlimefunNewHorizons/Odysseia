package org.metamechanists.odysseia.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.metamechanists.odysseia.modalities.Modality;
import org.metamechanists.odysseia.modalities.ModalityService;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Atribuye cada avance legítimo a la modalidad donde ocurrió.
 *
 * El progreso vanilla sigue siendo global a propósito: restaurarlo por mundo volvería a disparar
 * criterios y recompensas. Esta capa solo cambia el anuncio y deja una explicación inequívoca en
 * chat cuando un avance se obtiene en una modalidad concreta.
 */
public final class ModalityAdvancementListener implements Listener {

    private final ModalityService modalities;

    public ModalityAdvancementListener(ModalityService modalities) {
        this.modalities = modalities;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        // null significa que Minecraft decidió no anunciarlo (recetas y avances ocultos).
        if (event.message() == null || event.getAdvancement().getDisplay() == null) return;

        // Los logros vanilla (minecraft:*) no se anuncian: solo los de Slimefun y addons.
        if (!esAvanceExterno(event.getAdvancement().getKey().getNamespace())) {
            event.message(null);
            return;
        }

        Modality modality = modalities.resolve(event.getPlayer());
        Component player = Component.text(event.getPlayer().getName(), NamedTextColor.YELLOW);
        Component title = event.getAdvancement().getDisplay().title();
        Component mode = LegacyComponentSerializer.legacyAmpersand().deserialize(modality.displayName());
        String phrase = phrase(event.getAdvancement().getDisplay().frame());

        Component announcement = Component.text("✦ ", NamedTextColor.GOLD)
                .append(player)
                .append(Component.text(" " + phrase + " [", NamedTextColor.GRAY))
                .append(title)
                .append(Component.text("] en ", NamedTextColor.GRAY))
                .append(mode)
                .append(Component.text(".", NamedTextColor.GRAY));

        /*
         * Los avances de Slimefun y addons se emiten como anuncios globales. Clásico no
         * instala ni usa ese contenido: mostrarle el aviso es una fuga visual, no una
         * recompensa. Paper no permite filtrar receptores desde el mensaje del evento,
         * así que cancelamos sólo ese anuncio no vanilla y lo reenviamos a las otras
         * modalidades. Los avances minecraft:* conservan el broadcast habitual.
         */
        if (esAvanceExterno(event.getAdvancement().getKey().getNamespace())) {
            event.message(null);
            Bukkit.getOnlinePlayers().stream()
                    .filter(recipient -> !esClasico(modalities.resolve(recipient)))
                    .forEach(recipient -> recipient.sendMessage(announcement));
            return;
        }

        event.message(announcement);
    }

    /** Los avances con namespace de Minecraft son parte de la experiencia vanilla de Clásico. */
    static boolean esAvanceExterno(String namespace) {
        return namespace != null && !"minecraft".equalsIgnoreCase(namespace);
    }

    /** Centraliza la comparación para no depender del texto visible y coloreado de la modalidad. */
    static boolean esClasico(Modality modality) {
        return modality != null && "clasico".equalsIgnoreCase(modality.id());
    }

    static String phrase(AdvancementDisplay.Frame type) {
        List<String> options = switch (type) {
            case CHALLENGE -> List.of(
                    "rompió la lógica del juego y completó",
                    "hizo sudar al servidor completando",
                    "convirtió lo improbable en historial con");
            case GOAL -> List.of(
                    "se puso una meta y, contra todo pronóstico, logró",
                    "tachó de su lista",
                    "llegó con vida hasta");
            default -> List.of(
                    "desbloqueó",
                    "sumó a su colección",
                    "descubrió que también existía");
        };
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }
}
