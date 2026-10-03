package org.metamechanists.odysseia.laboratorio;

import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import org.metamechanists.odysseia.util.SlimefunClasses;

/**
 * Tope de bloques de Slimefun por chunk, solo dentro del laboratorio.
 *
 * En el mundo normal la densidad de maquinas la limita el coste de construirlas: hay que fundir,
 * cablear y alimentar cada una. En un creativo con /sf cheat ese freno no existe, y nada impide
 * llenar un chunk de reactores "a ver que pasa". Lo que pasa es que el ticker de Slimefun es
 * global, asi que un chunk saturado en el mundo de pruebas se lleva por delante el TPS de las
 * partidas de todo el mundo. El tope existe para que el laboratorio no pueda hacer eso.
 *
 * Deliberadamente NO se aplica fuera del laboratorio: ahi las bases grandes son el resultado de
 * horas de juego y ponerles un techo retroactivo seria romperlas.
 *
 * Se cuenta el total de bloques de Slimefun del chunk, no solo los que tickean. Distinguirlos
 * exigiria hurgar en el registro de tickers por reflexion para afinar un numero que aqui da igual:
 * en un mundo de pruebas, el tope se pone lo bastante alto como para no estorbar a nadie que este
 * probando de verdad, y lo bastante bajo como para frenar al que apila mil.
 */
public final class SandboxChunkLimitListener implements Listener {

    private static final String BYPASS = "odysseia.laboratorio.sinlimite";

    private final JavaPlugin plugin;
    private final Set<String> sandboxWorlds;
    private final Method getLocations;
    private final Method checkId;

    public SandboxChunkLimitListener(JavaPlugin plugin, Set<String> sandboxWorlds) {
        this.plugin = plugin;
        this.sandboxWorlds = sandboxWorlds;

        Method locations = null;
        Method check = null;
        try {
            Class<?> blockStorage = SlimefunClasses.blockStorage();
            locations = blockStorage.getMethod("getLocations", Chunk.class);
            check = blockStorage.getMethod("checkID", Location.class);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("[Laboratorio] Slimefun no disponible; el tope por chunk queda inactivo.");
        }
        this.getLocations = locations;
        this.checkId = check;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (getLocations == null) return;

        Chunk chunk = event.getBlockPlaced().getChunk();
        if (!sandboxWorlds.contains(chunk.getWorld().getName().toLowerCase(Locale.ROOT))) return;

        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;

        // Solo interesa frenar bloques de Slimefun; la construccion decorativa es libre.
        if (idDelBloque(event.getBlockPlaced().getLocation()) == null
                && !esItemSlimefun(event)) {
            return;
        }

        int maximo = Math.max(1, plugin.getConfig().getInt("modalidades.laboratorio.max-slimefun-por-chunk", 120));
        int actual = contarEnChunk(chunk);
        if (actual < maximo) return;

        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&6DrakesCraft &8· &7Este chunk ya tiene &e" + actual + "&7 bloques de Slimefun, el tope del "
                        + "laboratorio.&r\n&7Reparte las maquinas en chunks vecinos: el ticker es compartido "
                        + "con el resto del servidor."));
    }

    /**
     * Si el item que se coloca es de Slimefun.
     *
     * Se mira el item en la mano porque en LOWEST el bloque aun no esta registrado en el almacen
     * de Slimefun: preguntarle a BlockStorage por la ubicacion recien ocupada daria null siempre.
     */
    private boolean esItemSlimefun(BlockPlaceEvent event) {
        try {
            Class<?> slimefunItem = SlimefunClasses.slimefunItem();
            Object item = slimefunItem.getMethod("getByItem", org.bukkit.inventory.ItemStack.class)
                    .invoke(null, event.getItemInHand());
            return item != null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private String idDelBloque(Location location) {
        try {
            Object id = checkId.invoke(null, location);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private int contarEnChunk(Chunk chunk) {
        try {
            Object result = getLocations.invoke(null, chunk);
            if (result instanceof java.util.Collection<?> collection) return collection.size();
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().fine("[Laboratorio] No se pudo contar los bloques del chunk: " + error.getMessage());
        }
        return 0;
    }
}
