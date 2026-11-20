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

    public SupplyDropEvent(JavaPlugin plugin, String lootTier, int radius, String discordWebhookUrl) {
        this(plugin, 900L, discordWebhookUrl, lootTier, radius);
    }

    public SupplyDropEvent(JavaPlugin plugin, long durationSeconds, String discordWebhookUrl) {
        this(plugin, durationSeconds, discordWebhookUrl, "epico", 1000);
    }

    public SupplyDropEvent(JavaPlugin plugin, long durationSeconds, String discordWebhookUrl, String lootTier, int radius) {
        this.plugin = plugin;
        this.remainingSeconds = durationSeconds;
        this.discordWebhookUrl = discordWebhookUrl;
        this.lootTier = lootTier;
        this.searchRadius = radius;

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
        this.active = true;
        World world = Bukkit.getWorld("world");
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }

        // Find wilderness location outside claims
        Location target = null;
        for (int i = 0; i < 20; i++) {
            int x = ThreadLocalRandom.current().nextInt(-3000, 3000);
            int z = ThreadLocalRandom.current().nextInt(-3000, 3000);
            int y = world.getHighestBlockYAt(x, z);
            Location candidate = new Location(world, x, y, z);

            if (isClaimed(candidate)) continue;
            if (candidate.getBlock().isLiquid()) continue;

            target = candidate;
            break;
        }

        if (target == null) {
            target = new Location(world, 1000, world.getHighestBlockYAt(1000, 1000), 1000);
        }

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
                "&6&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n" +
                "  📦 &e&l¡METEORITO DE SUMINISTROS HA CAÍDO! &r\n" +
                "  &7Coordenadas aproximadas: &fX: ~" + approxX + ", Z: ~" + approxZ + " &8(Mundo: " + world.getName() + ")\n" +
                "  &cEl cofre está protegido por guardianes del Olimpo. ¡Derrótalos para abrirlo!\n" +
                "&6&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        Bukkit.broadcastMessage(msg);

        sendDiscordEmbed("☄️ ¡Ha caído un Meteorito de Suministros!",
                "**Mundo:** `" + world.getName() + "`\n" +
                "**Zona Aproximada:** `X: ~" + approxX + ", Z: ~" + approxZ + "`\n" +
                "**Estado:** Custodiado por guardianes del Olimpo. ¡El primer aventurero en abrirlo se queda el botín!",
                0xE67E22);
    }

    private void populateChest(Chest chest) {
        chest.getInventory().clear();
        chest.getInventory().addItem(
                new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 3),
                new ItemStack(Material.NETHERITE_INGOT, 2),
                new ItemStack(Material.DIAMOND_BLOCK, 4),
                new ItemStack(Material.TOTEM_OF_UNDYING, 1),
                new ItemStack(Material.EXPERIENCE_BOTTLE, 32),
                new ItemStack(Material.GOLDEN_CARROT, 64)
        );
    }

    private void spawnVisualImpact(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;

        w.strikeLightningEffect(loc);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3);
        w.spawnParticle(Particle.FLAME, loc, 50, 1, 1, 1, 0.1);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);

        Firework fw = (Firework) w.spawnEntity(loc.clone().add(0, 2, 0), EntityType.FIREWORK_ROCKET);
        var meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.ORANGE, Color.YELLOW, Color.RED)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    private void spawnGuardians(Location loc) {
        World w = loc.getWorld();
        if (w == null || w.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) return; // Pacífico rechaza monstruos (#827)

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
        Player player = event.getPlayer();
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                "&6&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n" +
                "  🎉 &a&l¡BOTÍN RECLAMADO! &r\n" +
                "  &f" + player.getName() + " &7ha abierto y reclamado el &eCofre del Olimpo&7!\n" +
                "&6&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

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
                    chestLocation.getWorld().playSound(chestLocation, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 1.2f);
                }
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                        "&e&l[SUMINISTROS] &a¡Todos los guardianes han caído! El cofre ahora está DESBLOQUEADO."));
            }
        }
    }

    @Override
    public void tick() {
        if (!active) return;
        remainingSeconds--;

        // Visual beam
        if (remainingSeconds % 5 == 0 && chestLocation != null && chestLocation.getWorld() != null) {
            chestLocation.getWorld().spawnParticle(Particle.END_ROD, chestLocation.clone().add(0.5, 1, 0.5), 15, 0.2, 5, 0.2, 0.05);
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
        this.active = false;
        HandlerList.unregisterAll(this);
        guardianUuids.clear();
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
