package org.metamechanists.odysseia.commands;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.economy.CommerceRateLimiter;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Permite a los jugadores vender de forma rápida y 100% segura los ítems de recursos
 * de su inventario principal sin arriesgar herramientas, armaduras o ítems de Slimefun.
 */
public final class SellInventoryCommand implements CommandExecutor {

    private final Odysseia plugin;
    private final Map<Material, Double> sellPrices = new EnumMap<>(Material.class);
    private Method slimefunGetByItemMethod;
    private boolean reflectionInitialized = false;
    private final CommerceRateLimiter rateLimiter = new CommerceRateLimiter();
    private static final Set<Material> DEFAULT_BLOCKED_MATERIALS = Set.of(
            Material.COBBLESTONE, Material.STONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.DIRT, Material.SAND, Material.GRAVEL, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE);

    public SellInventoryCommand(Odysseia plugin) {
        this.plugin = plugin;
        loadDefaultSellPrices();
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> sfItemClass = SlimefunClasses.slimefunItem();
            this.slimefunGetByItemMethod = sfItemClass.getMethod("getByItem", ItemStack.class);
            this.reflectionInitialized = true;
        } catch (Throwable ignored) {
            this.reflectionInitialized = false;
        }
    }

    private void loadDefaultSellPrices() {
        // Bloques de construcción & recursos básicos
        sellPrices.put(Material.COBBLESTONE, 0.25);
        sellPrices.put(Material.STONE, 0.50);
        sellPrices.put(Material.DEEPSLATE, 0.50);
        sellPrices.put(Material.COBBLED_DEEPSLATE, 0.30);
        sellPrices.put(Material.DIRT, 0.10);
        sellPrices.put(Material.SAND, 0.25);
        sellPrices.put(Material.GRAVEL, 0.25);
        sellPrices.put(Material.GRANITE, 0.40);
        sellPrices.put(Material.DIORITE, 0.40);
        sellPrices.put(Material.ANDESITE, 0.40);
        sellPrices.put(Material.NETHERRACK, 0.20);
        sellPrices.put(Material.BASALT, 0.35);
        sellPrices.put(Material.BLACKSTONE, 0.40);

        // Maderas (Logs)
        sellPrices.put(Material.OAK_LOG, 1.50);
        sellPrices.put(Material.SPRUCE_LOG, 1.50);
        sellPrices.put(Material.BIRCH_LOG, 1.50);
        sellPrices.put(Material.JUNGLE_LOG, 1.50);
        sellPrices.put(Material.ACACIA_LOG, 1.50);
        sellPrices.put(Material.DARK_OAK_LOG, 1.50);
        sellPrices.put(Material.MANGROVE_LOG, 1.50);
        sellPrices.put(Material.CHERRY_LOG, 1.50);
        sellPrices.put(Material.CRIMSON_STEM, 1.80);
        sellPrices.put(Material.WARPED_STEM, 1.80);

        // Minerales y lingotes quedan deliberadamente fuera: Slimefun permite
        // automatizarlos y convertirlos en una fuente ilimitada de Dragmas.

        // Cultivos & agricultura
        sellPrices.put(Material.WHEAT, 1.00);
        sellPrices.put(Material.CARROT, 1.00);
        sellPrices.put(Material.POTATO, 1.00);
        sellPrices.put(Material.BEETROOT, 1.00);
        sellPrices.put(Material.SUGAR_CANE, 1.00);
        sellPrices.put(Material.PUMPKIN, 2.00);
        sellPrices.put(Material.MELON_SLICE, 0.50);
        sellPrices.put(Material.SWEET_BERRIES, 0.80);
        sellPrices.put(Material.GLOW_BERRIES, 1.00);
        sellPrices.put(Material.BAMBOO, 0.50);
        sellPrices.put(Material.CACTUS, 1.00);
        // Nether wart is fully automatable; keep it below manual-farming value.
        sellPrices.put(Material.NETHER_WART, 0.10);

        // Drops de Mobs
        sellPrices.put(Material.ROTTEN_FLESH, 0.50);
        sellPrices.put(Material.BONE, 1.00);
        sellPrices.put(Material.STRING, 1.00);
        sellPrices.put(Material.SPIDER_EYE, 1.50);
        sellPrices.put(Material.GUNPOWDER, 3.00);
        sellPrices.put(Material.ENDER_PEARL, 5.00);
        sellPrices.put(Material.BLAZE_ROD, 8.00);
        sellPrices.put(Material.SLIME_BALL, 4.00);
        sellPrices.put(Material.FEATHER, 0.50);
        sellPrices.put(Material.LEATHER, 2.00);
        sellPrices.put(Material.PRISMARINE_SHARD, 2.50);
        sellPrices.put(Material.PRISMARINE_CRYSTALS, 3.50);
    }

    private boolean isSlimefunItem(ItemStack item) {
        if (!reflectionInitialized || slimefunGetByItemMethod == null || item == null) {
            return false;
        }
        try {
            return slimefunGetByItemMethod.invoke(null, item) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Visible para pruebas de regresión de la política económica. */
    boolean isSellable(Material material) {
        return sellPrices.containsKey(material) && !blockedMaterials().contains(material);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            player.sendMessage(ChatColor.RED + "⚠ El sistema de economía (Vault) no está disponible en este momento.");
            return true;
        }

        Economy economy = rsp.getProvider();
        List<SaleCandidate> candidates = new ArrayList<>();
        Map<Material, Integer> requestedByMaterial = new EnumMap<>(Material.class);

        // Escanear ÚNICAMENTE el inventario principal (ranuras 0 a 35). Omitir armadura y offhand.
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            // BLINDAJE DE SEGURIDAD 1: Nunca vender ítems con metadatos personalizados o encantamientos
            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                        || meta.hasCustomModelData() || !meta.getPersistentDataContainer().isEmpty())) {
                    continue;
                }
            }

            // BLINDAJE DE SEGURIDAD 2: Nunca vender ningún ítem proveniente de Slimefun
            if (isSlimefunItem(item)) {
                continue;
            }

            // BLINDAJE DE SEGURIDAD 3: Verificar si el material tiene precio asignado
            Material type = item.getType();
            if (!isSellable(type)) {
                continue;
            }
            candidates.add(new SaleCandidate(i, type, item.getAmount(), sellPrices.get(type)));
            requestedByMaterial.merge(type, item.getAmount(), Integer::sum);
        }

        if (candidates.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "💡 No se encontraron recursos vendibles en tu inventario principal.");
            player.sendMessage(ChatColor.GRAY + "Pista: piedra, cobble y recursos de granjas automáticas no se compran aquí.");
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = Math.clamp(configLong("economy-guard.sell-inventory.cooldown-seconds", 20L), 5L, 300L) * 1000L;
        long windowMillis = Math.clamp(configLong("economy-guard.sell-inventory.window-seconds", 3600L), 60L, 86_400L) * 1000L;
        int maxPerMaterial = Math.clamp(configInt("economy-guard.sell-inventory.max-items-per-material", 3456), 64, 100_000);
        CommerceRateLimiter.Decision decision = rateLimiter.reserve(player.getUniqueId(), requestedByMaterial,
                now, cooldownMillis, windowMillis, maxPerMaterial);
        if (decision.reason() == CommerceRateLimiter.Reason.COOLDOWN) {
            player.sendMessage(ChatColor.RED + "⏳ Espera " + seconds(decision.retryAfterMillis()) + "s antes de vender otra vez.");
            return true;
        }
        if (decision.reason() == CommerceRateLimiter.Reason.QUOTA_REACHED) {
            player.sendMessage(ChatColor.RED + "📦 Alcanzaste la cuota de venta por material. Intenta nuevamente en "
                    + seconds(decision.retryAfterMillis()) + "s.");
            return true;
        }

        double totalEarnings = 0.0;
        int totalItemsSold = 0;
        Map<Material, Integer> remainingAllowed = new EnumMap<>(decision.accepted());
        for (SaleCandidate candidate : candidates) {
            int allowed = Math.min(candidate.amount(), remainingAllowed.getOrDefault(candidate.material(), 0));
            if (allowed <= 0) continue;
            remainingAllowed.merge(candidate.material(), -allowed, Integer::sum);
            totalEarnings += candidate.unitPrice() * allowed;
            totalItemsSold += allowed;
            ItemStack current = player.getInventory().getItem(candidate.slot());
            if (current == null || current.getType() != candidate.material()) continue;
            if (current.getAmount() == allowed) player.getInventory().setItem(candidate.slot(), null);
            else current.setAmount(current.getAmount() - allowed);
        }

        // Acreditar dinero
        economy.depositPlayer(player, totalEarnings);

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "💰 " + ChatColor.BOLD + "¡VENTA DE INVENTARIO COMPLETADA!");
        player.sendMessage(ChatColor.GREEN + "✔ Vendidos: " + ChatColor.WHITE + totalItemsSold + " recursos de supervivencia.");
        player.sendMessage(ChatColor.YELLOW + "💵 Total acreditado: " + ChatColor.GOLD + "+" + String.format("%.2f", totalEarnings) + " Dragmas");
        player.sendMessage("");

        return true;
    }

    private Set<Material> blockedMaterials() {
        Set<Material> blocked = new HashSet<>(DEFAULT_BLOCKED_MATERIALS);
        if (plugin == null) return blocked;
        for (String name : plugin.getConfig().getStringList("economy-guard.sell-inventory.blocked-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null) blocked.add(material);
        }
        return blocked;
    }

    private long configLong(String path, long fallback) {
        return plugin == null ? fallback : plugin.getConfig().getLong(path, fallback);
    }

    private int configInt(String path, int fallback) {
        return plugin == null ? fallback : plugin.getConfig().getInt(path, fallback);
    }

    private long seconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private record SaleCandidate(int slot, Material material, int amount, double unitPrice) { }
}
