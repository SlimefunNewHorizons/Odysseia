package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Keyed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Clasico se queda en Minecraft de siempre con Terralith aunque el servidor cargue datapacks de
 * contenido (Stellarity, Incendium, Yggdrasil, SVM Powers, Enchants Plus, From the Fog).
 *
 * Los datapacks son globales: el terreno se aisla con ajustes propios por mundo, y esto cubre
 * lo que no se puede aislar asi: estructuras, recetas, encantamientos y entidades marcadas.
 * Las listas se pueden ampliar en config.yml bajo clasico-pureza sin recompilar.
 */
public final class ClasicoPurezaListener implements Listener {

    private static final List<String> ESTRUCTURAS = List.of(
            "yggdrasil", "stellarity", "incendium", "svm_ep", "lunareclipse.watching", "watching", "awesomedungeonend");
    private static final List<String> RECETAS = List.of(
            "stellarity", "incendium", "svm_ep", "enchantsplus", "lunareclipse.watching", "watching", "kohara");
    private static final List<String> ENCANTAMIENTOS = List.of(
            "enchantsplus", "stellarity", "incendium", "svm_ep");
    private static final List<String> ETIQUETAS = List.of(
            "stellarity.", "kohara.", "watching.", "svm_ep.", "enchantsplus.");

    private final JavaPlugin plugin;
    private final Set<String> estructuras = new HashSet<>();
    private final Set<String> recetas = new HashSet<>();
    private final Set<String> encantamientos = new HashSet<>();
    private final List<String> etiquetas;

    public ClasicoPurezaListener(JavaPlugin plugin) {
        this.plugin = plugin;
        estructuras.addAll(lista("estructuras", ESTRUCTURAS));
        recetas.addAll(lista("recetas", RECETAS));
        encantamientos.addAll(lista("encantamientos", ENCANTAMIENTOS));
        etiquetas = lista("etiquetas-entidad", ETIQUETAS);
        if (activo()) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::limpiarEntidades, 100L, 40L);
        }
    }

    private List<String> lista(String clave, List<String> porDefecto) {
        List<String> configurada = plugin.getConfig().getStringList("clasico-pureza." + clave);
        return configurada.isEmpty() ? porDefecto : configurada;
    }

    private boolean activo() {
        return plugin.getConfig().getBoolean("clasico-pureza.enabled", true);
    }

    private boolean esClasico(World world) {
        return activo() && world != null
                && plugin.getConfig().getStringList("modalidades.modos.clasico.mundos").contains(world.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEstructura(AsyncStructureSpawnEvent event) {
        if (!esClasico(event.getWorld())) return;
        if (estructuras.contains(event.getStructure().getKey().getNamespace())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !esClasico(event.getView().getPlayer().getWorld())) return;
        if (recetas.contains(keyed.getKey().getNamespace())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEncantar(EnchantItemEvent event) {
        if (!esClasico(event.getEnchanter().getWorld())) return;
        event.getEnchantsToAdd().keySet().removeIf(this::prohibido);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onYunque(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || !esClasico(event.getView().getPlayer().getWorld())) return;
        boolean malo = result.getEnchantments().keySet().stream().anyMatch(this::prohibido);
        if (!malo && result.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            malo = storage.getStoredEnchants().keySet().stream().anyMatch(this::prohibido);
        }
        if (malo) event.setResult(null);
    }

    private boolean prohibido(Enchantment enchantment) {
        return encantamientos.contains(enchantment.getKey().getNamespace());
    }

    private void limpiarEntidades() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!esClasico(player.getWorld())) continue;
            for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
                if (entity instanceof Player) continue;
                for (String tag : entity.getScoreboardTags()) {
                    if (etiquetas.stream().anyMatch(tag::startsWith)) {
                        entity.remove();
                        break;
                    }
                }
            }
        }
    }
}
