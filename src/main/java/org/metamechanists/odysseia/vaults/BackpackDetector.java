package org.metamechanists.odysseia.vaults;

import org.bukkit.ChatColor;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Reconoce mochilas de Slimefun, tambien cuando vienen escondidas dentro de otra cosa.
 *
 * Una mochila de Slimefun no guarda sus items dentro del propio item: guarda un **ID** en el lore y
 * el contenido vive en el almacen de Slimefun. Por eso una mochila metida en una boveda no ocupa lo
 * que contiene, solo lo que pesa el puntero. Meter 27 mochilas dentro de una shulker y la shulker
 * en la boveda multiplica el almacenamiento por varios ordenes de magnitud en un solo slot.
 *
 * Ademas, siendo un puntero, cualquier camino que copie el item copia el acceso al mismo contenido:
 * dos items con el mismo ID son dos puertas a la misma mochila.
 *
 * La deteccion prefiere la API de Slimefun, que es la autoridad. Solo si Slimefun no esta cargado
 * se cae al lore, que es como el propio Slimefun identifica una mochila ya vinculada.
 *
 * <p><b>La Ender Backpack queda fuera a proposito.</b> No extiende {@code SlimefunBackpack}, y no
 * por descuido de Slimefun: no es un almacen propio sino una puerta al cofre de ender del jugador,
 * que es uno solo. Guardarla en una boveda no multiplica nada, asi que bloquearla seria molestar
 * sin ganar seguridad. Si algun dia se quiere bloquear, hay que añadirla por nombre de clase, no
 * esperando que herede.</p>
 */
public final class BackpackDetector {

    /** Slimefun escribe el ID de la mochila en esta linea del lore. */
    private static final String PREFIJO_LORE = ChatColor.GRAY + "ID: ";

    private static boolean reflexionLista;
    private static Method getByItem;
    private static Class<?> claseMochila;

    private final boolean revisarContenedores;
    private final int profundidadMaxima;

    public BackpackDetector(boolean revisarContenedores, int profundidadMaxima) {
        this.revisarContenedores = revisarContenedores;
        this.profundidadMaxima = Math.max(1, profundidadMaxima);
    }

    /** True si el item es una mochila o esconde alguna dentro. */
    public boolean contieneMochila(ItemStack item) {
        return buscar(item, 0);
    }

    private boolean buscar(ItemStack item, int profundidad) {
        if (item == null || item.getType().isAir()) return false;
        if (esMochila(item)) return true;
        if (!revisarContenedores || profundidad >= profundidadMaxima) return false;

        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta == null) return false;

        // Shulker: su contenido viaja dentro del propio item, asi que hay que abrirlo.
        if (meta instanceof BlockStateMeta estado && estado.hasBlockState()
                && estado.getBlockState() instanceof ShulkerBox caja) {
            for (ItemStack dentro : caja.getInventory().getContents()) {
                if (buscar(dentro, profundidad + 1)) return true;
            }
        }

        if (meta instanceof BundleMeta bolsa) {
            for (ItemStack dentro : bolsa.getItems()) {
                if (buscar(dentro, profundidad + 1)) return true;
            }
        }

        return false;
    }

    /** True si el item es, en si mismo, una mochila de Slimefun. */
    public static boolean esMochila(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;

        Boolean segunSlimefun = preguntarASlimefun(item);
        if (segunSlimefun != null) return segunSlimefun;

        // Slimefun no esta disponible: nos apoyamos en la marca que el propio Slimefun deja.
        return tieneLoreDeMochila(item);
    }

    /**
     * @return true/false segun Slimefun, o {@code null} si Slimefun no esta disponible y hay que
     *         recurrir al lore.
     */
    private static Boolean preguntarASlimefun(ItemStack item) {
        inicializarReflexion();
        if (getByItem == null || claseMochila == null) return null;
        try {
            Object sfItem = getByItem.invoke(null, item);
            if (sfItem == null) return false;   // es un item normal, no de Slimefun
            return claseMochila.isInstance(sfItem);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Visible para pruebas: la marca que Slimefun deja en el lore de toda mochila. */
    static boolean tieneLoreDeMochila(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null) return false;
        for (String linea : lore) {
            if (linea != null && linea.startsWith(PREFIJO_LORE)) return true;
        }
        return false;
    }

    /** Visible para pruebas: la comparacion de una linea de lore, sin depender de Bukkit. */
    static boolean esLineaDeIdDeMochila(String linea) {
        return linea != null && linea.startsWith(PREFIJO_LORE);
    }

    private static synchronized void inicializarReflexion() {
        if (reflexionLista) return;
        reflexionLista = true;
        // El fork propio y el Slimefun de upstream usan paquetes distintos; se prueban los dos.
        for (String base : List.of("com.github.drakescraft_labs.slimefun4", "io.github.thebusybiscuit.slimefun4")) {
            try {
                Class<?> item = Class.forName(base + ".api.items.SlimefunItem");
                claseMochila = Class.forName(base + ".implementation.items.backpacks.SlimefunBackpack");
                getByItem = item.getMethod("getByItem", ItemStack.class);
                return;
            } catch (ReflectiveOperationException ignored) {
                getByItem = null;
                claseMochila = null;
            }
        }
    }
}
