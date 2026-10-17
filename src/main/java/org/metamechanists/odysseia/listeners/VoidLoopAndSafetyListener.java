package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.metamechanists.odysseia.Odysseia;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona la seguridad ante caídas al vacío:
 * 1. OneChunk (ChunkBlock): Bucle dimensional vertical continuo (Overworld Y < -50 -> Nether Y=310 -> End Y=250 -> Overworld Y=310).
 * 2. SkyBlock & OneBlock: Rescate automático al spawn de la isla con caída lenta y reinicio de distancia de caída.
 */
public final class VoidLoopAndSafetyListener implements Listener {

    private final Odysseia plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public VoidLoopAndSafetyListener(Odysseia plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!(event.getEntity() instanceof Player player)) return;

        String worldName = player.getWorld().getName().toLowerCase();
        if (isMonitoredWorld(worldName)) {
            event.setCancelled(true);
            handleVoidFall(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isDead() || !player.isOnline()) return;

        Location to = event.getTo();
        if (to == null) return;

        String worldName = player.getWorld().getName().toLowerCase();
        if (!isMonitoredWorld(worldName)) return;

        double triggerY = getTriggerY(worldName);
        if (to.getY() <= triggerY) {
            handleVoidFall(player);
        }
    }

    private boolean isMonitoredWorld(String worldName) {
        return worldName.contains("onechunk")
                || worldName.contains("chunkblock")
                || worldName.contains("bskyblock")
                || worldName.contains("skyblock")
                || worldName.contains("oneblock");
    }

    private double getTriggerY(String worldName) {
        if (worldName.contains("nether") || worldName.contains("the_end") || worldName.contains("end")) {
            return -2.0; // En Nether y End vacíos, rescata o transiciona inmediatamente al pasar Y <= -2
        }
        return -58.0; // En Overworld (min Y=-64), en cuanto minas el fondo (Y=-64) y caes a -58, se activa inmediatamente antes de daño
    }

    private void handleVoidFall(Player player) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < 2500) {
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        String currentWorld = player.getWorld().getName().toLowerCase();

        // 1. Caso OneChunk / ChunkBlock: Bucle Dimensional Infinito
        if (currentWorld.contains("onechunk") || currentWorld.contains("chunkblock")) {
            handleOneChunkLoop(player, currentWorld);
            return;
        }

        // 2. Caso SkyBlock / OneBlock: Rescate Seguro al Spawn de la Isla
        handleIslandRescue(player);
    }

    private void handleOneChunkLoop(Player player, String currentWorld) {
        World targetWorld;
        double targetY;
        String titleMsg;
        String subMsg;

        if (currentWorld.endsWith("_nether")) {
            // Nether -> The End
            targetWorld = Bukkit.getWorld("onechunk_world_the_end");
            if (targetWorld == null) targetWorld = Bukkit.getWorld("chunkblock_world_the_end");
            targetY = 250.0;
            titleMsg = ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "THE END";
            subMsg = ChatColor.GRAY + "Descendiendo a la dimensión final...";
        } else if (currentWorld.endsWith("_the_end") || currentWorld.endsWith("_end")) {
            // The End -> Overworld
            targetWorld = Bukkit.getWorld("onechunk_world");
            if (targetWorld == null) targetWorld = Bukkit.getWorld("chunkblock_world");
            targetY = 310.0;
            titleMsg = ChatColor.GREEN + "" + ChatColor.BOLD + "OVERWORLD";
            subMsg = ChatColor.GRAY + "Ascendiendo desde el cosmos...";
        } else {
            // Overworld -> Nether
            targetWorld = Bukkit.getWorld("onechunk_world_nether");
            if (targetWorld == null) targetWorld = Bukkit.getWorld("chunkblock_world_nether");
            targetY = 310.0;
            titleMsg = ChatColor.RED + "" + ChatColor.BOLD + "NETHER";
            subMsg = ChatColor.GRAY + "Cayendo a las profundidades del abismo...";
        }

        if (targetWorld == null) {
            // Fallback si los mundos derivados aun no estan cargados: rescate a su propio mundo
            handleIslandRescue(player);
            return;
        }

        Location targetLoc = new Location(
                targetWorld,
                player.getLocation().getX(),
                targetY,
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                player.getLocation().getPitch()
        );

        player.setFallDistance(0.0f);
        player.teleportAsync(targetLoc).thenAccept(success -> {
            if (success) {
                player.setFallDistance(0.0f);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 1, false, false, true));
                player.sendTitle(titleMsg, subMsg, 10, 40, 20);
                player.playSound(targetLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            }
        });
    }

    private void handleIslandRescue(Player player) {
        player.setFallDistance(0.0f);

        // Intento 1: Teletransporte BentoBox por comando /is go u /ob go
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.setFallDistance(0.0f);
            boolean isOneBlock = player.getWorld().getName().toLowerCase().contains("oneblock");
            
            // Comandos nativos de isla en BentoBox
            String cmd = isOneBlock ? "ob go" : "is go";
            boolean success = player.performCommand(cmd);
            if (!success) {
                player.performCommand("island go");
            }

            // Aplicamos amortiguacion magica para evitar dano de impacto
            player.setFallDistance(0.0f);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 1, false, false, true));
            player.sendMessage(ChatColor.GOLD + "[DrakesCraft] " + ChatColor.YELLOW + "✨ ¡Has sido rescatado del abismo hacia tu isla!");
            player.sendTitle(
                    ChatColor.GOLD + "✨ Rescate del Vacío",
                    ChatColor.YELLOW + "Has regresado sano y salvo a tu isla.",
                    10, 40, 15
            );
            player.playSound(player.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.0f, 1.2f);
        });
    }
}
