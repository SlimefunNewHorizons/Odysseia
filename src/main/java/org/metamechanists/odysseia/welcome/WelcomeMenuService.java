package org.metamechanists.odysseia.welcome;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.listeners.ResourcePackListener;

/**
 * Abre el menu de bienvenida: un dialogo nativo a pantalla completa con tres botones grandes
 * (Slimefun, SkyBlock, OneBlock).
 *
 * Los dialogos viven en el datapack DrakesCraft (generados desde scripts/generate_dialogs.py),
 * asi que cambiar el texto no pide recompilar este plugin. Bedrock no dibuja dialogos de Java:
 * a esos jugadores se les abre el menu de /modalidades, que es un inventario y funciona en ambos.
 */
public final class WelcomeMenuService {
    private final Odysseia plugin;

    public WelcomeMenuService(Odysseia plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("welcome-menu.enabled", true);
    }

    /** Abre el menu en el idioma del cliente. */
    public void show(Player player) {
        show(player, WelcomeMenu.dialogFor(player.locale().toString()));
    }

    /** Abre un dialogo concreto por nombre (p. ej. {@code welcome_en}). */
    public void show(Player player, String dialogName) {
        if (!player.isOnline()) {
            return;
        }
        if (ResourcePackListener.isBedrock(player)) {
            player.performCommand("modalidades");
            return;
        }
        try {
            Dialog dialog = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.DIALOG)
                    .get(Key.key(WelcomeMenu.NAMESPACE, dialogName));
            if (dialog == null) {
                plugin.getLogger().warning("[Welcome] El dialogo " + WelcomeMenu.NAMESPACE + ":" + dialogName
                        + " no existe. Falta el datapack DrakesCraft o no esta actualizado; se abre /modalidades.");
                player.performCommand("modalidades");
                return;
            }
            player.showDialog(dialog);
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().warning("[Welcome] No se pudo abrir el dialogo para " + player.getName()
                    + ": " + error.getMessage());
            player.performCommand("modalidades");
        }
    }
}
