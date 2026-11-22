package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.metamechanists.odysseia.kits.CustomContentResolver;
import org.metamechanists.odysseia.modalities.ModalityService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Blindaje y sanitización activa de aldeanos y vendedores ambulantes en la modalidad Clásico.
 *
 * <p>En Clásico (clasico, clasico_nether, clasico_the_end) el juego debe mantenerse estrictamente
 * vainilla. Algunos addons de Slimefun (como Cultivation, SlimeFrame o VillagerTrade) inyectan
 * recetas comerciales en aldeanos o vendedores ambulantes sin comprobar si el mundo tiene
 * Slimefun activo, y esas ofertas quedan además fijadas en el NBT de las entidades.</p>
 *
 * <p>Este listener:
 * <ul>
 *   <li>Cancela la adquisición de nuevas ofertas comerciales con ítems de Slimefun en Clásico.</li>
 *   <li>Higieniza en tiempo real las recetas de cualquier aldeano o comerciante cuando un jugador
 *       interactúa con él o abre su menú, eliminando únicamente las recetas de Slimefun sin alterar
 *       los intercambios vainilla (reparación/mending, esmeraldas, libreros, etc.).</li>
 *   <li>Limpia de forma asíncrona (1-3 ticks) comerciantes recién aparecidos o aldeanos que hayan
 *       cambiado de profesión.</li>
 * </ul>
 * </p>
 */
public final class ClasicoVillagerTradeGuardListener implements Listener {

    private static final Set<String> SLIMEFUN_NAMESPACES = Set.of(
            "slimefun",
            "cultivation",
            "slimeframe",
            "dynatech",
            "exoticgarden",
            "alchimiavitae",
            "infinityexpansion",
            "fluffymachines",
            "sensibletoolbox",
            "extraweapons",
            "villagertrade",
            "sfcalc",
            "slimybees",
            "slimytreetaps",
            "equivalencytech"
    );

    private final Plugin plugin;
    private final ModalityService modalityService;
    private Method slimefunGetByItem;

    public ClasicoVillagerTradeGuardListener(Plugin plugin, ModalityService modalityService) {
        this.plugin = plugin;
        this.modalityService = modalityService;
        initSlimefunReflection();
    }

    private void initSlimefunReflection() {
        for (String base : List.of("com.github.drakescraft_labs.slimefun4", "io.github.thebusybiscuit.slimefun4")) {
            try {
                Class<?> itemClass = Class.forName(base + ".api.items.SlimefunItem");
                this.slimefunGetByItem = itemClass.getMethod("getByItem", ItemStack.class);
                return;
            } catch (ReflectiveOperationException ignored) {
                this.slimefunGetByItem = null;
            }
        }
    }

    /**
     * Determina si el mundo pertenece a la modalidad Clásico.
     */
    public boolean isClasico(World world) {
        return ModalityService.isClasico(modalityService, world);
    }

    /**
     * Determina si un ItemStack corresponde a un ítem de Slimefun o de sus addons.
     */
    public boolean isSlimefunOrCustomItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return false;
        }

        // 1. Verificación por API de Slimefun (cubre ítems del core y todos los addons registrados)
        if (slimefunGetByItem != null) {
            try {
                Object sfItem = slimefunGetByItem.invoke(null, item);
                if (sfItem != null) {
                    return true;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // 2. Verificación de PersistentDataContainer
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                String ns = key.getNamespace().toLowerCase(Locale.ROOT);
                if (SLIMEFUN_NAMESPACES.contains(ns) || ns.startsWith("slimefun") || ns.startsWith("equivalency")) {
                    return true;
                }
            }

            // 3. Verificación de Lore (patrones característicos de Slimefun / Cultivation)
            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        if (line == null) continue;
                        String stripped = ChatColor.stripColor(line).toLowerCase(Locale.ROOT);
                        if (stripped.contains("slimefun")
                                || stripped.contains("cultivation")
                                || stripped.startsWith("id: ")
                                || stripped.contains("generado por sfmaster")) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Comprueba si una receta de comercio produce o requiere un ítem de Slimefun.
     */
    public boolean isSlimefunTrade(MerchantRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        if (isSlimefunOrCustomItem(recipe.getResult())) {
            return true;
        }
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (isSlimefunOrCustomItem(ingredient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indica si un ItemStack posee encantamientos no-vanilla (namespace != {@code minecraft})
     * o niveles por encima del máximo legal vanilla, tanto en encantamientos directos como
     * almacenados en libros encantados.
     */
    public boolean hasIllegalEnchantments(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (hasIllegalEnchantMap(meta.getEnchants())) {
            return true;
        }
        return meta instanceof EnchantmentStorageMeta storage && hasIllegalEnchantMap(storage.getStoredEnchants());
    }

    private static boolean hasIllegalEnchantMap(Map<Enchantment, Integer> enchants) {
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            if (!CustomContentResolver.isVanillaEnchantment(enchantment)
                    || level < 1
                    || level > Math.max(1, enchantment.getMaxLevel())) {
                return true;
            }
        }
        return false;
    }

    private enum TradeAction {
        KEEP,
        REPLACE,
        REMOVE
    }

    private record TradeSanitization(TradeAction action, MerchantRecipe sanitizedRecipe) {
        private static final TradeSanitization KEEP = new TradeSanitization(TradeAction.KEEP, null);
        private static final TradeSanitization REMOVE = new TradeSanitization(TradeAction.REMOVE, null);

        private static TradeSanitization replace(MerchantRecipe recipe) {
            return new TradeSanitization(TradeAction.REPLACE, recipe);
        }
    }

    private TradeSanitization inspectAndSanitize(MerchantRecipe recipe) {
        if (recipe == null || isSlimefunTrade(recipe)) {
            return TradeSanitization.REMOVE;
        }
        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir() || !result.hasItemMeta()) {
            return TradeSanitization.KEEP;
        }
        ItemStack sanitizedResult = result.clone();
        ItemMeta meta = sanitizedResult.getItemMeta();
        if (meta == null) {
            return TradeSanitization.KEEP;
        }

        boolean hadEnchants = !meta.getEnchants().isEmpty();
        boolean modified = sanitizeEnchantMap(
                meta.getEnchants(),
                meta::removeEnchant,
                (ench, level) -> meta.addEnchant(ench, level, true));

        if (meta instanceof EnchantmentStorageMeta storage) {
            hadEnchants |= !storage.getStoredEnchants().isEmpty();
            modified |= sanitizeEnchantMap(
                    storage.getStoredEnchants(),
                    storage::removeStoredEnchant,
                    (ench, level) -> storage.addStoredEnchant(ench, level, true));
        }

        if (!modified) {
            return TradeSanitization.KEEP;
        }

        boolean hasRemainingEnchants = !meta.getEnchants().isEmpty()
                || (meta instanceof EnchantmentStorageMeta storage && !storage.getStoredEnchants().isEmpty());
        // Un libro encantado (o ítem encantado) que solo tenía encantamientos no-vanilla queda vacío y roto: eliminar trade.
        if (hadEnchants && !hasRemainingEnchants) {
            return TradeSanitization.REMOVE;
        }

        sanitizedResult.setItemMeta(meta);
        return TradeSanitization.replace(copyRecipeWithResult(recipe, sanitizedResult));
    }

    private static boolean sanitizeEnchantMap(
            Map<Enchantment, Integer> enchants,
            Consumer<Enchantment> remover,
            BiConsumer<Enchantment, Integer> updater) {
        boolean modified = false;
        for (Map.Entry<Enchantment, Integer> entry : Map.copyOf(enchants).entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            if (!CustomContentResolver.isVanillaEnchantment(enchantment) || level < 1) {
                remover.accept(enchantment);
                modified = true;
            } else {
                int clamped = CustomContentResolver.clampVanillaLevel(enchantment, level);
                if (clamped != level) {
                    updater.accept(enchantment, clamped);
                    modified = true;
                }
            }
        }
        return modified;
    }

    private static MerchantRecipe copyRecipeWithResult(MerchantRecipe recipe, ItemStack result) {
        MerchantRecipe copy = new MerchantRecipe(
                result,
                recipe.getUses(),
                recipe.getMaxUses(),
                recipe.hasExperienceReward(),
                recipe.getVillagerExperience(),
                recipe.getPriceMultiplier(),
                recipe.getDemand(),
                recipe.getSpecialPrice(),
                recipe.shouldIgnoreDiscounts()
        );
        if (recipe.getIngredients() != null) {
            copy.setIngredients(recipe.getIngredients());
        }
        return copy;
    }

    /**
     * Sanea la lista de recetas de un comerciante, purgando ofertas de Slimefun o exclusivamente
     * no-vanilla y clampeando encantamientos vanilla ilegales sin alterar intercambios legítimos.
     *
     * @return true si se removió o saneó al menos una receta no autorizada.
     */
    public boolean sanitizeRecipes(Merchant trader) {
        if (trader == null) {
            return false;
        }
        List<MerchantRecipe> recipes = trader.getRecipes();
        if (recipes == null || recipes.isEmpty()) {
            return false;
        }
        List<MerchantRecipe> cleanRecipes = new ArrayList<>(recipes.size());
        boolean modified = false;
        for (MerchantRecipe recipe : recipes) {
            TradeSanitization outcome = inspectAndSanitize(recipe);
            switch (outcome.action()) {
                case KEEP -> cleanRecipes.add(recipe);
                case REPLACE -> {
                    cleanRecipes.add(outcome.sanitizedRecipe());
                    modified = true;
                }
                case REMOVE -> modified = true;
            }
        }
        if (modified) {
            trader.setRecipes(cleanRecipes);
        }
        return modified;
    }

    /**
     * Evita que un aldeano en Clásico adquiera un nuevo trade de Slimefun o con encantamientos no-vanilla.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerAcquireTrade(VillagerAcquireTradeEvent event) {
        if (!isClasico(event.getEntity().getWorld())) {
            return;
        }
        TradeSanitization outcome = inspectAndSanitize(event.getRecipe());
        if (outcome.action() == TradeAction.REMOVE) {
            event.setCancelled(true);
        } else if (outcome.action() == TradeAction.REPLACE) {
            event.setRecipe(outcome.sanitizedRecipe());
        }
    }

    /**
     * Al interactuar con un aldeano o vendedor ambulante en Clásico, purga o sanea cualquier trade
     * corrupto existente en su NBT antes de que se abra la ventana comercial.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!isClasico(event.getPlayer().getWorld())) {
            return;
        }
        if (event.getRightClicked() instanceof AbstractVillager trader) {
            if (sanitizeRecipes(trader) && plugin != null) {
                plugin.getLogger().info("[ClasicoTradeGuard] Sanitización de trades ejecutada en aldeano ("
                        + trader.getLocation().getBlockX() + ", "
                        + trader.getLocation().getBlockY() + ", "
                        + trader.getLocation().getBlockZ() + ") en "
                        + trader.getWorld().getName());
            }
        }
    }

    /**
     * Barrera secundaria al abrir cualquier inventario de comerciante.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!isClasico(event.getPlayer().getWorld())) {
            return;
        }
        if (event.getInventory() instanceof MerchantInventory merchantInventory) {
            Merchant merchant = merchantInventory.getMerchant();
            if (merchant != null && sanitizeRecipes(merchant) && plugin != null) {
                plugin.getLogger().info("[ClasicoTradeGuard] Sanitización secundaria de trades ejecutada al abrir inventario.");
            }
        }
    }

    /**
     * Higieniza vendedores ambulantes u otros aldeanos tras su aparición.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (plugin == null || !plugin.isEnabled() || !isClasico(event.getLocation().getWorld())) {
            return;
        }
        if (event.getEntity() instanceof AbstractVillager trader) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (trader.isValid()) {
                    sanitizeRecipes(trader);
                }
            }, 2L);
        }
    }

    /**
     * Higieniza aldeanos que acaban de cambiar de profesión cuando los plugins de addons
     * programan sus inyecciones en el tick siguiente.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerCareerChange(VillagerCareerChangeEvent event) {
        if (plugin == null || !plugin.isEnabled() || !isClasico(event.getEntity().getWorld())) {
            return;
        }
        Villager villager = event.getEntity();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (villager.isValid()) {
                sanitizeRecipes(villager);
            }
        }, 3L);
    }
}
