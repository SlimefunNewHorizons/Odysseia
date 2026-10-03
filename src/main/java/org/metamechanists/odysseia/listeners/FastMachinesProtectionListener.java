package org.metamechanists.odysseia.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Makes FastMachines honor ProtectionStones ownership and membership.
 * FastMachines opens its menus through Slimefun without passing the clicked
 * location to its permission check, so the region check must happen before it.
 */
public final class FastMachinesProtectionListener implements Listener {

    private final Odysseia plugin;
    private final Method blockStorageCheckId;
    private final Method slimefunGetById;
    private final Method slimefunGetAddon;
    private final Method addonGetName;
    private final Method regionFromLocation;
    private final Method regionIsOwner;
    private final Method regionIsMember;
    private boolean unavailableLogged;

    public FastMachinesProtectionListener(Odysseia plugin) {
        this.plugin = plugin;

        Method storageCheck = null;
        Method itemById = null;
        Method itemAddon = null;
        Method addonName = null;
        Method fromLocation = null;
        Method isOwner = null;
        Method isMember = null;

        try {
            Class<?> blockStorage = SlimefunClasses.blockStorage();
            Class<?> slimefunItem = SlimefunClasses.slimefunItem();
            Class<?> psRegion = Class.forName("dev.espi.protectionstones.PSRegion");

            storageCheck = blockStorage.getMethod("checkID", Location.class);
            itemById = slimefunItem.getMethod("getById", String.class);
            itemAddon = slimefunItem.getMethod("getAddon");
            addonName = SlimefunClasses.slimefunAddon().getMethod("getName");
            fromLocation = psRegion.getMethod("fromLocation", Location.class);
            isOwner = psRegion.getMethod("isOwner", UUID.class);
            isMember = psRegion.getMethod("isMember", UUID.class);
        } catch (ReflectiveOperationException exception) {
            // ProtectionStones es opcional en staging. La integración queda inactiva hasta el
            // próximo arranque, sin una traza de ClassNotFoundException en cada inicio.
            plugin.getLogger().info("[FastMachines] Protección de Fast Machines inactiva: falta "
                    + exception.getMessage() + ".");
        }

        this.blockStorageCheckId = storageCheck;
        this.slimefunGetById = itemById;
        this.slimefunGetAddon = itemAddon;
        this.addonGetName = addonName;
        this.regionFromLocation = fromLocation;
        this.regionIsOwner = isOwner;
        this.regionIsMember = isMember;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFastMachineUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !plugin.getConfig().getBoolean("fast-machines-protection.enabled", true)) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || !isFastMachine(block)) {
            return;
        }

        Player player = event.getPlayer();
        String bypass = plugin.getConfig().getString("fast-machines-protection.bypass-permission", "odysseia.fastmachines.bypass");
        if (player.hasPermission(bypass) || canUseProtection(player, block.getLocation())) {
            return;
        }

        event.setCancelled(true);
        String message = plugin.getConfig().getString(
            "fast-machines-protection.denied-message",
            "&cNo puedes usar las Fast Machines de otra protección."
        );
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private boolean isFastMachine(Block block) {
        if (!integrationAvailable()) {
            return false;
        }

        try {
            String id = (String) blockStorageCheckId.invoke(null, block.getLocation());
            if (id == null || id.isBlank()) {
                return false;
            }

            Object slimefunItem = slimefunGetById.invoke(null, id);
            if (slimefunItem == null) {
                return false;
            }

            Object addon = slimefunGetAddon.invoke(slimefunItem);
            return addon != null && "FastMachines".equalsIgnoreCase(String.valueOf(addonGetName.invoke(addon)));
        } catch (ReflectiveOperationException exception) {
            logUnavailable(exception);
            return false;
        }
    }

    private boolean canUseProtection(Player player, Location location) {
        try {
            Object region = regionFromLocation.invoke(null, location);
            if (region == null) {
                return true;
            }

            UUID uuid = player.getUniqueId();
            return (boolean) regionIsOwner.invoke(region, uuid) || (boolean) regionIsMember.invoke(region, uuid);
        } catch (ReflectiveOperationException exception) {
            logUnavailable(exception);
            return false;
        }
    }

    private boolean integrationAvailable() {
        return blockStorageCheckId != null && slimefunGetById != null && slimefunGetAddon != null
            && addonGetName != null && regionFromLocation != null && regionIsOwner != null && regionIsMember != null;
    }

    private void logUnavailable(ReflectiveOperationException exception) {
        if (!unavailableLogged) {
            unavailableLogged = true;
            plugin.getLogger().log(Level.WARNING, "[FastMachines] No se pudo validar la protección de una máquina.", exception);
        }
    }
}
