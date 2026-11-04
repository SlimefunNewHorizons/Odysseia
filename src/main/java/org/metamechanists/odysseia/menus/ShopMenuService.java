package org.metamechanists.odysseia.menus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.listeners.StoreCommandGuardListener;

import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Hub nativo de comercio y kits; sus destinos se definen íntegramente en config.yml. */
public final class ShopMenuService implements Listener, org.bukkit.command.CommandExecutor {
    private final Odysseia plugin;
    private final Map<Inventory, Page> pages = Collections.synchronizedMap(new IdentityHashMap<>());

    public ShopMenuService(Odysseia plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo un jugador puede abrir la tienda.");
            return true;
        }
        if (opensKits(label, args)) {
            openKits(player);
        } else {
            openRoot(player);
        }
        return true;
    }

    /** Keeps the kit aliases independent from the generic commerce hub aliases. */
    private boolean opensKits(String label, String[] args) {
        String normalizedLabel = label.toLowerCase(Locale.ROOT);
        return normalizedLabel.equals("kitsvip")
                || normalizedLabel.equals("kits_vip")
                || normalizedLabel.equals("kits-vip")
                || (args.length > 0 && args[0].equalsIgnoreCase("kits"));
    }

    private void openRoot(Player player) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("native-menus.shop");
        if (root == null || !root.getBoolean("enabled", true)) {
            player.sendMessage(color("&cLa tienda nativa está desactivada."));
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, size(root.getInt("rows", 6)), color(root.getString("title", "&8Tienda")));
        Map<Integer, String> actions = new LinkedHashMap<>();

        ItemStack filler = createGlassPane(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack border = createGlassPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack accent = createGlassPane(Material.ORANGE_STAINED_GLASS_PANE, " ");

        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == 0 || i == 8 || i == 45 || i == 53) {
                inventory.setItem(i, accent);
            } else if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inventory.setItem(i, border);
            } else {
                inventory.setItem(i, filler);
            }
        }

        ConfigurationSection entries = root.getConfigurationSection("entries");
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry == null) continue;
                int slot = entry.getInt("slot", -1);
                if (slot < 0 || slot >= inventory.getSize()) continue;
                inventory.setItem(slot, item(entry, Material.CHEST));
                List<String> actionsForEntry = new ArrayList<>(entry.getStringList("commands"));
                entry.getStringList("messages").forEach(message -> actionsForEntry.add("odysseia:message:" + message));
                if (!actionsForEntry.isEmpty()) actions.put(slot, String.join("\n", actionsForEntry));
            }
        }
        pages.put(inventory, new Page(actions));
        player.openInventory(inventory);
    }

    private void openKits(Player player) {
        ConfigurationSection menu = plugin.getConfig().getConfigurationSection("native-menus.kits");
        if (menu == null) return;
        Inventory inventory = Bukkit.createInventory(null, size(menu.getInt("rows", 3)), color(menu.getString("title", "&8Kits VIP")));
        Map<Integer, String> actions = new LinkedHashMap<>();
        List<String> kits = menu.getStringList("order");
        for (int index = 0; index < kits.size() && index < inventory.getSize(); index++) {
            String kit = kits.get(index).toLowerCase(Locale.ROOT);
            ConfigurationSection definition = plugin.getConfig().getConfigurationSection("kits." + kit);
            if (definition == null) continue;
            String permission = definition.getString("permission", "").trim();
            boolean owned = !permission.isEmpty() && player.hasPermission(permission);
            var state = plugin.getKitClaimService().state(player.getUniqueId(), kit, definition.getString("cooldown", "30d"));
            ConfigurationSection icon = menu.getConfigurationSection("icons." + kit);
            ItemStack item = item(icon, Material.DIAMOND_CHESTPLATE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color(icon == null ? "&f&l" + kit.toUpperCase(Locale.ROOT) : icon.getString("name", "&f&l" + kit.toUpperCase(Locale.ROOT))));
                String status = !owned ? "&cNo comprado" : state.available() ? "&aDisponible ahora" : "&eDisponible en " + state.remainingText();
                meta.setLore(List.of(color("&7Kit mensual de rango."), color(status), color("&eClic para reclamar.")));
                item.setItemMeta(meta);
            }
            inventory.setItem(index, item);
            actions.put(index, "odysseia:claim:" + kit);
        }
        pages.put(inventory, new Page(actions));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Page page = pages.get(event.getInventory());
        if (page == null) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0) return;
        String action = page.actions().get(event.getRawSlot());
        if (action == null) return;
        for (String command : action.split("\\n")) {
            if (command.equalsIgnoreCase("odysseia:open-kits")) {
                openKits(player);
            } else if (command.startsWith("odysseia:claim:")) {
                String claim = "kit " + command.substring("odysseia:claim:".length());
                var modalityGuard = plugin.getModalityStorageGuard();
                if (modalityGuard == null || !modalityGuard.intercept(player, claim)) {
                    dispatchSafely(player, claim);
                }
            } else if (command.startsWith("odysseia:survival-command:")) {
                String survivalCommand = command.substring("odysseia:survival-command:".length()).trim();
                var modalityGuard = plugin.getModalityStorageGuard();
                if (modalityGuard != null && modalityGuard.intercept(player, survivalCommand)) {
                    player.closeInventory();
                    break;
                }
                if (!survivalCommand.isBlank()) {
                    dispatchSafely(player, survivalCommand);
                }
            } else if (command.startsWith("odysseia:message:")) {
                player.sendMessage(color(command.substring("odysseia:message:".length())));
            } else if (!command.isBlank()) {
                String dispatchedCommand = command.startsWith("/") ? command.substring(1) : command;
                var modalityGuard = plugin.getModalityStorageGuard();
                if (modalityGuard != null && modalityGuard.intercept(player, dispatchedCommand)) {
                    player.closeInventory();
                    break;
                }
                StoreCommandGuardListener.runInternal(player,
                        () -> dispatchSafely(player, dispatchedCommand));
            }
        }
    }

    private void dispatchSafely(Player player, String command) {
        try {
            Bukkit.dispatchCommand(player, command);
        } catch (org.bukkit.command.CommandException ex) {
            plugin.getLogger().warning("El comando '" + command + "' invocado desde el menú falló para "
                    + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        pages.remove(event.getInventory());
    }

    /**
     * Los iconos de la tienda son items de verdad; clic y arrastre son eventos Bukkit distintos y
     * cancelar solo onClick deja la puerta abierta a un arrastre (los clientes Bedrock lo generan
     * distinto a un mouse) que saque o pierda items en este inventario temporal.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (pages.containsKey(event.getInventory())) event.setCancelled(true);
    }

    private ItemStack item(ConfigurationSection entry, Material fallback) {
        Material material = entry == null ? fallback : Material.matchMaterial(entry.getString("material", fallback.name()));
        if (material == null || !material.isItem()) {
            material = fallback;
        }
        ItemStack item = new ItemStack(material);
        if (entry == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color(entry.getString("name", "&f")));
        meta.setLore(entry.getStringList("lore").stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGlassPane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static int size(int rows) {
        return Math.max(1, Math.min(6, rows)) * 9;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private record Page(Map<Integer, String> actions) { }
}
