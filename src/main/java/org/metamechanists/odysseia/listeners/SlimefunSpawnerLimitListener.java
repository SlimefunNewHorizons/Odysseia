package org.metamechanists.odysseia.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Limita cuantos spawners de Slimefun caben juntos.
 *
 * El addon ya trae un tope de seis entidades por spawner, y ese tope es el que hace que uno solo
 * sea inofensivo. Lo que no controla es cuantos se pueden pegar unos a otros: seis apilados en una
 * columna de tres bloques multiplican por seis ese limite sin que nada lo impida, y si encima
 * LevelledMobs les pone nivel 105, cada uno de esos mobs tarda una eternidad en morirse y se queda
 * ahi ocupando pathfinding y colisiones.
 *
 * Se cuenta por distancia y no por chunk a proposito: el problema es la densidad, y un chunk no
 * significa nada para quien construye a caballo entre dos.
 *
 * El conteo mira solo los bloques que ya son SPAWNER de vanilla, asi que la consulta cara --saber
 * si ese spawner es de Slimefun-- se hace sobre un punado de bloques y no sobre el cubo entero.
 */
public final class SlimefunSpawnerLimitListener implements Listener {

    /** Permite al staff montar granjas de prueba sin pelearse con el limite. */
    private static final String BYPASS = "odysseia.spawners.bypass";

    private final Odysseia plugin;
    private final Method slimefunGetByItem;
    private final Method slimefunItemGetId;
    private final Method blockStorageCheckId;

    public SlimefunSpawnerLimitListener(Odysseia plugin) {
        this.plugin = plugin;

        Method byItem = null;
        Method getId = null;
        Method checkId = null;
        try {
            Class<?> slimefunItem = SlimefunClasses.slimefunItem();
            Class<?> blockStorage = SlimefunClasses.blockStorage();
            byItem = slimefunItem.getMethod("getByItem", ItemStack.class);
            getId = slimefunItem.getMethod("getId");
            checkId = blockStorage.getMethod("checkID", Location.class);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("[Spawners] Slimefun no disponible; el límite de spawners queda inactivo.");
        }
        this.slimefunGetByItem = byItem;
        this.slimefunItemGetId = getId;
        this.blockStorageCheckId = checkId;
    }

    private boolean activo() {
        return slimefunGetByItem != null
                && plugin.getConfig().getBoolean("slimefun-spawners.enabled", true);
    }

    /** Un spawner de Slimefun: los electricos de cada mob y el reforzado que los alimenta. */
    private static boolean esSpawnerSlimefun(String id) {
        if (id == null) return false;
        String mayus = id.toUpperCase(Locale.ROOT);
        return mayus.startsWith("ELECTRIC_SPAWNER") || mayus.equals("REINFORCED_SPAWNER");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // Se corta antes que Slimefun --que registra el bloque en HIGHEST-- para que un rechazo no
        // le deje una entrada huerfana en su almacen.
        if (!activo() || event.getBlockPlaced().getType() != Material.SPAWNER) return;

        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;

        String colocado = idDelItem(event.getItemInHand());
        if (!esSpawnerSlimefun(colocado)) return;

        int maximo = Math.max(1, plugin.getConfig().getInt("slimefun-spawners.max-por-zona", 2));
        int radio = Math.max(1, plugin.getConfig().getInt("slimefun-spawners.radio", 8));
        int vecinos = contarCerca(event.getBlockPlaced(), radio);
        if (vecinos < maximo) return;

        event.setCancelled(true);
        player.sendMessage(color("&6DrakesCraft &8· &cDemasiados spawners juntos."));
        player.sendMessage(color("&7Solo caben &e" + maximo + "&7 spawners de Slimefun cada &e"
                + radio + " &7bloques. Aquí ya hay &e" + vecinos + "&7."));
        player.sendMessage(color("&7Sepáralos: apilados multiplican los mobs que cada uno genera"
                + " y el servidor los arrastra a todos."));
    }

    /** Cuantos spawners de Slimefun hay ya alrededor, sin contar el que se intenta colocar. */
    private int contarCerca(Block centro, int radio) {
        int total = 0;
        for (int x = -radio; x <= radio; x++) {
            for (int y = -radio; y <= radio; y++) {
                for (int z = -radio; z <= radio; z++) {
                    Block bloque = centro.getRelative(x, y, z);
                    // El filtro barato va primero: solo los SPAWNER llegan a la consulta a Slimefun.
                    if (bloque.getType() != Material.SPAWNER) continue;
                    if (esSpawnerSlimefun(idDelBloque(bloque.getLocation()))) total++;
                }
            }
        }
        return total;
    }

    private String idDelItem(ItemStack item) {
        if (item == null || slimefunGetByItem == null) return null;
        try {
            Object slimefunItem = slimefunGetByItem.invoke(null, item);
            return slimefunItem == null ? null : (String) slimefunItemGetId.invoke(slimefunItem);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private String idDelBloque(Location location) {
        if (blockStorageCheckId == null) return null;
        try {
            return (String) blockStorageCheckId.invoke(null, location);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static String color(String texto) {
        return ChatColor.translateAlternateColorCodes('&', texto);
    }
}
