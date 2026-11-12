package org.metamechanists.odysseia.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Variantes de armadura al renombrar en el yunque (estilo Better Vanilla Building): el nombre elige la textura puesta
 * (equippable.asset_id) y el icono (item_model) del pack. "normal" devuelve la armadura vanilla.
 */
public final class ArmorVariantListener implements Listener {

    private record Variante(String asset, String modelo, String nombre, NamedTextColor color) { }

    private static final Map<String, Variante> VARIANTES = Map.ofEntries(
            Map.entry("dragon", new Variante("dragon", "dragon", "Dragón", NamedTextColor.LIGHT_PURPLE)),
            Map.entry("kintsugi", new Variante("kintsugi", null, "Kintsugi", NamedTextColor.GOLD)),
            Map.entry("tech cian", new Variante("tech_cyan", "tech_cyan", "Tecnológica Cian", NamedTextColor.AQUA)),
            Map.entry("tech cyan", new Variante("tech_cyan", "tech_cyan", "Tecnológica Cian", NamedTextColor.AQUA)),
            Map.entry("tech magenta", new Variante("tech_magenta", "tech_magenta", "Tecnológica Magenta", NamedTextColor.LIGHT_PURPLE)),
            Map.entry("tech oro", new Variante("tech_gold", "tech_gold", "Tecnológica Dorada", NamedTextColor.GOLD)),
            Map.entry("tech gold", new Variante("tech_gold", "tech_gold", "Tecnológica Dorada", NamedTextColor.GOLD)),
            Map.entry("tech verde", new Variante("tech_green", "tech_green", "Tecnológica Verde", NamedTextColor.GREEN)),
            Map.entry("tech green", new Variante("tech_green", "tech_green", "Tecnológica Verde", NamedTextColor.GREEN)));

    private static final String PERMISO = "odysseia.armorvariants";

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void alPrepararYunque(PrepareAnvilEvent evento) {
        ItemStack resultado = evento.getResult();
        if (resultado == null) {
            return;
        }
        EquipmentSlot ranura = ranuraDe(resultado.getType().name());
        if (ranura == null) {
            return;
        }
        HumanEntity jugador = evento.getView().getPlayer();
        if (!jugador.hasPermission(PERMISO)) {
            return;
        }
        String clave = normalizar(evento.getView().getRenameText());
        boolean restaurar = clave.equals("normal") || clave.equals("original") || clave.equals("vanilla");
        Variante variante = VARIANTES.get(clave);
        if (variante == null && !restaurar) {
            return;
        }
        ItemMeta meta = resultado.getItemMeta();
        if (meta == null) {
            return;
        }
        EquippableComponent equipable = meta.getEquippable();
        equipable.setSlot(ranura);
        if (restaurar) {
            equipable.setModel(NamespacedKey.minecraft(materialVanilla(resultado.getType().name())));
            meta.setItemModel(null);
            meta.customName(null);
        } else {
            equipable.setModel(new NamespacedKey("drakescraft", variante.asset()));
            meta.setItemModel(variante.modelo() == null ? null
                    : new NamespacedKey("drakescraft", variante.modelo() + "_" + sufijo(ranura)));
            meta.customName(Component.text(variante.nombre()).color(variante.color())
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.setEquippable(equipable);
        resultado.setItemMeta(meta);
        evento.setResult(resultado);
    }

    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    static EquipmentSlot ranuraDe(String tipo) {
        if (tipo.endsWith("_HELMET")) {
            return EquipmentSlot.HEAD;
        }
        if (tipo.endsWith("_CHESTPLATE")) {
            return EquipmentSlot.CHEST;
        }
        if (tipo.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (tipo.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    static String sufijo(EquipmentSlot ranura) {
        return switch (ranura) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            default -> "boots";
        };
    }

    static String materialVanilla(String tipo) {
        String material = tipo.substring(0, tipo.indexOf('_')).toLowerCase(Locale.ROOT);
        return switch (material) {
            case "golden" -> "gold";
            case "turtle" -> "turtle_scute";
            default -> material;
        };
    }
}
