package org.metamechanists.odysseia.listeners;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.metamechanists.odysseia.modalities.ModalityService;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Blindaje activo de jugabilidad vainilla en la modalidad Clásico contra Slimefun y sus addons.
 *
 * <p>En Clásico (clasico, clasico_nether, clasico_the_end) el juego debe mantenerse 100% vainilla.
 * Este listener actúa como salvaguarda integral del servidor:
 * <ul>
 *   <li>Filtra y elimina cualquier drop de ítems de Slimefun o addons al morir entidades en Clásico.</li>
 *   <li>Limpia metadatos residuales en BlockStorage al romper bloques en Clásico para evitar que
 *       máquinas fantasmas o Lucky Blocks activen efectos en el mundo vainilla.</li>
 *   <li>Impide la colocación y el uso interactivo de ítems/herramientas de Slimefun en Clásico.</li>
 *   <li>Retira cualquier ítem de Slimefun del inventario, armadura y manos al entrar o interactuar en Clásico
 *       y lo deja en CONSIGNA (plugins/Odysseia/clasico-consigna/&lt;uuid&gt;.yml); se devuelve al salir de Clásico.</li>
 * </ul>
 * <p>Hasta el 2026-09-20 la purga destruía los objetos: Pasiente perdió su set completo de armadura
 * SlimeTinker al cruzar a Clásico el 2026-09-18 (INC-066). Nada que sea del jugador se destruye aquí.</p>
 * <ul>
 * </ul>
 * </p>
 */
public final class ClasicoSlimefunGuardListener implements Listener {

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
    private Method blockStorageHasBlockInfo;
    private Method blockStorageClearBlockInfo;
    private final File consignaDir;

    public ClasicoSlimefunGuardListener(Plugin plugin, ModalityService modalityService) {
        this.plugin = plugin;
        this.modalityService = modalityService;
        this.consignaDir = plugin == null ? new File("clasico-consigna") : new File(plugin.getDataFolder(), "clasico-consigna");
        initSlimefunReflection();
    }

    private void initSlimefunReflection() {
        for (String base : List.of("com.github.drakescraft_labs.slimefun4", "io.github.thebusybiscuit.slimefun4")) {
            try {
                Class<?> itemClass = Class.forName(base + ".api.items.SlimefunItem");
                this.slimefunGetByItem = itemClass.getMethod("getByItem", ItemStack.class);
            } catch (ReflectiveOperationException ignored) {
            }

            for (String bsClass : List.of(base + ".legacy.api.BlockStorage", base + ".api.BlockStorage",
                    "me.mrCookieSlime.Slimefun.api.BlockStorage")) {
                try {
                    Class<?> storageClass = Class.forName(bsClass);
                    this.blockStorageHasBlockInfo = storageClass.getMethod("hasBlockInfo", Block.class);
                    this.blockStorageClearBlockInfo = storageClass.getMethod("clearBlockInfo", Block.class);
                    break;
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
    }

    public boolean isClasicoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("clasico")) return true;
        if (modalityService != null) {
            return "clasico".equalsIgnoreCase(modalityService.resolve(lower).id());
        }
        return false;
    }

    /**
     * Determina si el mundo pertenece a la modalidad Clásico.
     */
    public boolean isClasico(World world) {
        if (world == null) return false;
        return isClasicoName(world.getName());
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
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                String ns = key.getNamespace().toLowerCase(Locale.ROOT);
                if (SLIMEFUN_NAMESPACES.contains(ns) || ns.startsWith("slimefun") || ns.startsWith("equivalency")) {
                    return true;
                }
            }

            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        if (line == null) continue;
                        String stripped = ChatColor.stripColor(line).toLowerCase(Locale.ROOT);
                        if (stripped.contains("slimefun")
                                || stripped.contains("cultivation")
                                || stripped.contains("equivalency")
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
     * Purga activamente todos los ítems de Slimefun o addons del inventario del jugador.
     */
    public int purgeSlimefunItems(Player player) {
        if (player == null || !isClasico(player.getWorld())) {
            return 0;
        }
        PlayerInventory inv = player.getInventory();
        List<ItemStack> retirados = new ArrayList<>();
        List<Integer> ranuras = new ArrayList<>();

        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (isSlimefunOrCustomItem(contents[i])) {
                retirados.add(contents[i].clone());
                ranuras.add(i);
            }
        }
        ItemStack offHand = inv.getItemInOffHand();
        boolean quitarOffHand = isSlimefunOrCustomItem(offHand);
        if (quitarOffHand) {
            retirados.add(offHand.clone());
        }
        ItemStack[] armors = inv.getArmorContents();
        boolean armorModified = false;
        for (int i = 0; i < armors.length; i++) {
            if (isSlimefunOrCustomItem(armors[i])) {
                retirados.add(armors[i].clone());
                armors[i] = null;
                armorModified = true;
            }
        }
        if (retirados.isEmpty()) {
            return 0;
        }

        // Primero se guarda en consigna y solo si quedo en disco se vacia: antes que perder equipo
        // de un jugador, prefiero dejarlo pasar y avisar en consola.
        if (!guardarConsigna(player, retirados)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&6DrakesCraft &8· &cNo pude guardar tus objetos de Slimefun en consigna; avisa al staff antes de usarlos en Clásico."));
            return 0;
        }
        for (int i : ranuras) {
            inv.setItem(i, null);
        }
        if (quitarOffHand) {
            inv.setItemInOffHand(null);
        }
        if (armorModified) {
            inv.setArmorContents(armors);
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&6DrakesCraft &8· &e" + retirados.size() + " objeto(s) de Slimefun quedaron en consigna (no se usan en Clásico). "
                + "&aSe te devuelven al salir de Clásico."));
        return retirados.size();
    }

    private File ficheroConsigna(UUID uuid) {
        return new File(consignaDir, uuid + ".yml");
    }

    /** Anade objetos a la consigna del jugador. Devuelve false si no quedo escrito y releido. */
    private synchronized boolean guardarConsigna(Player player, List<ItemStack> nuevos) {
        try {
            if (!consignaDir.isDirectory() && !consignaDir.mkdirs()) {
                return false;
            }
            File f = ficheroConsigna(player.getUniqueId());
            YamlConfiguration yml = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            List<ItemStack> todos = new ArrayList<>(cargarConsigna(yml));
            todos.addAll(nuevos);
            yml.set("jugador", player.getName());
            yml.set("actualizado", System.currentTimeMillis());
            yml.set("objetos", todos);
            yml.save(f);
            List<ItemStack> releidos = cargarConsigna(YamlConfiguration.loadConfiguration(f));
            if (releidos.size() != todos.size()) {
                if (plugin != null) plugin.getLogger().warning("[Clasico] consigna de " + player.getName() + " no cuadra al releer: " + releidos.size() + " != " + todos.size());
                return false;
            }
            if (plugin != null) plugin.getLogger().info("[Clasico] " + nuevos.size() + " objeto(s) Slimefun de " + player.getName() + " en consigna (" + todos.size() + " en total).");
            return true;
        } catch (IOException | RuntimeException e) {
            if (plugin != null) plugin.getLogger().log(Level.WARNING, "[Clasico] no se pudo guardar la consigna de " + player.getName(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> cargarConsigna(YamlConfiguration yml) {
        List<ItemStack> out = new ArrayList<>();
        List<?> raw = yml.getList("objetos");
        if (raw == null) return out;
        for (Object o : raw) {
            if (o instanceof ItemStack it && it.getType() != org.bukkit.Material.AIR) {
                out.add(it);
            } else if (o instanceof Map<?, ?> m) {
                try {
                    out.add(ItemStack.deserialize((Map<String, Object>) m));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    /** Devuelve la consigna al jugador fuera de Clasico: al inventario y, lo que no cabe, al suelo. */
    public int devolverConsigna(Player player) {
        if (player == null || isClasico(player.getWorld())) {
            return 0;
        }
        File f = ficheroConsigna(player.getUniqueId());
        if (!f.isFile()) {
            return 0;
        }
        List<ItemStack> objetos = cargarConsigna(YamlConfiguration.loadConfiguration(f));
        if (objetos.isEmpty()) {
            f.delete();
            return 0;
        }
        int devueltos = 0;
        for (ItemStack it : objetos) {
            Map<Integer, ItemStack> sobra = player.getInventory().addItem(it);
            for (ItemStack resto : sobra.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), resto);
            }
            devueltos++;
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
        if (plugin != null) plugin.getLogger().info("[Clasico] consigna devuelta a " + player.getName() + ": " + devueltos + " objeto(s).");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&6DrakesCraft &8· &aTe devolví " + devueltos + " objeto(s) de Slimefun que dejaste en consigna al entrar a Clásico."));
        return devueltos;
    }

    private void clearSlimefunBlockInfo(Block block) {
        if (blockStorageHasBlockInfo != null && blockStorageClearBlockInfo != null) {
            try {
                boolean hasInfo = (boolean) blockStorageHasBlockInfo.invoke(null, block);
                if (hasInfo) {
                    blockStorageClearBlockInfo.invoke(null, block);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isClasico(player.getWorld())) {
            purgeSlimefunItems(player);
        } else if (plugin != null) {
            // un tick despues: que el cambio de inventario por modalidad (si lo hay) ya haya pasado
            plugin.getServer().getScheduler().runTask(plugin, () -> devolverConsigna(player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isClasico(player.getWorld())) {
            purgeSlimefunItems(player);
        } else if (plugin != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> devolverConsigna(player), 40L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isClasico(event.getRespawnLocation().getWorld()) && plugin != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> purgeSlimefunItems(player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isClasico(player.getWorld())) return;

        ItemStack current = event.getCurrentItem();
        if (isSlimefunOrCustomItem(current)) {
            event.setCancelled(true);
            if (guardarConsigna(player, List.of(current.clone()))) {
                event.setCurrentItem(null);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&6DrakesCraft &8· &eEse objeto de Slimefun quedó en consigna; se te devuelve al salir de Clásico."));
            }
            return;
        }

        ItemStack cursor = event.getCursor();
        if (isSlimefunOrCustomItem(cursor)) {
            event.setCancelled(true);
            if (guardarConsigna(player, List.of(cursor.clone()))) {
                event.getView().setCursor(null);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&6DrakesCraft &8· &eEse objeto de Slimefun quedó en consigna; se te devuelve al salir de Clásico."));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!isClasico(event.getEntity().getWorld())) return;
        if (isSlimefunOrCustomItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!isClasico(event.getPlayer().getWorld())) return;
        ItemStack soltado = event.getItemDrop().getItemStack();
        if (isSlimefunOrCustomItem(soltado)) {
            if (guardarConsigna(event.getPlayer(), List.of(soltado.clone()))) {
                event.getItemDrop().remove();
            } else {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!isClasico(event.getEntity().getWorld())) return;
        event.getDrops().removeIf(this::isSlimefunOrCustomItem);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isClasico(event.getBlock().getWorld())) return;
        clearSlimefunBlockInfo(event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isClasico(event.getBlock().getWorld())) return;
        if (isSlimefunOrCustomItem(event.getItemInHand())) {
            event.setCancelled(true);
            purgeSlimefunItems(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isClasico(event.getPlayer().getWorld())) return;
        ItemStack item = event.getItem();
        if (isSlimefunOrCustomItem(item)) {
            event.setCancelled(true);
            purgeSlimefunItems(event.getPlayer());
        }
    }
}
