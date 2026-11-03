package org.metamechanists.odysseia.events.drop;

import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.metamechanists.odysseia.events.calendar.EventOptOutStore;
import org.metamechanists.odysseia.events.calendar.EventTelemetryLogger;
import org.metamechanists.odysseia.events.model.ActiveEvent;
import org.metamechanists.odysseia.events.model.EventType;
import org.metamechanists.odysseia.utils.WebhookSender;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SupplyDropEvent implements ActiveEvent, Listener {

    private final JavaPlugin plugin;
    private final String discordWebhookUrl;
    @Getter private Location chestLocation;
    private boolean active = false;
    private boolean unlocked = false;
    private final List<UUID> guardianUuids = new ArrayList<>();
    private long remainingSeconds;
    private Method psRegionMethod;

    private final String lootTier;
    private final int searchRadius;
    private final EventOptOutStore optOutStore;
    private final EventTelemetryLogger telemetry;
    private boolean claimed = false;

    public SupplyDropEvent(JavaPlugin plugin, String lootTier, int radius, String discordWebhookUrl) {
        this(plugin, 900L, discordWebhookUrl, lootTier, radius, null, null);
    }

    public SupplyDropEvent(JavaPlugin plugin, long durationSeconds, String discordWebhookUrl) {
        this(plugin, durationSeconds, discordWebhookUrl, "epico", 1000, null, null);
    }

    public SupplyDropEvent(JavaPlugin plugin, long durationSeconds, String discordWebhookUrl, String lootTier, int radius) {
        this(plugin, durationSeconds, discordWebhookUrl, lootTier, radius, null, null);
    }

    public SupplyDropEvent(
            JavaPlugin plugin,
            long durationSeconds,
            String discordWebhookUrl,
            String lootTier,
            int radius,
            EventOptOutStore optOutStore,
            EventTelemetryLogger telemetry
    ) {
        this.plugin = plugin;
        this.remainingSeconds = durationSeconds;
        this.discordWebhookUrl = discordWebhookUrl;
        this.lootTier = lootTier != null ? lootTier : "epico";
        this.searchRadius = radius;
        this.optOutStore = optOutStore;
        this.telemetry = telemetry;

        try {
            psRegionMethod = Class.forName("dev.espi.protectionstones.PSRegion")
                    .getMethod("fromLocation", Location.class);
        } catch (Exception ignored) {
            psRegionMethod = null;
        }
    }

    @Override
    public EventType getEventType() {
        return EventType.SUPPLY_DROP;
    }

    @Override
    public void start() {
        World world = Bukkit.getWorld("world");
        if (world == null) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() == World.Environment.NORMAL
                        && !w.getName().toLowerCase(Locale.ROOT).contains("lab")
                        && !w.getName().equalsIgnoreCase("lobby")) {
                    world = w;
                    break;
                }
            }
        }
        if (world == null) {
            this.active = false;
            return;
        }

        // Seleccionar UNICAMENTE de chunks ya cargados (0 carga sincrona en Server thread)
        // y colocar en y+1 solo si es AIRE sobre bloque solido y fuera de claims/spawn.
        Chunk[] loaded = world.getLoadedChunks();
        Location spawn = world.getSpawnLocation();
        Location target = null;
        if (loaded.length > 0) {
            for (int attempt = 0; attempt < 40; attempt++) {
                Chunk ch = loaded[ThreadLocalRandom.current().nextInt(loaded.length)];
                int x = (ch.getX() << 4) + ThreadLocalRandom.current().nextInt(2, 14);
                int z = (ch.getZ() << 4) + ThreadLocalRandom.current().nextInt(2, 14);
                double dx = x - spawn.getX();
                double dz = z - spawn.getZ();
                if ((dx * dx + dz * dz) < (150.0 * 150.0)) continue;

                int groundY = world.getHighestBlockYAt(x, z);
                Block ground = world.getBlockAt(x, groundY, z);
                Block above = world.getBlockAt(x, groundY + 1, z);
                if (!ground.getType().isSolid() || ground.isLiquid() || !above.getType().isAir()) continue;

                Location candidate = above.getLocation();
                if (isClaimed(candidate) || isClaimed(ground.getLocation()) || isWorldGuardProtected(candidate)) continue;

                target = candidate;
                break;
            }
        }

        if (target == null) {
            this.active = false;
            if (telemetry != null) {
                telemetry.record(0, "supply-drop-" + lootTier, "SUPPLY_DROP", "SKIPPED",
                        Bukkit.getOnlinePlayers().size(), "Sin chunk salvaje cargado disponible para colocar cofre seguro");
            }
            return;
        }

        this.active = true;
        this.claimed = false;
        this.chestLocation = target.clone();
        Block block = chestLocation.getBlock();
        block.setType(Material.CHEST);

        if (block.getState() instanceof Chest chest) {
            populateChest(chest);
        }

        spawnVisualImpact(chestLocation);
        spawnGuardians(chestLocation);

        Bukkit.getPluginManager().registerEvents(this, plugin);

        int approxX = ((chestLocation.getBlockX() + 50) / 100) * 100;
        int approxZ = ((chestLocation.getBlockZ() + 50) / 100) * 100;

        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&6📦 Evento&8] &e¡Suministros del Olimpo (&6" + lootTier + "&e) en &fX:~" + approxX
                + " Z:~" + approxZ + " &7(" + world.getName() + ")! Derrota a los guardianes &8· &f/evento silenciar");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (optOutStore != null && optOutStore.isOptedOut(p.getUniqueId())) continue;
            p.sendMessage(msg);
        }

        sendDiscordEmbed("☄️ ¡Ha caído un Meteorito de Suministros!",
                "**Mundo:** `" + world.getName() + "`\n" +
                "**Zona Aproximada:** `X: ~" + approxX + ", Z: ~" + approxZ + "`\n" +
                "**Estado:** Custodiado por guardianes del Olimpo. ¡El primer aventurero en abrirlo se queda el botín!",
                0xE67E22);
    }

    private void populateChest(Chest chest) {
        chest.getInventory().clear();
        if ("legendario".equalsIgnoreCase(lootTier)) {
            chest.getInventory().addItem(
                    new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 4),
                    new ItemStack(Material.NETHERITE_INGOT, 3),
                    new ItemStack(Material.DIAMOND_BLOCK, 6),
                    new ItemStack(Material.TOTEM_OF_UNDYING, 2),
                    new ItemStack(Material.EXPERIENCE_BOTTLE, 64),
                    new ItemStack(Material.GOLDEN_CARROT, 64)
            );
        } else {
            chest.getInventory().addItem(
                    new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2),
                    new ItemStack(Material.NETHERITE_INGOT, 1),
                    new ItemStack(Material.DIAMOND_BLOCK, 3),
                    new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                    new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                    new ItemStack(Material.GOLDEN_CARROT, 64)
            );
        }
    }

    private void spawnVisualImpact(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;

        // Sin EXPLOSION_EMITTER ni particulas cegadoras: solo chispas cortas y cohete suave
        w.spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1.0, 0.5), 24, 0.4, 0.8, 0.4, 0.03);
        w.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.1f);

        Firework fw = (Firework) w.spawnEntity(loc.clone().add(0.5, 2.0, 0.5), EntityType.FIREWORK_ROCKET);
        var meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.ORANGE, Color.YELLOW)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    private void spawnGuardians(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;

        for (int i = 0; i < 4; i++) {
            Location spawnLoc = loc.clone().add((i % 2 == 0 ? 2 : -2), 0, (i > 1 ? 2 : -2));
            WitherSkeleton guardian = (WitherSkeleton) w.spawnEntity(spawnLoc, EntityType.WITHER_SKELETON);
            guardian.setCustomName(ChatColor.translateAlternateColorCodes('&', "&c&lGuardián del Olimpo"));
            guardian.setCustomNameVisible(true);
            guardian.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 99999, 1));
            guardian.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 99999, 1));
            guardianUuids.add(guardian.getUniqueId());
        }
    }

    private boolean isClaimed(Location loc) {
        if (psRegionMethod == null) return false;
        try {
            Object res = psRegionMethod.invoke(null, loc);
            return res != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isWorldGuardProtected(Location loc) {
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            Object rc = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = rc.getClass().getMethod("createQuery").invoke(rc);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weLoc = adapter.getMethod("adapt", Location.class).invoke(null, loc);
            Object set = query.getClass().getMethod("getApplicableRegions", weLoc.getClass()).invoke(query, weLoc);
            int size = (int) set.getClass().getMethod("size").invoke(set);
            return size > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestBreak(BlockBreakEvent event) {
        if (!active || chestLocation == null) return;
        if (event.getBlock().getLocation().equals(chestLocation)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "¡El cofre del Olimpo está sellado mágicamente y no puede romperse!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChestOpen(PlayerInteractEvent event) {
        if (!active || chestLocation == null) return;
        if (event.getClickedBlock() == null) return;
        if (!event.getClickedBlock().getLocation().equals(chestLocation)) return;

        if (!unlocked && !guardianUuids.isEmpty()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "¡Debes eliminar a todos los Guardianes antes de abrir el cofre!");
            event.getPlayer().playSound(chestLocation, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            return;
        }

        // Opened successfully!
        this.claimed = true;
        Player player = event.getPlayer();
        String closeMsg = ChatColor.translateAlternateColorCodes('&',
                "&8[&6📦 Cierre&8] &f" + player.getName() + " &7ha conquistado y reclamado el &eCofre del Olimpo&7.");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (optOutStore != null && optOutStore.isOptedOut(p.getUniqueId())) continue;
            p.sendMessage(closeMsg);
        }

        sendDiscordEmbed("🎉 ¡Suministros Reclamados!",
                "El aventurero **" + player.getName() + "** ha conquistado a los guardianes y reclamado el Cofre del Olimpo.",
                0x2ECC71);

        stop("Reclamado por " + player.getName());
    }

    @EventHandler
    public void onGuardianDeath(EntityDeathEvent event) {
        if (!active) return;
        if (guardianUuids.remove(event.getEntity().getUniqueId())) {
            if (guardianUuids.isEmpty()) {
                unlocked = true;
                if (chestLocation != null && chestLocation.getWorld() != null) {
                    chestLocation.getWorld().playSound(chestLocation, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.2f);
                }
            }
        }
    }

    @Override
    public void tick() {
        if (!active) return;
        remainingSeconds--;

        // Haz visual discreto
        if (remainingSeconds % 5 == 0 && chestLocation != null && chestLocation.getWorld() != null) {
            chestLocation.getWorld().spawnParticle(Particle.END_ROD, chestLocation.clone().add(0.5, 1, 0.5), 10, 0.2, 3, 0.2, 0.03);
        }

        if (remainingSeconds <= 0) {
            stop("Tiempo límite expirado");
        }
    }

    public void stop() {
        stop("Comando de finalización");
    }

    @Override
    public void stop(String reason) {
        if (!active && guardianUuids.isEmpty()) return;
        this.active = false;
        HandlerList.unregisterAll(this);

        // Despawn guardianes remanentes para no dejar WitherSkeletons con Resistencia II sueltos
        for (UUID gid : guardianUuids) {
            Entity e = Bukkit.getEntity(gid);
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
        guardianUuids.clear();

        // Si nadie reclamó el cofre antes de expirar/apagar, limpiar inventario y restaurar AIRE
        if (!claimed && chestLocation != null && chestLocation.getWorld() != null
                && chestLocation.getWorld().isChunkLoaded(chestLocation.getBlockX() >> 4, chestLocation.getBlockZ() >> 4)) {
            Block b = chestLocation.getBlock();
            if (b.getType() == Material.CHEST) {
                if (b.getState() instanceof Chest c) {
                    c.getInventory().clear();
                }
                b.setType(Material.AIR, false);
            }
        }

        if (telemetry != null) {
            telemetry.record(0, "supply-drop-" + lootTier, "SUPPLY_DROP", "FINISHED",
                    claimed ? 1 : 0, reason);
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public long getTimeRemainingSeconds() {
        return Math.max(0, remainingSeconds);
    }

    @Override
    public String getStatusSummary() {
        return "Suministros (" + remainingSeconds + "s restantes, guardianes: " + guardianUuids.size() + ")";
    }

    private void sendDiscordEmbed(String title, String description, int color) {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) return;
        String json = "{\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"" + escapeJson(title) + "\",\n" +
                "    \"description\": \"" + escapeJson(description) + "\",\n" +
                "    \"color\": " + color + ",\n" +
                "    \"footer\": { \"text\": \"DrakesCraft Network · Eventos de Suministros\" }\n" +
                "  }]\n" +
                "}";
        WebhookSender.sendAsync(plugin, discordWebhookUrl, json);
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "");
    }
}
