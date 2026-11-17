package org.metamechanists.odysseia.menus;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidades compartidas para estilizar GUIs con la estética de Slimefun, soporte de
 * colores hexadecimales (&#RRGGBB) y cabezas con texturas personalizadas base64.
 */
public final class MenuUtils {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private MenuUtils() {
    }

    /** Traduce tanto códigos de color legados (&a) como colores hexadecimales (&#4FC3F7). */
    public static String colorize(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 32);
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(sb, "§x§" + hex.charAt(0) + "§" + hex.charAt(1)
                    + "§" + hex.charAt(2) + "§" + hex.charAt(3)
                    + "§" + hex.charAt(4) + "§" + hex.charAt(5));
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    /** Traduce una lista de líneas de lore aplicando colorize a cada una. */
    public static List<String> colorize(List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<String> list = new ArrayList<>(lines.size());
        for (String line : lines) {
            list.add(colorize(line));
        }
        return list;
    }

    /**
     * Crea un item de cabeza personalizada con skin base64. Si el entorno no soporta perfiles
     * de Paper (p. ej. tests MockBukkit), retorna un fallback seguro sin lanzar excepción.
     */
    public static ItemStack createHead(String base64Texture, Material fallback) {
        Material safeFallback = (fallback != null && fallback.isItem()) ? fallback : Material.PLAYER_HEAD;
        if (base64Texture == null || base64Texture.isBlank()) {
            return new ItemStack(safeFallback);
        }
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        try {
            ItemMeta rawMeta = item.getItemMeta();
            if (rawMeta instanceof SkullMeta skullMeta) {
                PlayerProfile profile = (PlayerProfile) Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", base64Texture));
                skullMeta.setPlayerProfile(profile);
                item.setItemMeta(skullMeta);
                return item;
            }
        } catch (Throwable ignored) {
            // Fallback silencioso y limpio para MockBukkit o entornos sin PaperProfile activo
        }
        return new ItemStack(safeFallback);
    }

    /** Crea un cristal tintado decorativo con nombre vacío o personalizado. */
    public static ItemStack createPane(Material material, String name) {
        ItemStack item = new ItemStack(material != null ? material : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Construye un ItemStack completo con soporte de textura, nombre, lore, brillo y flags ocultos. */
    public static ItemStack buildItem(Material material, String texture, String name, List<String> lore, boolean glow) {
        ItemStack item = (texture != null && !texture.isBlank())
                ? createHead(texture, material)
                : new ItemStack(material != null && material.isItem() ? material : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(colorize(lore));
            }
            if (glow) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
