package org.metamechanists.odysseia.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.metamechanists.odysseia.menus.MenuUtils;
import org.metamechanists.odysseia.modalities.Modality;
import org.metamechanists.odysseia.modalities.ModalityService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menú interactivo de modalidades de DrakesCraft con estética de Slimefun,
 * marcos de cristal decorativos, cabezas texturizadas y sonido ambiental.
 */
public final class ModalidadesCommand implements CommandExecutor, Listener {

    public static final String HEAD_SLIMEFUN = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTliYzM4ZDdhMTI2MmQ1ZDg1MmEzOTI4YTM2ZmEyNGNjMGU0ZGNmMTkzZDU3NzFkZGJlOGU1ZGRjZmQ5NDMifX19";
    public static final String HEAD_CLASICO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjRiMjJjN2E2ODVlMTMyOWMyOWYyN2VjYzEzZGIzYmUxYzAyYWI4MjEyMTNmNTZkYTU5ZDk5N2Y3ZmEzZjc5In19fQ==";
    public static final String HEAD_SKYBLOCK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZiMDEzYjg2MDY0ZjdiNmVjM2NhNGY2Zjg1MTBmMDE5ZmJhYWUxYjFhN2NmNzNiNWYwMGU5NWM0NzhhODczMSJ9fX0=";
    public static final String HEAD_ONEBLOCK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzY5M2ExY2Y1MmU0NjM1ODQ4YmIyOGE1ZGYxYTNlZDU3NWFjODZmYzcxODM2YTk5MjZlZTQxYTEzZTJmNWI2NiJ9fX0=";
    public static final String HEAD_ONECHUNK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjUxNzcwYmU0NjYxODU4YWQ2NDYyNzE5MmJhN2ZhMTY3MDlkN2RmOWQyNzJkNWMzMjdmZmJiMThhZjYxZWM0In19fQ==";
    public static final String HEAD_LABORATORIO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODhiZjBhNzRiYTA0YTQzNDBkOGFhNTM2YjAwYjA1ZGNmYTM1ZTdmZjJiM2ZlM2YzOGFjMmE1ZWI1NzQzYjAyMCJ9fX0=";

    public static final String HEAD_MENU = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDg4YTYxZTc3YTcyYmViNTE1OGI0NjUxODBhMTQ1ZWVjZjhmNmQ3ZGVmYTBmNjg4YWRjMmE5YjE4YTk5OGJjIn19fQ==";
    public static final String HEAD_INFO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2EyMDhhYjIzZTlhNWQwY2Q1YWNmNGMzYTI5Yzc4YzU0MzkwNTZhOGE1YTNlM2Y5ZjQxZWRiNWY4M2Q5ZmM0In19fQ==";
    public static final String HEAD_TIENDA = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWY4OTFiNTk0M2Q4NDQyMDFkMjE4ZmVhYTk3MmJhMWIxZmFjY2VhZTJkOTM1OWRlNzM2Y2FjYTU3YmM5NiJ9fX0=";
    public static final String HEAD_SPAWN = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWY1YTNlZGM0YTYwN2Q5ZTRmNmUzMWNlYjcxMDZlZjNiYjZiNzIxNWE5ZjhkZjY3MjljY2YxMGRiM2I4YTQifX19";

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

    public void open(Player player) {
        List<Modality> list = modalities.modalities();
        Modality current = modalities.resolve(player);
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MenuUtils.colorize("&#4FC3F7&l✦ DRAKESCRAFT &8· &#FFD700&lMODALIDADES &#4FC3F7&l✦"));
        holder.setInventory(inventory);

        // Marco de cristal estilo Slimefun
        ItemStack cyanAccent = MenuUtils.createPane(Material.CYAN_STAINED_GLASS_PANE, " ");
        ItemStack lightBlueBorder = MenuUtils.createPane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        ItemStack grayBorder = MenuUtils.createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack blackFiller = MenuUtils.createPane(Material.BLACK_STAINED_GLASS_PANE, " ");

        for (int i = 0; i < 54; i++) {
            if (i == 0 || i == 8 || i == 45 || i == 53) {
                inventory.setItem(i, cyanAccent);
            } else if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inventory.setItem(i, (i % 2 == 0) ? lightBlueBorder : grayBorder);
            } else {
                inventory.setItem(i, blackFiller);
            }
        }

        // Distribuimos las modalidades en la cuadrícula central
        int[] slots = centeredSlots(list.size());
        for (int index = 0; index < list.size() && index < slots.length; index++) {
            Modality modality = list.get(index);
            int slot = slots[index];
            boolean here = modality.id().equalsIgnoreCase(current.id());
            holder.putModality(slot, modality);

            String texture = resolveTexture(modality);
            Material fallbackMaterial = resolveFallbackMaterial(modality.icon());
            List<String> lore = buildLore(modality, here);
            String title = (here ? "&#55FF55&l✦ " : "&#4FC3F7&l✦ ") + modality.displayName()
                    + (here ? " &a&l[ACTUAL]" : "");

            inventory.setItem(slot, MenuUtils.buildItem(fallbackMaterial, texture, title, lore, here));
        }

        // Barra inferior de navegación y utilidades (Fila 6)
        inventory.setItem(45, MenuUtils.buildItem(Material.BARRIER, null,
                "&c&l✖ Cerrar Menú",
                List.of("&7Haz clic para salir del menú de modalidades."), false));

        inventory.setItem(47, MenuUtils.buildItem(Material.CHEST, HEAD_MENU,
                "&6&l✦ Menú Principal &7(/menu)",
                List.of(
                        "&8&m────────────────────────────────────────",
                        "&7Accede a la tienda general de bloques,",
                        "&7subastas, trabajos, habilidades y kits.",
                        "",
                        "&e➤ Clic para abrir &6/menu",
                        "&8&m────────────────────────────────────────"
                ), false));

        inventory.setItem(49, MenuUtils.buildItem(Material.BOOK, HEAD_INFO,
                "&b&lℹ ¿Cómo Funciona la Red?",
                List.of(
                        "&8&m────────────────────────────────────────",
                        "&7En DrakesCraft disfrutas de modalidades interconectadas:",
                        "",
                        "&8▪ &aRango y Kits: &fUniversales en " + enTodas(),
                        "&8▪ &eEconomía (Dragmas): &fCompartida en toda la red",
                        "&8▪ &dSlimefun 4: &fActivo en Survival, SkyBlock, OneBlock y OneChunk",
                        "&8▪ &cInventarios: &fAislados por modalidad",
                        "&8▪ &bBóvedas (/pv): &fIndependientes por modalidad",
                        "",
                        "&7Elige tu mundo preferido y comienza tu aventura.",
                        "&8&m────────────────────────────────────────"
                ), false));

        inventory.setItem(51, MenuUtils.buildItem(Material.EMERALD, HEAD_TIENDA,
                "&e&l✦ Tienda Oficial Web",
                List.of(
                        "&8&m────────────────────────────────────────",
                        "&7Apoya al servidor y adquiere rangos VIP,",
                        "&7llaves de oráculo y cosméticos exclusivos.",
                        "",
                        "&8▪ &aWeb: &fdrakescraft-v2.tebex.io",
                        "",
                        "&e➤ Clic para recibir el enlace en el chat",
                        "&8&m────────────────────────────────────────"
                ), false));

        inventory.setItem(53, MenuUtils.buildItem(Material.BEACON, HEAD_SPAWN,
                "&a&l✦ Spawn Principal &7(/spawn)",
                List.of(
                        "&8&m────────────────────────────────────────",
                        "&7Regresa inmediatamente al spawn",
                        "&7de tu modalidad actual.",
                        "",
                        "&e➤ Clic para ir al &a/spawn",
                        "&8&m────────────────────────────────────────"
                ), false));

        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
    }

    private String resolveTexture(Modality modality) {
        return switch (modality.id().toLowerCase(Locale.ROOT)) {
            case "survival" -> HEAD_SLIMEFUN;
            case "clasico" -> HEAD_CLASICO;
            case "skyblock" -> HEAD_SKYBLOCK;
            case "oneblock" -> HEAD_ONEBLOCK;
            case "onechunk" -> HEAD_ONECHUNK;
            case "laboratorio" -> HEAD_LABORATORIO;
            default -> "";
        };
    }

    private Material resolveFallbackMaterial(String iconName) {
        if (iconName == null || iconName.isBlank()) return Material.GRASS_BLOCK;
        Material mat = Material.matchMaterial(iconName.toUpperCase(Locale.ROOT));
        return (mat != null && mat.isItem()) ? mat : Material.GRASS_BLOCK;
    }

    private List<String> buildLore(Modality modality, boolean isCurrent) {
        List<String> lore = new ArrayList<>();
        lore.add("&8&m────────────────────────────────────────");
        if (!modality.description().isBlank()) {
            lore.add("&7" + modality.description());
            lore.add("");
        }
        switch (modality.id().toLowerCase(Locale.ROOT)) {
            case "survival" -> {
                lore.add("&8▪ &eMundo: &fOverworld + Nether + End custom");
                lore.add("&8▪ &aTecnología: &2Slimefun 4 &a+ Suites Activas");
            }
            case "clasico" -> {
                lore.add("&8▪ &eMundo: &fTerralith 100% Clásico");
                lore.add("&8▪ &aReglas: &2Sin Slimefun &8· &fEquipamiento Vanilla");
            }
            case "skyblock" -> {
                lore.add("&8▪ &eMundo: &fIslas en el Vacío (3 dimensiones)");
                lore.add("&8▪ &aExpansión: &bAutomática al subir de /rankup");
            }
            case "oneblock" -> {
                lore.add("&8▪ &eMundo: &fUn solo bloque con fases dinámicas");
                lore.add("&8▪ &aSlimefun: &2Habilitado en toda la isla");
            }
            case "onechunk" -> {
                lore.add("&8▪ &eMundo: &fEspacio acotado a un chunk (16x16)");
                lore.add("&8▪ &aSlimefun: &2Bucle dimensional infinito");
            }
            case "laboratorio" -> {
                lore.add("&8▪ &eModo: &bCreativo completo para pruebas");
                lore.add("&8▪ &cSeguridad: &7Se reinicia cada lunes; nada sale");
            }
            default -> {
                if (!modality.worlds().isEmpty()) {
                    lore.add("&8▪ &eMundos: &f" + String.join(", ", modality.worlds()));
                }
            }
        }
        lore.add("&8▪ &6Economía & Rangos: &aConservados en toda la red");
        lore.add("&8▪ &cInventario & Bóvedas: &ePropios de la modalidad");
        lore.add("&8&m────────────────────────────────────────");
        if (isCurrent) {
            lore.add("&a&l✔ ESTÁS JUGANDO AQUÍ AHORA");
        } else {
            lore.add("&e➤ Clic izquierdo para viajar &8· &7/" + modality.command());
        }
        lore.add("&8&m────────────────────────────────────────");
        return lore;
    }

    public static int[] centeredSlots(int count) {
        if (count == 6) {
            return new int[]{20, 22, 24, 29, 31, 33};
        }
        if (count == 1) return new int[]{22};
        if (count == 2) return new int[]{21, 23};
        if (count == 3) return new int[]{20, 22, 24};
        if (count == 4) return new int[]{20, 21, 23, 24};
        if (count == 5) return new int[]{20, 21, 22, 23, 24};
        if (count == 7) return new int[]{19, 21, 23, 25, 29, 31, 33};
        if (count == 8) return new int[]{19, 21, 23, 25, 28, 30, 32, 34};
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = (i < 7) ? 19 + i : 28 + (i - 7);
        }
        return slots;
    }

    private String enTodas() {
        int total = modalities.modalities().size();
        return switch (total) {
            case 0, 1 -> "esta modalidad";
            case 2 -> "las dos modalidades";
            case 3 -> "las tres modalidades";
            case 4 -> "las cuatro modalidades";
            case 5 -> "las cinco modalidades";
            case 6 -> "las seis modalidades";
            case 7 -> "las siete modalidades";
            case 8 -> "las ocho modalidades";
            case 9 -> "las nueve modalidades";
            case 10 -> "las diez modalidades";
            default -> "las " + total + " modalidades";
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) return;

        // Utilidades de la barra inferior
        if (rawSlot == 45) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            return;
        }
        if (rawSlot == 47) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            player.performCommand("menu");
            return;
        }
        if (rawSlot == 49) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
            player.sendMessage(MenuUtils.colorize("&8&m────────────────────────────────────────"));
            player.sendMessage(MenuUtils.colorize("&6⚡ &lDRAKESCRAFT NETWORK &8· &fInformación de Modalidades"));
            player.sendMessage(MenuUtils.colorize("&8▪ &aRango y Kits: &fUniversales en " + enTodas()));
            player.sendMessage(MenuUtils.colorize("&8▪ &eEconomía (Dragmas): &fCompartida en toda la red"));
            player.sendMessage(MenuUtils.colorize("&8▪ &cInventarios y Bóvedas (/pv): &fAislados por modalidad"));
            player.sendMessage(MenuUtils.colorize("&8▪ &bSlimefun 4: &fHabilitado en Survival, SkyBlock, OneBlock y OneChunk"));
            player.sendMessage(MenuUtils.colorize("&8&m────────────────────────────────────────"));
            return;
        }
        if (rawSlot == 51) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            player.sendMessage(MenuUtils.colorize("&6DrakesCraft &8· &fTienda oficial: &ehttps://drakescraft-v2.tebex.io"));
            return;
        }
        if (rawSlot == 53) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            player.performCommand("spawn");
            return;
        }

        // Clic en modalidad
        Modality target = holder.getModality(rawSlot);
        if (target != null) {
            Modality current = modalities.resolve(player);
            if (target.id().equalsIgnoreCase(current.id())) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
                player.sendMessage(MenuUtils.colorize("&6DrakesCraft &8· &7Ya estás jugando en &e" + target.displayName() + "&7."));
                return;
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            player.closeInventory();
            if (!target.command().isBlank()) {
                player.performCommand(target.command());
            }
        }
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, Modality> modalitiesBySlot = new HashMap<>();
        private Inventory inventory;

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public void putModality(int slot, Modality modality) {
            modalitiesBySlot.put(slot, modality);
        }

        public Modality getModality(int slot) {
            return modalitiesBySlot.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
