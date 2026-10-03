package org.metamechanists.odysseia.laboratorio;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.metamechanists.odysseia.util.SlimefunClasses;

/** Non-destructive safety rules for the creative Slimefun laboratory. */
public final class SandboxSafetyListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2_000L;
    private static final String MODE_BYPASS = "odysseia.laboratorio.modo-bypass";

    private final JavaPlugin plugin;
    private final Set<String> sandboxWorlds;
    private final List<String> blockedItemFragments;
    private final List<String> blockedMachineFragments;
    private final Map<UUID, Long> lastWarning = new HashMap<>();
    private final Method getSlimefunItem;
    private final Method getSlimefunId;

    public SandboxSafetyListener(JavaPlugin plugin, Set<String> sandboxWorlds) {
        this.plugin = plugin;
        this.sandboxWorlds = sandboxWorlds;
        this.blockedItemFragments = plugin.getConfig()
                .getStringList("modalidades.laboratorio.fragmentos-items-explosivos")
                .stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
        this.blockedMachineFragments = plugin.getConfig()
                .getStringList("modalidades.laboratorio.fragmentos-maquinas-peligrosas")
                .stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();

        Method byItem = null;
        Method id = null;
        try {
            Class<?> slimefunItem = SlimefunClasses.slimefunItem();
            byItem = slimefunItem.getMethod("getByItem", ItemStack.class);
            id = slimefunItem.getMethod("getId");
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("[Laboratorio] No se pudieron identificar herramientas explosivas de Slimefun.");
        }
        this.getSlimefunItem = byItem;
        this.getSlimefunId = id;
    }

    /** Cancels every real explosion but keeps audiovisual feedback for testing. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (!isSandbox(event.getLocation().getWorld().getName())) return;
        event.setCancelled(true);
        simulateSafeExplosion(event.getLocation());
        event.getLocation().getWorld().getNearbyPlayers(event.getLocation(), 12).forEach(this::warnExplosion);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (!isSandbox(event.getBlock().getWorld().getName())) return;
        event.setCancelled(true);
        simulateSafeExplosion(event.getBlock().getLocation().add(0.5, 0.5, 0.5));
        event.getBlock().getWorld().getNearbyPlayers(event.getBlock().getLocation(), 12).forEach(this::warnExplosion);
    }

    /** Stops Slimefun explosive tools before their secondary block-breaking handler runs. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosiveTool(BlockBreakEvent event) {
        if (!isSandbox(event.getBlock().getWorld().getName())) return;
        String id = slimefunId(event.getPlayer().getInventory().getItemInMainHand());
        if (!isBlockedItemId(id, blockedItemFragments)) return;

        event.setCancelled(true);
        warnExplosion(event.getPlayer());
    }

    /** Rejects machines whose purpose is keeping chunks loaded or accelerating global tickers. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDangerousMachine(BlockPlaceEvent event) {
        if (!isSandbox(event.getBlockPlaced().getWorld().getName())) return;
        String id = slimefunId(event.getItemInHand());
        if (!isBlockedItemId(id, blockedItemFragments) && !isBlockedItemId(id, blockedMachineFragments)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(color("&6DrakesCraft &8· &7Ese dispositivo está bloqueado en el laboratorio "
                + "porque mantiene chunks o tickers globales bajo carga. &ePrueba su receta y guía, no su colocación."));
    }

    /** Provides self-only Creative/Survival switching without granting creative commands. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameModeCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isSandbox(player.getWorld().getName()) || player.hasPermission(MODE_BYPASS)) return;

        String[] tokens = commandTokens(event.getMessage());
        if (tokens.length == 0 || !isGameModeCommand(tokens[0])) return;

        event.setCancelled(true);
        GameMode requested = requestedMode(tokens);
        if (requested == GameMode.CREATIVE || requested == GameMode.SURVIVAL) {
            player.setGameMode(requested);
            player.sendMessage(color("&6DrakesCraft &8· &7Modo de prueba: &e" + requested.name() + "&7."));
        } else {
            player.sendMessage(color("&6DrakesCraft &8· &7En el laboratorio solo puedes alternar entre "
                    + "&e/gmc&7 y &e/gms&7. Aventura y espectador están desactivados."));
        }
    }

    /** Also closes mode changes made indirectly by another player-facing plugin. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!isSandbox(player.getWorld().getName()) || player.hasPermission(MODE_BYPASS)) return;
        if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SURVIVAL) return;

        event.setCancelled(true);
        player.sendMessage(color("&6DrakesCraft &8· &7Aventura y espectador no están disponibles en el laboratorio."));
    }

    static boolean isBlockedItemId(String id, List<String> fragments) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toUpperCase(Locale.ROOT);
        return fragments.stream().anyMatch(normalized::contains);
    }

    static GameMode requestedMode(String[] tokens) {
        if (tokens.length == 1) {
            return switch (tokens[0]) {
                case "gmc" -> GameMode.CREATIVE;
                case "gms" -> GameMode.SURVIVAL;
                case "gma" -> GameMode.ADVENTURE;
                case "gmsp" -> GameMode.SPECTATOR;
                default -> null;
            };
        }
        if (!tokens[0].equals("gamemode") || tokens.length != 2) return null;
        return switch (tokens[1]) {
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static boolean isGameModeCommand(String label) {
        return label.equals("gamemode") || label.equals("gmc") || label.equals("gms")
                || label.equals("gma") || label.equals("gmsp");
    }

    private static String[] commandTokens(String raw) {
        String command = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isBlank()) return new String[0];
        String[] tokens = command.split("\\s+");
        int separator = tokens[0].lastIndexOf(':');
        if (separator >= 0) tokens[0] = tokens[0].substring(separator + 1);
        return tokens;
    }

    private String slimefunId(ItemStack item) {
        if (getSlimefunItem == null || item == null || item.getType() == Material.AIR) return null;
        try {
            Object slimefunItem = getSlimefunItem.invoke(null, item);
            return slimefunItem == null ? null : String.valueOf(getSlimefunId.invoke(slimefunItem));
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().fine("[Laboratorio] No se pudo identificar herramienta: " + error.getMessage());
            return null;
        }
    }

    private void simulateSafeExplosion(org.bukkit.Location location) {
        location.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
        location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.15F);
    }

    private void warnExplosion(Player player) {
        long now = System.currentTimeMillis();
        long previous = lastWarning.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < MESSAGE_COOLDOWN_MS) return;
        lastWarning.put(player.getUniqueId(), now);
        player.sendMessage(color("&6DrakesCraft &8· &7Tu explosivo funciona, pero las explosiones reales "
                + "no están permitidas en este mundo para evitar abusos. &eSe mostró solo el efecto visual."));
    }

    private boolean isSandbox(String worldName) {
        return sandboxWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
