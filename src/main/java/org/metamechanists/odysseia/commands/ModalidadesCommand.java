package org.metamechanists.odysseia.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.metamechanists.odysseia.modalities.Modality;
import org.metamechanists.odysseia.modalities.ModalityService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu para saltar entre las modalidades del servidor.
 *
 * Deja explicito lo que mas se pregunta: el rango y los kits se conservan en todas, y lo
 * unico que cambia es el inventario y las bovedas.
 *
 * El recuento se calcula, no se escribe. Estaba puesto a mano como "las tres" y siguio diciendolo
 * al llegar SkyBlock, OneBlock y el laboratorio: un jugador lo reporto viendo cinco iconos y un
 * texto que hablaba de tres. Un numero escrito a mano en un menu que crece solo siempre acaba
 * mintiendo.
 */
public final class ModalidadesCommand implements CommandExecutor, Listener {

    private final ModalityService modalities;

    public ModalidadesCommand(ModalityService modalities) {
        this.modalities = modalities;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo un jugador puede abrir el menu de modalidades.");
            return true;
        }
        open(player);
        return true;
    }

    private void open(Player player) {
        List<Modality> list = modalities.modalities();
        Modality current = modalities.resolve(player);
        Inventory inventory = Bukkit.createInventory(new Holder(), 27, color("&8Modalidades de &6DrakesCraft"));

        // Distribuimos las modalidades en la fila central (slots 10..16 si son hasta 7)
        int[] slots = centeredSlots(list.size());
        for (int index = 0; index < list.size() && index < slots.length; index++) {
            Modality modality = list.get(index);
            boolean here = modality.id().equals(current.id());
            List<String> lore = new ArrayList<>();
            if (!modality.description().isBlank()) lore.add(color("&7" + modality.description()));
            lore.add("");
            lore.add(color("&7Tu rango y tus kits &ase conservan&7 aqui."));
            lore.add(color("&7Lo que cambia es el &einventario&7 y las &ebovedas&7."));
            lore.add("");
            lore.add(here ? color("&aEstas aqui ahora.")
                          : color("&eClic para ir &8· &7/" + modality.command()));
            inventory.setItem(slots[index], item(modality.icon(), modality.displayName(), lore, here));
        }
        inventory.setItem(22, item("BOOK", "&6¿Cómo funciona?", List.of(
                color("&7En todas las modalidades cuentas con &aSlimefun&7,"),
                color("&7pero puedes jugar de forma &e100% clásica&7"),
                color("&7sin usar máquinas si así lo prefieres."),
                "",
                color("&7En &bSkyBlock&7, &dOneBlock&7 y &6OneChunk&7 tu isla"),
                color("&7se expande automáticamente al subir de &a/rankup&7."),
                "",
                color("&7Tus rangos y kits son universales en " + enTodas() + ".")), false));
        player.openInventory(inventory);
    }

    /**
     * "las cinco", "las cuatro"... segun cuantas modalidades haya cargadas de verdad.
     *
     * Se deletrea hasta diez y a partir de ahi se usa la cifra, que es donde deja de sonar
     * natural en castellano.
     */
    private String enTodas() {
        int total = modalities.modalities().size();
        return switch (total) {
            case 0, 1 -> "esta";
            case 2 -> "las dos";
            case 3 -> "las tres";
            case 4 -> "las cuatro";
            case 5 -> "las cinco";
            case 6 -> "las seis";
            case 7 -> "las siete";
            case 8 -> "las ocho";
            case 9 -> "las nueve";
            case 10 -> "las diez";
            default -> "las " + total;
        };
    }

    /** Distribuye los iconos centrados en la fila del medio del cofre sin desbordar los bordes. */
    static int[] centeredSlots(int count) {
        if (count == 6) {
            // Distribución estéticamente equilibrada para 6 modalidades: slots 10, 11, 12, 14, 15, 16 (dejando el centro 13 o 10..15)
            return new int[]{10, 11, 12, 14, 15, 16};
        }
        int[] slots = new int[count];
        int start = 13 - (count / 2);
        for (int index = 0; index < count; index++) slots[index] = start + index;
        return slots;
    }

    /**
     * El menu deja huecos sin item entre los iconos centrados: sin esto, un arrastre (los
     * clientes Bedrock lo generan distinto a un mouse) podria soltar un item real del jugador
     * dentro de este inventario temporal, donde se pierde al cerrarse. Clic y arrastre son
     * eventos Bukkit distintos: cancelar solo el primero no basta.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        for (Modality modality : modalities.modalities()) {
            if (!name.equalsIgnoreCase(ChatColor.stripColor(color(modality.displayName())))) continue;
            if (modality.id().equals(modalities.resolve(player).id())) {
                player.sendMessage(color("&6DrakesCraft &8· &7Ya estas en &e" + name + "&7."));
                return;
            }
            player.closeInventory();
            if (!modality.command().isBlank()) player.performCommand(modality.command());
            return;
        }
    }

    private static ItemStack item(String material, String name, List<String> lore, boolean glow) {
        Material type = Material.matchMaterial(material.toUpperCase(Locale.ROOT));
        ItemStack stack = new ItemStack(type == null ? Material.GRASS_BLOCK : type);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore);
            if (glow) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Marca el inventario como propio para que los clics no muevan items. */
    private static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, 27);
        }
    }
}
