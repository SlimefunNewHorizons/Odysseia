package org.metamechanists.odysseia.integrations;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** Bridges the optional Slimefun Cheat guide and gives SFMaster copies an owner. */
public final class SlimefunGuideBridge {
    private final Plugin plugin;
    private final NamespacedKey ownerKey;
    private final NamespacedKey expiryKey;
    private final NamespacedKey sfMasterItemKey;
    private Method getGuideItem;
    private Method openGuide;
    private Object cheatMode;
    private boolean unavailableLogged;

    public SlimefunGuideBridge(Plugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "sfmaster_guide_owner");
        this.expiryKey = new NamespacedKey(plugin, "sfmaster_guide_expiry");
        this.sfMasterItemKey = new NamespacedKey(plugin, "sfmaster_item");
        try {
            Class<?> guideMode = Class.forName("io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode");
            Class<?> guide = Class.forName("io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide");
            this.cheatMode = Enum.valueOf(guideMode.asSubclass(Enum.class), "CHEAT_MODE");
            this.getGuideItem = guide.getMethod("getItem", guideMode);
            try {
                this.openGuide = guide.getMethod("openCheatMenu", Player.class);
            } catch (NoSuchMethodException ignored) {
                try {
                    this.openGuide = guide.getMethod("openMainMenuAsync", Player.class, guideMode, int.class);
                } catch (NoSuchMethodException ignored2) {
                    this.openGuide = guide.getMethod("openGuide", Player.class, ItemStack.class);
                }
            }
        } catch (ReflectiveOperationException exception) {
            logUnavailable(exception);
        }
    }

    public ItemStack createCheatGuide() {
        if (getGuideItem == null || cheatMode == null) return null;
        try {
            ItemStack guide = (ItemStack) getGuideItem.invoke(null, cheatMode);
            return guide == null ? null : guide.clone();
        } catch (ReflectiveOperationException exception) {
            logUnavailable(exception);
            return null;
        }
    }

    /** Marks a guide with its purchaser and the exact temporary-rank expiry. */
    public ItemStack createOwnedCheatGuide(UUID owner, long expiryEpochSecond) {
        ItemStack guide = createCheatGuide();
        return guide == null ? null : stampOwnedGuide(guide, owner, expiryEpochSecond);
    }

    public ItemStack stampOwnedGuide(ItemStack item, UUID owner, long expiryEpochSecond) {
        if (!isCheatGuide(item)) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, owner.toString());
        data.set(expiryKey, PersistentDataType.LONG, expiryEpochSecond);
        // Reuse the existing SFMaster anti-transfer guard without adding visible lore.
        data.set(sfMasterItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCheatGuide(ItemStack candidate) {
        ItemStack template = createCheatGuide();
        if (candidate == null || template == null) return false;
        ItemStack normalized = candidate.clone();
        ItemMeta meta = normalized.getItemMeta();
        if (meta != null) {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.remove(ownerKey);
            data.remove(expiryKey);
            data.remove(sfMasterItemKey);
            normalized.setItemMeta(meta);
        }
        normalized.setAmount(1);
        template.setAmount(1);
        return normalized.isSimilar(template);
    }

    public Optional<UUID> ownerOf(ItemStack item) {
        if (!isCheatGuide(item) || !item.hasItemMeta()) return Optional.empty();
        String value = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public boolean isExpired(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        Long expiry = item.getItemMeta().getPersistentDataContainer().get(expiryKey, PersistentDataType.LONG);
        return expiry != null && expiry > 0 && expiry <= (System.currentTimeMillis() / 1000L);
    }

    /** Opens cheat mode directly because ownership metadata intentionally changes ItemStack equality. */
    public boolean openOwnedCheatGuide(Player player) {
        if (openGuide == null) return false;
        try {
            if (openGuide.getParameterCount() == 1) {
                openGuide.invoke(null, player);
                return true;
            } else if (openGuide.getParameterCount() == 3 && cheatMode != null) {
                openGuide.invoke(null, player, cheatMode, 1);
                return true;
            } else if (openGuide.getParameterCount() == 2) {
                ItemStack item = createCheatGuide();
                openGuide.invoke(null, player, item);
                return true;
            }
            return false;
        } catch (ReflectiveOperationException exception) {
            logUnavailable(exception);
            return false;
        }
    }

    private void logUnavailable(ReflectiveOperationException exception) {
        if (!unavailableLogged) {
            unavailableLogged = true;
            plugin.getLogger().log(Level.WARNING, "[SFMaster] No se pudo enlazar la guía Cheat de Slimefun.", exception);
        }
    }
}
