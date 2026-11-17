package org.metamechanists.odysseia.purchase;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.metamechanists.odysseia.Odysseia;

import java.util.*;

/** Construye kits desde la configuración activa sin ejecutar dinero ni comandos embebidos. */
public final class KitDeliveryService {
    private final Odysseia plugin;
    private final NamespacedKey transactionKey;

    public KitDeliveryService(Odysseia plugin) {
        this.plugin = plugin;
        this.transactionKey = new NamespacedKey(plugin, "purchase_transaction");
    }

    public ActionResult deliver(Player player, String kitName, String transaction) {
        return deliver(player, kitName, transaction, true);
    }

    /** Builds a kit atomically; administrative previews never invoke external rewards. */
    public ActionResult deliver(Player player, String kitName, String transaction, boolean includeProtection) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("kits." + kitName);
        if (section == null) return ActionResult.manual("Kit ausente en configuración: " + kitName);
        if (containsTransaction(player, transaction)) return ActionResult.manual("Se detectaron ítems de esta transacción; requiere reconciliación");
        List<ItemStack> items = new ArrayList<>();
        for (Map<?, ?> values : section.getMapList("vanilla-items")) {
            List<ItemStack> stacks = createItems(values, transaction);
            if (stacks == null) return ActionResult.manual("Item inválido en kit " + kitName + ": " + values.get("material"));
            items.addAll(stacks);
        }
        if (!fits(player.getInventory(), items)) return ActionResult.waiting("Inventario sin espacio suficiente");
        if (includeProtection) {
            ActionResult protection = deliverConfiguredProtection(player, section);
            if (protection.status() != ActionResult.Status.COMPLETED) return protection;
        }
        for (ItemStack item : items) player.getInventory().addItem(item);
        return ActionResult.completed("kit=" + kitName + ";items=" + items.size());
    }

    /** Valida todas las entradas antes de permitir pruebas o entregas reales. */
    public List<String> validateConfiguration() {
        List<String> errors = new ArrayList<>();
        ConfigurationSection kits = plugin.getConfig().getConfigurationSection("kits");
        if (kits == null) return List.of("Falta la sección kits");
        for (String kit : kits.getKeys(false)) {
            ConfigurationSection section = kits.getConfigurationSection(kit);
            if (section == null) continue;
            String permission = section.getString("permission", "").trim();
            if (permission.isEmpty()) errors.add(kit + ": falta permiso explícito de LuckPerms");
            String protectionKey = section.getString("protection-alias", "").trim();
            if (!protectionKey.isEmpty()) {
                String alias = plugin.getConfig().getString("protectionstones.aliases." + protectionKey, "").trim();
                int amount = section.getInt("protection-amount", 1);
                if (!protectionKey.matches("[A-Za-z0-9_-]+") || !alias.matches("[A-Za-z0-9_-]+")) {
                    errors.add(kit + ": alias de ProtectionStone inválido " + protectionKey);
                }
                if (amount < 1 || amount > 64) errors.add(kit + ": cantidad de ProtectionStone fuera de 1..64");
            }
            int index = 0;
            for (Map<?, ?> values : section.getMapList("vanilla-items")) {
                index++;
                String path = kit + ".vanilla-items[" + index + "]";
                boolean fromSlimefun = values.get("slimefun-item") != null;
                boolean fromSpecial = values.get("special-item") != null;
                Material material = Material.matchMaterial(String.valueOf(values.get("material")));
                if (!fromSlimefun && !fromSpecial && (material == null || !material.isItem())) {
                    errors.add(path + ": material inválido");
                    continue;
                }
                int amount = integer(values.get("amount"), 1);
                if (amount < 1 || amount > 2304) errors.add(path + ": cantidad fuera de 1..2304");
                if (material == Material.WRITTEN_BOOK
                        && !(values.get("pages") instanceof List<?> pages && !pages.isEmpty())) {
                    errors.add(path + ": libro sin páginas");
                }
                Object enchantments = values.get("enchantments");
                if (enchantments instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        Enchantment enchantment = org.metamechanists.odysseia.kits.CustomContentResolver
                                .enchantment(String.valueOf(entry.getKey()));
                        int level = integer(entry.getValue(), 0);
                        if (enchantment == null) errors.add(path + ": encantamiento desconocido " + entry.getKey());
                        if (level < 1 || level > 255) errors.add(path + ": nivel inválido para " + entry.getKey());
                    }
                }
            }
        }
        return errors;
    }

    private List<ItemStack> createItems(Map<?, ?> values, String transaction) {
        // Un kit puede partir de un item especial (Papa de mar con PDC), Slimefun o vanilla.
        Object special = values.get("special-item");
        ItemStack item = null;
        if (special != null) {
            String specialName = String.valueOf(special).toUpperCase(Locale.ROOT);
            if (specialName.contains("PAPA_DE_MAR")) {
                item = plugin.createPapaDeMarItem();
            }
        }
        if (item == null) {
            Object slimefunId = values.get("slimefun-item");
            item = slimefunId == null ? null
                    : org.metamechanists.odysseia.kits.CustomContentResolver.slimefunItem(String.valueOf(slimefunId));
            if (slimefunId != null && item == null) {
                plugin.getLogger().warning("[Kits] Item de Slimefun no encontrado: " + slimefunId);
                return null;
            }
        }
        Material material = item != null ? item.getType()
                : Material.matchMaterial(String.valueOf(values.get("material")));
        if (material == null) return null;
        int amount = integer(values.get("amount"), 1);
        if (item == null) item = new ItemStack(material, 1);
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return List.of(item);
        Object name = values.get("name");
        if (name != null) meta.setDisplayName(color(String.valueOf(name)));
        Object loreValue = values.get("lore");
        if (loreValue instanceof List<?> lore) meta.setLore(lore.stream().map(value -> color(String.valueOf(value))).toList());
        if (meta instanceof BookMeta bookMeta) {
            String rawTitle = String.valueOf(values.containsKey("book-title")
                    ? values.get("book-title") : "Guía de DrakesCraft");
            bookMeta.setTitle(ChatColor.stripColor(color(rawTitle)));
            bookMeta.setAuthor(color(String.valueOf(values.containsKey("author")
                    ? values.get("author") : "Staff DrakesCraft")));
            if (name == null) {
                meta.setDisplayName(color(rawTitle));
            }
            if (loreValue == null) {
                meta.setLore(List.of(
                        color("&8&m────────────────────────────────────────"),
                        color("&7Manual oficial de supervivencia."),
                        color("&7Aprende comandos, reglas y protecciones."),
                        "",
                        color("&e➤ Clic derecho para abrir la guía."),
                        color("&8&m────────────────────────────────────────")
                ));
            }
            Object pages = values.get("pages");
            if (pages instanceof List<?> list) {
                bookMeta.setPages(list.stream()
                        .map(value -> color(String.valueOf(value).replace("\\n", "\n")))
                        .toList());
            }
            bookMeta.setGeneration(BookMeta.Generation.ORIGINAL);
        }
        Object cmd = values.get("custom-model-data");
        if (cmd != null) {
            int cmdVal = integer(cmd, -1);
            if (cmdVal >= 0) meta.setCustomModelData(cmdVal);
        }
        Object enchantments = values.get("enchantments");
        if (enchantments instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Enchantment enchantment = org.metamechanists.odysseia.kits.CustomContentResolver
                        .enchantment(String.valueOf(entry.getKey()));
                // Nunca entregar un kit reducido: si su proveedor de encantamientos no está
                // cargado, abortar el ítem y con ello la entrega atómica del kit.
                if (enchantment == null) {
                    plugin.getLogger().warning("[Kits] Encantamiento no resoluble al entregar "
                            + entry.getKey() + "; se rechaza el kit completo.");
                    return null;
                }
                meta.addEnchant(enchantment, integer(entry.getValue(), 1), true);
            }
        }
        if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armorMeta) {
            Object trimMatObj = values.get("trim-material");
            Object trimPatObj = values.get("trim-pattern");
            if (trimMatObj != null && trimPatObj != null) {
                org.bukkit.inventory.meta.trim.TrimMaterial trimMaterial = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(String.valueOf(trimMatObj).toLowerCase(Locale.ROOT)));
                org.bukkit.inventory.meta.trim.TrimPattern trimPattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(String.valueOf(trimPatObj).toLowerCase(Locale.ROOT)));
                if (trimMaterial != null && trimPattern != null) {
                    armorMeta.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(trimMaterial, trimPattern));
                }
            }
        }
        Object attributes = values.get("attributes");
        if (attributes instanceof Map<?, ?> attributeMap) {
            org.metamechanists.odysseia.kits.CustomContentResolver.applyAttributes(meta, attributeMap);
        }
        if (Boolean.parseBoolean(String.valueOf(values.containsKey("unbreakable") ? values.get("unbreakable") : false))) {
            meta.setUnbreakable(true);
        }
        if (Boolean.parseBoolean(String.valueOf(values.containsKey("hide-flags") ? values.get("hide-flags") : false))) {
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        }
        if (Boolean.parseBoolean(String.valueOf(values.containsKey("soulbound") ? values.get("soulbound") : false))) {
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "soulbound"), PersistentDataType.BYTE, (byte) 1);
        }
        Object pdcMapObj = values.get("pdc");
        if (pdcMapObj instanceof Map<?, ?> pdcMap) {
            for (Map.Entry<?, ?> entry : pdcMap.entrySet()) {
                String rawKey = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                NamespacedKey nKey = rawKey.contains(":") ? NamespacedKey.fromString(rawKey) : new NamespacedKey(plugin, rawKey);
                if (nKey != null) {
                    meta.getPersistentDataContainer().set(nKey, PersistentDataType.STRING, String.valueOf(entry.getValue()));
                }
            }
        }
        meta.getPersistentDataContainer().set(transactionKey, PersistentDataType.STRING, transaction);
        item.setItemMeta(meta);
        List<ItemStack> stacks = new ArrayList<>();
        for (int remaining = Math.max(1, amount); remaining > 0; remaining -= material.getMaxStackSize()) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(remaining, material.getMaxStackSize()));
            stacks.add(stack);
        }
        return stacks;
    }

    /**
     * Entrega la piedra del kit por la API de ProtectionStones, igual que las compras de Tebex.
     *
     * Antes despachaba {@code /ps give <alias>} por consola, pero ese comando identifica el
     * bloque por su MATERIAL: respondía "Invalid protection block" y ningún kit entregaba su
     * protección. La API resuelve por alias, que además es único cuando dos bloques comparten
     * material.
     */
    private ActionResult deliverConfiguredProtection(Player player, ConfigurationSection section) {
        String key = section.getString("protection-alias", "").trim();
        if (key.isEmpty()) return ActionResult.completed("no protection");

        String alias = plugin.getConfig().getString("protectionstones.aliases." + key, "").trim();
        int amount = section.getInt("protection-amount", 1);
        if (!key.matches("[A-Za-z0-9_-]+") || !alias.matches("[A-Za-z0-9_-]+") || amount < 1 || amount > 64) {
            return ActionResult.manual("Configuración de ProtectionStone inválida para el kit");
        }

        return ProtectionStoneDelivery.give(player, alias, amount);
    }

    private boolean containsTransaction(Player player, String transaction) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            if (transaction.equals(item.getItemMeta().getPersistentDataContainer().get(transactionKey, PersistentDataType.STRING))) return true;
        }
        return false;
    }

    private boolean fits(PlayerInventory inventory, List<ItemStack> items) {
        ItemStack[] snapshot = inventory.getStorageContents();
        int free = 0;
        for (ItemStack item : snapshot) if (item == null || item.getType().isAir()) free++;
        return free >= items.size();
    }
    private int integer(Object value, int fallback) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private String color(String value) { return org.metamechanists.odysseia.menus.MenuUtils.colorize(value); }
}
