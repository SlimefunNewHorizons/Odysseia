package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.metamechanists.odysseia.integrations.SlimefunGuideBridge;
import org.metamechanists.odysseia.integrations.SFMasterPassExpiry;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.lang.reflect.Method;

public class SFMasterWatcherListener implements Listener {

    private static final String SFMASTER_CHEAT_PERMISSION = "slimefun.cheat.items";
    private static final String SFMASTER_ACTIVE_PERMISSION = "odysseia.sfmaster.active";
    private static final String SFMASTER_BYPASS_PERMISSION = "odysseia.sfmaster.bypass_marking";
    private static final String SFMASTER_MARKER_LORE = "§cGenerado por SFMaster - No comerciable";
    private static final String LABORATORY_WORLD = "laboratorio";
    private static final long OWNER_MISMATCH_ALERT_INTERVAL_MILLIS = 2_000L;
    private static final long OWNER_MISMATCH_ALERT_RETENTION_MILLIS = 60_000L;
    private static final long OWNER_MISMATCH_LOG_INTERVAL_MILLIS = 60_000L;

    private final Plugin plugin;
    private final NamespacedKey sfMasterKey;
    private final NamespacedKey sfMasterOwnerKey;
    
    private final File blocksFile;
    private final YamlConfiguration blocksConfig;
    private final Set<String> sfMasterBlocks = new HashSet<>();
    private final Map<String, String> sfMasterBlockOwners = new HashMap<>();
    private final Map<String, BrokenBlock> brokenSFMasterBlocks = new HashMap<>();
    private final Map<String, Long> ownerMismatchAlerts = new HashMap<>();
    private final Map<String, Long> ownerMismatchLogs = new HashMap<>();
    private Set<String> blockedAddons;
    private Set<String> blockedIdPrefixes;
    private Set<String> blockedIdFragments;
    private Set<String> blockedMaterials;
    private Set<String> approvedAddons;
    private final Method slimefunGetByItem;
    private final SlimefunGuideBridge slimefunGuide;
    private boolean reflectionWarningLogged;

    public SFMasterWatcherListener(Plugin plugin) {
        this.plugin = plugin;
        this.sfMasterKey = new NamespacedKey(plugin, "sfmaster_item");
        this.sfMasterOwnerKey = new NamespacedKey(plugin, "sfmaster_item_owner");
        this.blocksFile = new File(plugin.getDataFolder(), "sfmaster_blocks.yml");
        this.blocksConfig = YamlConfiguration.loadConfiguration(blocksFile);
        reloadConfiguration();
        this.slimefunGetByItem = findSlimefunGetByItem();
        this.slimefunGuide = new SlimefunGuideBridge(plugin);
        loadBlocks();
    }

    /** Reloads only audit rules; claim authorization belongs to Slimefun Core. */
    public void reloadConfiguration() {
        this.blockedAddons = normalizedConfigSet("sfmaster-audit.blocked-addons");
        this.blockedIdPrefixes = normalizedConfigSet("sfmaster-audit.blocked-id-prefixes");
        this.blockedIdFragments = normalizedConfigSet("sfmaster-audit.blocked-id-fragments");
        this.blockedMaterials = normalizedConfigSet("sfmaster-audit.blocked-materials");
        this.approvedAddons = normalizedConfigSet("sfmaster-audit.approved-addons");
    }

    private Set<String> normalizedConfigSet(String path) {
        Set<String> values = new HashSet<>();
        for (String value : plugin.getConfig().getStringList(path)) {
            values.add(value.toUpperCase(Locale.ROOT));
        }
        return values;
    }

    private Method findSlimefunGetByItem() {
        try {
            Class<?> slimefunItem = Class.forName("io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem");
            return slimefunItem.getMethod("getByItem", ItemStack.class);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().severe("No se pudo enlazar la API de Slimefun para proteger SFMaster: " + exception.getMessage());
            return null;
        }
    }

    private SfItemDescriptor describeSlimefunItem(ItemStack item) {
        if (slimefunGetByItem == null || item == null) {
            return null;
        }

        try {
            Object slimefunItem = slimefunGetByItem.invoke(null, item);
            if (slimefunItem == null) {
                return null;
            }
            String id = String.valueOf(slimefunItem.getClass().getMethod("getId").invoke(slimefunItem));
            Object addon = slimefunItem.getClass().getMethod("getAddon").invoke(slimefunItem);
            String addonName = addon == null ? "" : String.valueOf(addon.getClass().getMethod("getName").invoke(addon));
            return new SfItemDescriptor(id.toUpperCase(Locale.ROOT), addonName.toUpperCase(Locale.ROOT));
        } catch (ReflectiveOperationException exception) {
            if (!reflectionWarningLogged) {
                reflectionWarningLogged = true;
                plugin.getLogger().warning("No se pudo identificar un item de SFMaster: " + exception.getMessage());
            }
            return null;
        }
    }

    private String blockedReason(ItemStack item) {
        String material = item.getType().name();
        if (blockedMaterials.contains(material) || item.getType().getMaxDurability() > 0) {
            return "herramientas, armas, armaduras y materiales de alto valor estan bloqueados";
        }

        SfItemDescriptor descriptor = describeSlimefunItem(item);
        if (descriptor == null) {
            return "el item no pudo validarse de forma segura";
        }
        if (blockedAddons.stream().anyMatch(descriptor.addon()::contains)) {
            return "ese addon esta bloqueado para SFMaster";
        }
        if (blockedIdPrefixes.stream().anyMatch(descriptor.id()::startsWith)
                || blockedIdFragments.stream().anyMatch(descriptor.id()::contains)) {
            return "ese tipo de item esta bloqueado para SFMaster";
        }
        return null;
    }

    private void loadBlocks() {
        sfMasterBlocks.clear();
        sfMasterBlockOwners.clear();
        List<String> list = blocksConfig.getStringList("blocks");
        if (list != null) {
            sfMasterBlocks.addAll(list);
        }
        var owners = blocksConfig.getConfigurationSection("owners");
        if (owners != null) {
            for (String location : owners.getKeys(false)) {
                String owner = owners.getString(location);
                if (owner != null && !owner.isBlank()) {
                    sfMasterBlockOwners.put(location, owner);
                }
            }
        }
    }

    private void saveBlocks() {
        blocksConfig.set("blocks", new ArrayList<>(sfMasterBlocks));
        blocksConfig.set("owners", null);
        sfMasterBlockOwners.forEach((location, owner) -> blocksConfig.set("owners." + location, owner));
        try {
            blocksConfig.save(blocksFile);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar sfmaster_blocks.yml: " + e.getMessage());
        }
    }

    private boolean isSFMasterItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(sfMasterKey, PersistentDataType.BYTE);
    }

    private boolean belongsTo(ItemStack item, UUID playerId) {
        if (!isSFMasterItem(item)) return true;
        ItemMeta meta = item.getItemMeta();
        String owner = meta == null ? null : meta.getPersistentDataContainer()
                .get(sfMasterOwnerKey, PersistentDataType.STRING);
        return owner == null || owner.equals(playerId.toString());
    }

    /**
     * SFMaster is deliberately usable inside the isolated laboratory. Keeping this decision in a
     * small pure helper makes it harder for a future inventory handler to accidentally reopen a
     * route into another modality.
     */
    static boolean isLaboratoryWorld(String worldName) {
        return LABORATORY_WORLD.equalsIgnoreCase(worldName == null ? "" : worldName);
    }

    /** Only automation entirely contained in the laboratory may carry marked SFMaster items. */
    static boolean allowsLaboratoryInventoryMove(String sourceWorld, String destinationWorld) {
        return isLaboratoryWorld(sourceWorld) && isLaboratoryWorld(destinationWorld);
    }

    private static String inventoryWorldName(Inventory inventory) {
        Location location = inventory == null ? null : inventory.getLocation();
        return location == null || location.getWorld() == null ? null : location.getWorld().getName();
    }

    private boolean isSfMasterActive(Player player) {
        return (player.hasPermission(SFMASTER_CHEAT_PERMISSION) || player.hasPermission(SFMASTER_ACTIVE_PERMISSION))
                && !player.hasPermission(SFMASTER_BYPASS_PERMISSION);
    }

    private boolean hasSfMasterAccess(Player player) {
        return player.hasPermission(SFMASTER_CHEAT_PERMISSION)
                || player.hasPermission(SFMASTER_ACTIVE_PERMISSION)
                || player.hasPermission("slimefun.cheat.items.bypass");
    }

    public void deliverGuidesToOnlinePassHolders() {
        Bukkit.getOnlinePlayers().forEach(this::ensureCheatGuide);
    }

    /** Runs on the main thread and only touches inventories belonging to online players. */
    public void startGuideCleanup() {
        long seconds = Math.max(10L, plugin.getConfig().getLong("sfmaster-guide.cleanup-interval-seconds", 30L));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::purgeExpiredGuides), seconds * 20L, seconds * 20L);
    }

    private void ensureCheatGuide(Player player) {
        if (!plugin.getConfig().getBoolean("sfmaster-guide.enabled", true) || !isSfMasterActive(player)) {
            return;
        }
        if (hasOwnedGuide(player.getInventory(), player.getUniqueId()) || hasOwnedGuide(player.getEnderChest(), player.getUniqueId())) return;
        ItemStack guide = slimefunGuide.createOwnedCheatGuide(player.getUniqueId(), SFMasterPassExpiry.forPlayer(plugin, player));
        if (guide == null) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(guide);
        if (!leftovers.isEmpty()) {
            player.sendMessage("§eSFMaster activo, pero necesitas un espacio libre para recibir la guía Cheat.");
        }
    }

    private boolean hasOwnedGuide(Inventory inventory, UUID owner) {
        for (ItemStack item : inventory.getContents()) {
            if (slimefunGuide.ownerOf(item).filter(owner::equals).isPresent()) return true;
        }
        return false;
    }

    private void purgeExpiredGuides(Player player) {
        boolean ownerLostPass = !isSfMasterActive(player);
        purgeInventory(player.getInventory(), player.getUniqueId(), ownerLostPass, 0);
        purgeInventory(player.getEnderChest(), player.getUniqueId(), ownerLostPass, 0);
    }

    /** Recursively removes only owned guides that are expired or whose owner lost the pass. */
    private int purgeInventory(Inventory inventory, UUID onlineOwner, boolean ownerLostPass, int depth) {
        if (inventory == null || depth > 4) return 0;
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) continue;
            UUID owner = slimefunGuide.ownerOf(item).orElse(null);
            if (owner != null && (slimefunGuide.isExpired(item) || (ownerLostPass && owner.equals(onlineOwner)))) {
                inventory.setItem(slot, null);
                removed++;
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta stateMeta && stateMeta.getBlockState() instanceof ShulkerBox shulker) {
                removed += purgeInventory(shulker.getInventory(), onlineOwner, ownerLostPass, depth + 1);
                stateMeta.setBlockState(shulker);
                item.setItemMeta(stateMeta);
                inventory.setItem(slot, item);
            }
        }
        return removed;
    }

    private void removeHeldGuide(Player player, PlayerInteractEvent event) {
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
        else player.getInventory().setItemInMainHand(null);
    }

    private void markItem(ItemStack item, String owner) {
        if (item == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.getPersistentDataContainer().set(sfMasterKey, PersistentDataType.BYTE, (byte) 1);
        if (owner != null && !owner.isBlank()) {
            meta.getPersistentDataContainer().set(sfMasterOwnerKey, PersistentDataType.STRING, owner);
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        if (!lore.contains(SFMASTER_MARKER_LORE)) {
            lore.add("");
            lore.add(SFMASTER_MARKER_LORE);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isSFMasterItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNo puedes soltar ítems generados por el modo SFMaster.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (isSFMasterItem(item) && !belongsTo(item, player.getUniqueId())) {
            event.setCancelled(true);
            String alertKey = player.getUniqueId() + ":" + event.getItem().getUniqueId();
            long now = System.currentTimeMillis();
            if (shouldAlertOwnerMismatch(ownerMismatchAlerts, alertKey, now)) {
                player.sendMessage("§cEse ítem SFMaster pertenece a otro jugador.");
            }
            if (shouldLogOwnerMismatch(ownerMismatchLogs, player.getUniqueId().toString(), now)) {
                plugin.getLogger().warning("[SFMaster] " + player.getName()
                        + " intentó recoger un ítem marcado de otro propietario.");
            }
        }
    }

    /**
     * Limits repeated feedback from a protected item left within a player's pickup radius.
     * The pickup remains cancelled on every event; only player-facing and console notices are throttled.
     */
    static boolean shouldAlertOwnerMismatch(Map<String, Long> alerts, String alertKey, long nowMillis) {
        alerts.entrySet().removeIf(entry -> nowMillis - entry.getValue() > OWNER_MISMATCH_ALERT_RETENTION_MILLIS);
        Long previousAlert = alerts.get(alertKey);
        if (previousAlert != null && nowMillis >= previousAlert
                && nowMillis - previousAlert < OWNER_MISMATCH_ALERT_INTERVAL_MILLIS) {
            return false;
        }
        alerts.put(alertKey, nowMillis);
        return true;
    }

    /**
     * Throttles the console notice separately from the player-facing message.
     * The player alert is keyed per item entity so each distinct item is reported once,
     * but the console only needs one line per player and window: a player standing on a pile
     * of protected items would otherwise emit a warning every alert interval
     * per entity for as long as they stay there.
     */
    static boolean shouldLogOwnerMismatch(Map<String, Long> logs, String playerKey, long nowMillis) {
        logs.entrySet().removeIf(entry -> nowMillis - entry.getValue() > OWNER_MISMATCH_LOG_INTERVAL_MILLIS);
        Long previousLog = logs.get(playerKey);
        if (previousLog != null && nowMillis >= previousLog
                && nowMillis - previousLog < OWNER_MISMATCH_LOG_INTERVAL_MILLIS) {
            return false;
        }
        logs.put(playerKey, nowMillis);
        return true;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (isSFMasterItem(item)) {
            Block block = event.getBlockPlaced();
            String locKey = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
            sfMasterBlocks.add(locKey);
            ItemMeta meta = item.getItemMeta();
            String owner = meta == null ? null : meta.getPersistentDataContainer()
                    .get(sfMasterOwnerKey, PersistentDataType.STRING);
            if (owner != null) {
                sfMasterBlockOwners.put(locKey, owner);
            }
            saveBlocks();
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String locKey = block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
        if (sfMasterBlocks.contains(locKey)) {
            sfMasterBlocks.remove(locKey);
            String owner = sfMasterBlockOwners.remove(locKey);
            saveBlocks();
            brokenSFMasterBlocks.put(locKey, new BrokenBlock(System.currentTimeMillis(), owner));
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        Location loc = itemEntity.getLocation();
        String targetKey = null;
        long now = System.currentTimeMillis();

        String owner = null;
        for (Map.Entry<String, BrokenBlock> entry : brokenSFMasterBlocks.entrySet()) {
            if (now - entry.getValue().timestamp() > 1000) {
                continue;
            }
            String[] parts = entry.getKey().split(",");
            if (parts[0].equals(loc.getWorld().getName())) {
                double bx = Double.parseDouble(parts[1]) + 0.5;
                double by = Double.parseDouble(parts[2]) + 0.5;
                double bz = Double.parseDouble(parts[3]) + 0.5;
                Location bLoc = new Location(loc.getWorld(), bx, by, bz);
                if (loc.distanceSquared(bLoc) < 2.25) { // 1.5 bloques de radio
                    targetKey = entry.getKey();
                    owner = entry.getValue().owner();
                    break;
                }
            }
        }

        if (targetKey != null) {
            ItemStack item = itemEntity.getItemStack();
            markItem(item, owner);
            itemEntity.setItemStack(item);
            brokenSFMasterBlocks.remove(targetKey);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String world = event.getPlayer().getWorld().getName();
        if ("laboratorio".equalsIgnoreCase(world)) {
            return; // En el mundo laboratorio el modo cheat y comandos de Slimefun están 100% permitidos
        }
        String msg = event.getMessage().toLowerCase();
        if (isSfMasterActive(event.getPlayer())
                && (msg.equals("/sf cheat") || msg.equals("/slimefun cheat"))) {
            event.setCancelled(true);
            ensureCheatGuide(event.getPlayer());
            if (!slimefunGuide.openOwnedCheatGuide(event.getPlayer())) {
                event.getPlayer().sendMessage("§eUsa la guía SFMaster que recibiste para abrir la Cheat Sheet.");
            }
            return;
        }
        if (isSfMasterActive(event.getPlayer())
                && (msg.startsWith("/sf give") || msg.startsWith("/slimefun give"))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cSFMaster solo permite reclamar desde la Cheat Sheet controlada.");
            return;
        }
        if (msg.startsWith("/ah sell") || msg.startsWith("/ah sellbid") || msg.startsWith("/ahca sell") || msg.startsWith("/crazyauctions sell")) {
            Player player = event.getPlayer();
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (isSFMasterItem(hand)) {
                event.setCancelled(true);
                player.sendMessage("§cNo puedes vender ítems de SFMaster en la subasta.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCheatGuideUse(PlayerInteractEvent event) {
        ItemStack guide = event.getItem();
        if (!slimefunGuide.isCheatGuide(guide)) return;
        Player player = event.getPlayer();
        var owner = slimefunGuide.ownerOf(guide);
        if (owner.isPresent()) {
            event.setCancelled(true);
            if (!owner.get().equals(player.getUniqueId()) || slimefunGuide.isExpired(guide) || !isSfMasterActive(player)) {
                removeHeldGuide(player, event);
                player.sendMessage("§cTu guía SFMaster venció o no te pertenece y fue eliminada.");
                return;
            }
            slimefunGuide.openOwnedCheatGuide(player);
            return;
        }
        if (!hasSfMasterAccess(player)) {
            event.setCancelled(true);
            player.sendMessage("§cTu pase SFMaster expiró. Esta guía Cheat ya no se puede usar.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            purgeExpiredGuides(event.getPlayer());
            ensureCheatGuide(event.getPlayer());
        });
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(item -> isSFMasterItem(item));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isSFMasterItem(event.getItem()) && !allowsLaboratoryInventoryMove(
                inventoryWorldName(event.getSource()), inventoryWorldName(event.getDestination()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!belongsTo(event.getMainHandItem(), event.getPlayer().getUniqueId())
                || !belongsTo(event.getOffHandItem(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNo puedes usar un ítem SFMaster de otro jugador.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            purgeInventory(event.getView().getTopInventory(), player.getUniqueId(), false, 0);
            purgeExpiredGuides(player);
        }

        if (event.getWhoClicked() instanceof Player player) {
            ItemStack hotbarItem = event.getHotbarButton() >= 0
                    ? player.getInventory().getItem(event.getHotbarButton())
                    : null;
            if (!belongsTo(event.getCurrentItem(), player.getUniqueId())
                    || !belongsTo(event.getCursor(), player.getUniqueId())
                    || !belongsTo(hotbarItem, player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage("§cNo puedes usar un ítem SFMaster de otro jugador.");
                return;
            }
            // El laboratorio tiene inventarios separados por InvSwitcher y una lista blanca de
            // comandos: permitir su maquinaria aquí no abre una ruta hacia Survival u otra isla.
            if (isLaboratoryWorld(player.getWorld().getName())) {
                return;
            }
            if (event.getHotbarButton() >= 0
                    && isSFMasterItem(hotbarItem)
                    && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                player.sendMessage("§cNo puedes guardar ítems de SFMaster aquí.");
                return;
            }
        }

        // Permitir interacciones puramente dentro del inventario del jugador
        if (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.PLAYER) {
            // Permitir moverse cosas dentro del propio inventario, EXCEPTO si están haciendo shift-click hacia otro inventario no permitido
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                if (event.getInventory().getType() != InventoryType.PLAYER && event.getInventory().getType() != InventoryType.CRAFTING) {
                    if (isSFMasterItem(event.getCurrentItem())) {
                        event.setCancelled(true);
                        if (event.getWhoClicked() instanceof Player) {
                            ((Player) event.getWhoClicked()).sendMessage("§cNo puedes guardar ítems de SFMaster aquí.");
                        }
                    }
                }
            }
            return;
        }

        // Si están interactuando con otro inventario (cofre, tradeo, etc.)
        if (isSFMasterItem(event.getCurrentItem()) || isSFMasterItem(event.getCursor())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player) {
                ((Player) event.getWhoClicked()).sendMessage("§cNo puedes mover ítems de SFMaster a este inventario.");
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory == null) {
            return;
        }

        boolean touchesExternalInventory = event.getRawSlots().stream()
                .anyMatch(slot -> slot < inventory.getSize());

        ItemStack cursor = event.getOldCursor();
        if (touchesExternalInventory && isSFMasterItem(cursor)) {
            if (event.getWhoClicked() instanceof Player player
                    && isLaboratoryWorld(player.getWorld().getName())
                    && belongsTo(cursor, player.getUniqueId())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage("§cNo puedes arrastrar ítems de SFMaster a este inventario.");
            }
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame || event.getRightClicked().getType().name().equals("ARMOR_STAND")) {
            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            if (isSFMasterItem(hand)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cNo puedes colocar ítems de SFMaster aquí.");
            }
            ItemStack offhand = event.getPlayer().getInventory().getItemInOffHand();
            if (isSFMasterItem(offhand)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Reports suspicious legacy objects without deleting them. Unmarked objects
     * may have been crafted legitimately, so staff must review every candidate.
     */
    public AuditResult audit(Player player) {
        AuditAccumulator accumulator = new AuditAccumulator();
        auditInventory(player.getInventory(), accumulator, 0);
        auditInventory(player.getEnderChest(), accumulator, 0);
        return new AuditResult(accumulator.marked, accumulator.guides, Map.copyOf(accumulator.suspicious));
    }

    private void auditInventory(Inventory inventory, AuditAccumulator accumulator, int depth) {
        if (inventory == null || depth > 4) return;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            if (isSFMasterItem(item)) accumulator.marked += item.getAmount();
            if (slimefunGuide.isCheatGuide(item)) accumulator.guides += item.getAmount();

            SfItemDescriptor descriptor = describeSlimefunItem(item);
            if (descriptor != null && isSuspiciousLegacy(item, descriptor) && !isSFMasterItem(item)) {
                accumulator.suspicious.merge(descriptor.id(), item.getAmount(), Integer::sum);
            }
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta stateMeta && stateMeta.getBlockState() instanceof ShulkerBox shulker) {
                auditInventory(shulker.getInventory(), accumulator, depth + 1);
            }
        }
    }

    private boolean isSuspiciousLegacy(ItemStack item, SfItemDescriptor descriptor) {
        boolean approvedAddon = approvedAddons.stream().anyMatch(descriptor.addon()::contains);
        return !approvedAddon || blockedReason(item) != null;
    }

    public record AuditResult(int markedItems, int cheatGuides, Map<String, Integer> suspiciousLegacy) {
    }

    private static final class AuditAccumulator {
        private int marked;
        private int guides;
        private final Map<String, Integer> suspicious = new LinkedHashMap<>();
    }

    private record SfItemDescriptor(String id, String addon) {}

    private record BrokenBlock(long timestamp, String owner) {}
}
