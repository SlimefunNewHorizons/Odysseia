package org.metamechanists.odysseia.reencarnacion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GUI de la Capsula de Recuerdos. Permite al jugador depositar entre 0 y 5 items
 * para conservarlos tras el rito de reencarnacion.
 */
public final class CapsulaRecuerdosGUI implements InventoryHolder, Listener {

    private static final int GUI_SIZE = 27;
    public static final Set<Integer> CAPSULE_SLOTS = new HashSet<>(Arrays.asList(10, 11, 12, 13, 14));
    private static final int CONFIRM_SLOT = 23;
    private static final int CANCEL_SLOT = 21;
    private static final int INFO_SLOT = 4;

    private final ReencarnacionManager manager;
    private final Player player;
    private final Inventory inventory;
    private boolean confirmed = false;

    public CapsulaRecuerdosGUI(ReencarnacionManager manager, Player player) {
        this.manager = manager;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, GUI_SIZE, Component.text("✦ Cápsula de Recuerdos (Máx 5) ✦", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        setupDecorations();
    }

    private void setupDecorations() {
        ItemStack border = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.displayName(Component.text(" "));
            border.setItemMeta(borderMeta);
        }

        for (int i = 0; i < GUI_SIZE; i++) {
            if (!CAPSULE_SLOTS.contains(i) && i != CONFIRM_SLOT && i != CANCEL_SLOT && i != INFO_SLOT) {
                inventory.setItem(i, border);
            }
        }

        // Info item
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(Component.text("✦ CÁPSULA DE RECUERDOS ✦", NamedTextColor.GOLD, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Coloca en los 5 espacios inferiores", NamedTextColor.GRAY));
            lore.add(Component.text("hasta 5 ítems que quieras conservar.", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("• No es obligatorio colocar los 5.", NamedTextColor.YELLOW));
            lore.add(Component.text("• Si no deseas conservar nada, déjalos vacíos.", NamedTextColor.YELLOW));
            lore.add(Component.text("• Tómate todo el tiempo necesario.", NamedTextColor.YELLOW));
            lore.add(Component.empty());
            lore.add(Component.text("Al renacer recibirás tu Caja Sellada con estos objetos.", NamedTextColor.AQUA));
            infoMeta.lore(lore);
            info.setItemMeta(infoMeta);
        }
        inventory.setItem(INFO_SLOT, info);

        // Cancel item
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.displayName(Component.text("✖ CANCELAR RITO", NamedTextColor.RED, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Cierra la cápsula y regresa tus ítems.", NamedTextColor.GRAY));
            cancelMeta.lore(lore);
            cancel.setItemMeta(cancelMeta);
        }
        inventory.setItem(CANCEL_SLOT, cancel);

        // Confirm item
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.displayName(Component.text("✔ CONFIRMAR SELECCIÓN", NamedTextColor.GREEN, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Guarda los ítems en la cápsula y", NamedTextColor.GRAY));
            lore.add(Component.text("genera tu código de seguridad Discord.", NamedTextColor.GRAY));
            confirmMeta.lore(lore);
            confirm.setItemMeta(confirmMeta);
        }
        inventory.setItem(CONFIRM_SLOT, confirm);
    }

    public void open() {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CapsulaRecuerdosGUI gui) || gui != this) {
            return;
        }

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();

        // Click en inventario superior
        if (rawSlot < GUI_SIZE) {
            if (rawSlot == CONFIRM_SLOT) {
                event.setCancelled(true);
                handleConfirm();
                return;
            } else if (rawSlot == CANCEL_SLOT) {
                event.setCancelled(true);
                player.closeInventory();
                player.sendMessage(Component.text("✖ Rito de reencarnación cancelado. Tus ítems permanecen intactos.", NamedTextColor.RED));
                return;
            } else if (!CAPSULE_SLOTS.contains(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof CapsulaRecuerdosGUI gui) || gui != this) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot < GUI_SIZE && !CAPSULE_SLOTS.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CapsulaRecuerdosGUI gui) || gui != this) {
            return;
        }

        // Si cerró la interfaz sin haber confirmado, devolver los ítems que colocó en la cápsula
        if (!confirmed) {
            for (int slot : CAPSULE_SLOTS) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    player.getInventory().addItem(item).values().forEach(dropped -> 
                        player.getWorld().dropItemNaturally(player.getLocation(), dropped)
                    );
                    inventory.setItem(slot, null);
                }
            }
        }
    }

    private void handleConfirm() {
        if (this.confirmed) {
            return;
        }
        this.confirmed = true;
        List<ItemStack> capsuleItems = new ArrayList<>();

        for (int slot : CAPSULE_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                capsuleItems.add(item.clone());
                inventory.setItem(slot, null); // Limpiar para que no se duplique al cerrar
            }
        }

        player.closeInventory();
        ReencarnacionSession session = manager.createSession(player, capsuleItems);

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.sendMessage(Component.text("§6§m-----------------------------------------------------"));
        player.sendMessage(Component.text("§e§l         ✦ RITO DE REENCARNACIÓN SOLICITADO ✦"));
        player.sendMessage(Component.text("§c§lADVERTENCIA CRÍTICA: §7Al autorizar este proceso se destruirá irrevocablemente:"));
        player.sendMessage(Component.text(" §8• §7Tu inventario, armaduras equipadas y Ender Chest."));
        player.sendMessage(Component.text(" §8• §7Todas tus bóvedas personales (/pv) y depósitos en el banco."));
        player.sendMessage(Component.text(" §8• §7Tus protecciones y construcciones (terreno regenerado de origen)."));
        player.sendMessage(Component.text(" §8• §7Investigaciones de Slimefun y tu dinero (reinicio a ₯1.000)."));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("§aSe han resguardado §f" + capsuleItems.size() + "/5 ítems §aen tu Cápsula del Tiempo.", NamedTextColor.GREEN));
        player.sendMessage(Component.text("§6Código de autorización temporal: §e§l" + session.getCode() + " §7(Válido por 15 minutos)"));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("§bPara confirmar, ve al Discord oficial y escribe en #habla-con-saori:", NamedTextColor.AQUA));
        player.sendMessage(Component.text("§f!reencarnar " + session.getCode(), NamedTextColor.WHITE, TextDecoration.BOLD));
        player.sendMessage(Component.text("§6§m-----------------------------------------------------"));
    }
}
