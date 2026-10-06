package org.metamechanists.odysseia.kits;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Resuelve contenido que no es vanilla: items de Slimefun y encantamientos de otros plugins.
 *
 * Slimefun se busca por reflexion a proposito. Asi Odysseia sigue compilando y arrancando aunque
 * Slimefun no este presente, y un kit que pida un item inexistente falla solo en ese item en vez
 * de tumbar la entrega completa.
 */
public final class CustomContentResolver {

    private static Class<?> slimefunItemClass;
    private static boolean slimefunChecked;

    private CustomContentResolver() {
    }

    /**
     * Encantamiento por nombre, admitiendo {@code namespace:clave}.
     *
     * Sin namespace se asume {@code minecraft}. Esto es lo que permite usar los encantamientos
     * de ExcellentEnchants y similares, que viven fuera del namespace de Minecraft.
     */
    public static Enchantment enchantment(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        if (key == null) return null;
        Enchantment found = Registry.ENCHANTMENT.get(key);
        if (found != null) return found;
        // Algunos plugins registran con su propio namespace sin exponerlo en el config del kit.
        for (Enchantment candidate : Registry.ENCHANTMENT) {
            if (candidate.getKey().getKey().equalsIgnoreCase(key.getKey())) return candidate;
        }
        return null;
    }

    /** True si Slimefun esta cargado y se puede resolver contenido suyo. */
    public static boolean slimefunAvailable() {
        return slimefunClass() != null;
    }

    private static Class<?> slimefunClass() {
        if (!slimefunChecked) {
            slimefunChecked = true;
            if (Bukkit.getPluginManager().getPlugin("Slimefun") != null) {
                try {
                    slimefunItemClass = SlimefunClasses.slimefunItem();
                } catch (ClassNotFoundException error) {
                    slimefunItemClass = null;
                }
            }
        }
        return slimefunItemClass;
    }

    /** Copia del item de Slimefun con ese id, o null si no existe o Slimefun no esta. */
    public static ItemStack slimefunItem(String id) {
        Class<?> cls = slimefunClass();
        if (cls == null || id == null || id.isBlank()) return null;
        try {
            Object item = cls.getMethod("getById", String.class).invoke(null, id.trim().toUpperCase(Locale.ROOT));
            if (item == null) return null;
            Object stack = cls.getMethod("getItem").invoke(item);
            return stack instanceof ItemStack found ? found.clone() : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Bukkit.getLogger().log(Level.WARNING, "[Kits] No se pudo resolver el item de Slimefun " + id, error);
            return null;
        }
    }

    /**
     * Aplica modificadores de atributo, por ejemplo para vida o dano por encima de lo normal.
     * Formato: {@code atributo: valor}, con el nombre de atributo de Bukkit.
     */
    public static void applyAttributes(ItemMeta meta, Map<?, ?> attributes) {
        for (Map.Entry<?, ?> entry : attributes.entrySet()) {
            String raw = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : NamespacedKey.minecraft(raw);
            if (key == null) continue;
            Attribute attribute = Registry.ATTRIBUTE.get(key);
            if (attribute == null) {
                if (key.getKey().startsWith("generic.")) {
                    NamespacedKey altKey = NamespacedKey.minecraft(key.getKey().substring(8));
                    attribute = Registry.ATTRIBUTE.get(altKey);
                } else {
                    NamespacedKey altKey = NamespacedKey.minecraft("generic." + key.getKey());
                    attribute = Registry.ATTRIBUTE.get(altKey);
                }
            }
            if (attribute == null) continue;
            double amount;
            try {
                amount = Double.parseDouble(String.valueOf(entry.getValue()));
            } catch (NumberFormatException ignored) {
                continue;
            }
            meta.addAttributeModifier(attribute, new AttributeModifier(
                    new NamespacedKey("odysseia", "kit-" + key.getKey() + "-" + UUID.randomUUID()),
                    amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
    }

    /** Efecto de pocion por nombre, admitiendo namespace. Devuelve null si no existe. */
    public static PotionEffect potionEffect(String raw, int duration, int amplifier) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = value.contains(":") ? NamespacedKey.fromString(value) : NamespacedKey.minecraft(value);
        if (key == null) return null;
        PotionEffectType type = Registry.EFFECT.get(key);
        return type == null ? null : new PotionEffect(type, duration, Math.max(0, amplifier - 1), false, false);
    }
}
