package org.metamechanists.odysseia.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.boss.instances.WitherStormBoss;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evento Nocturno de Terror del Multiverso & Dioses Antiguos de DrakesCraft.
 * 
 * Ambientación Inmersiva:
 * - Apariciones y espejismos fugaces de Herobrine y Sombras del Vacío.
 * - Susurros encriptados de Dioses Antiguos y colapso multiversal.
 * - Alucinaciones auditivas (pasos detrás del jugador, campanas lejanas, latidos cósmicos).
 * - Distorsiones atmosféricas con relámpagos de sangre, niebla espesa y glitches dimensionales.
 * - Recompensas de reliquias de Slimefun al abatir monstruos nocturnos.
 */
public class HorrorNightScheduler implements Listener {

    private final Odysseia plugin;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Long> lastNightProcessedPerWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTriggerAtPerWorld = new ConcurrentHashMap<>();
    private BukkitTask task;

    public HorrorNightScheduler(Odysseia plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) task.cancel();
        if (!plugin.getConfig().getBoolean("horror-night.enabled", true)) {
            plugin.getLogger().info("[HorrorNight] Desactivado por configuración.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Revisa cada 5 segundos el estado del ciclo día/noche
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkNightCycle, 100L, 100L);
        plugin.getLogger().info("[HorrorNight] Noche de Terror & Lore del Multiverso activado.");
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void checkNightCycle() {
        if (!plugin.getConfig().getBoolean("horror-night.enabled", true) || Bukkit.getOnlinePlayers().isEmpty()) return;
        Set<String> enabledWorlds = new HashSet<>(plugin.getConfig().getStringList("horror-night.worlds"));

        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            if (!enabledWorlds.isEmpty() && !enabledWorlds.contains(world.getName())) continue;
            if (world.getPlayers().stream().noneMatch(player -> player.isOnline() && !player.isDead()
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR)) continue;

            long time = world.getTime();
            long dayIndex = world.getFullTime() / 24000L;

            // Noche en Minecraft: entre tick 13000 y 22000
            if (time >= 13000 && time <= 22000) {
                String worldKey = world.getName();
                Long lastDay = lastNightProcessedPerWorld.get(worldKey);

                long now = System.currentTimeMillis();
                long minIntervalMs = Math.max(0L, plugin.getConfig().getLong("horror-night.min-interval-minutes", 15L)) * 60_000L;

                // La noche perpetua de Halloween (SeasonalEventManager) usa setTime, que adelanta fullTime
                // y abre un "día" nuevo cada pocos minutos: el intervalo real evita repetir la secuencia.
                if (shouldTrigger(lastDay, dayIndex, lastTriggerAtPerWorld.get(worldKey), now, minIntervalMs)) {
                    lastNightProcessedPerWorld.put(worldKey, dayIndex);
                    lastTriggerAtPerWorld.put(worldKey, now);
                    triggerNightHorrorSequence(world);
                }
            }
        }
    }

    static boolean shouldTrigger(Long lastDay, long dayIndex, Long lastTriggerAt, long now, long minIntervalMs) {
        boolean newNight = lastDay == null || lastDay < dayIndex;
        boolean intervalElapsed = lastTriggerAt == null || now - lastTriggerAt >= minIntervalMs;
        return newNight && intervalElapsed;
    }

    /** Dispara la secuencia nocturna cinematográfica. */
    private void triggerNightHorrorSequence(World world) {
        plugin.getLogger().info("[HorrorNight] Secuencia del Multiverso iniciada en " + world.getName() + ", día " + (world.getFullTime() / 24000L));

        for (Player p : world.getPlayers()) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&4&l✦ [NOCHE DE TERROR] &cEl Velo del Multiverso se debilita... Los Antiguos caminan entre las sombras."));
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 2.0f, 0.4f);
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.8f, 0.4f);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.2f, 0.3f);
        }

        // Programar entre 4 y 6 eventos aleatorios durante la noche
        int eventCount = 4 + random.nextInt(3);
        for (int i = 1; i <= eventCount; i++) {
            long delayTicks = (long) (i * 220 + random.nextInt(300));
            final int eventIndex = i;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                executeRandomHorrorStrike(world, eventIndex);
            }, delayTicks);
        }
    }

    /** Ejecuta un evento de terror o distorsión cósmica a un jugador. */
    private void executeRandomHorrorStrike(World world, int strikeIndex) {
        if (!plugin.getConfig().getBoolean("horror-night.enabled", true)
                || world.getTime() < 13000L || world.getTime() > 22000L) {
            return;
        }
        List<Player> eligiblePlayers = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.isOnline() && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                eligiblePlayers.add(p);
            }
        }

        if (eligiblePlayers.isEmpty()) return;

        Player victim = eligiblePlayers.get(random.nextInt(eligiblePlayers.size()));
        int horrorType = random.nextInt(7);

        switch (horrorType) {
            case 0 -> WitherStormBoss.triggerScreamer(victim);
            case 1 -> triggerCreepyWhispers(victim);
            case 2 -> triggerShadowStalker(victim);
            case 3 -> triggerAtmosphericEclipse(victim);
            case 4 -> triggerHerobrineMirage(victim);
            case 5 -> triggerAudioHallucination(victim);
            case 6 -> triggerDimensionalGlitch(victim);
        }
    }

    /** Evento 1: Susurros crípticos de Dioses Antiguos y colapso multiversal. */
    private void triggerCreepyWhispers(Player player) {
        String[] whispers = {
            "&8[&4&k|||&4 Khronos el Olvidado &8&k|||&8] &7\"Las líneas temporales convergen... esta realidad pronto será reclamada.\"",
            "&8[&5&k|||&5 El Velo del Multiverso &8&k|||&8] &7\"No mires hacia atrás en la niebla. Ellos ya te han visto.\"",
            "&8[&6&k|||&6 El Oráculo Roto &8&k|||&8] &7\"Los dioses del Olimpo duermen, pero los Antiguos están despiertos...\"",
            "&8[&c&k|||&c Entidad 404 &8&k|||&8] &7\"DrakesCraft no es un mundo seguro... es una convergencia de dimensiones.\"",
            "&8[&4&k|||&4 La Sombra del Tártaro &8&k|||&8] &7\"Tus máquinas y reactores son insignificantes ante el vacío eterno.\"",
            "&8[&8&k|||&8 El Testigo Silencioso &8&k|||&8] &7\"¿Escuchas los pasos? No son los tuyos...\""
        };
        String msg = ChatColor.translateAlternateColorCodes('&', whispers[random.nextInt(whispers.length)]);
        player.sendMessage(msg);
        player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 1.8f, 0.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1.5f, 0.5f);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 0.5f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 1, false, false));
    }

    /** Evento 2: Aparición fugaz de una Sombra del Vacío. */
    private void triggerShadowStalker(Player player) {
        Location behind = player.getLocation().subtract(player.getLocation().getDirection().multiply(2.5)).add(0, 0.5, 0);
        if (behind.getWorld() == null) return;

        WitherSkeleton ghost = (WitherSkeleton) behind.getWorld().spawnEntity(behind, EntityType.WITHER_SKELETON);
        ghost.setCustomName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Sombra del Vacío");
        ghost.setCustomNameVisible(true);
        ghost.setGlowing(true);
        ghost.setAI(false);
        ghost.setInvulnerable(true);

        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.5f, 0.5f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (ghost.isValid()) {
                ghost.getWorld().spawnParticle(Particle.LARGE_SMOKE, ghost.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.05);
                ghost.getWorld().playSound(ghost.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.5f);
                ghost.remove();
            }
        }, 40L);
    }

    /** Evento 3: Eclipse atmosférico y relámpago de sangre. */
    private void triggerAtmosphericEclipse(Player player) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            "&5&l⚡ &dUn relámpago de sangre rasga el firmamento. La Noche de Terror se intensifica."));

        player.getWorld().strikeLightningEffect(player.getLocation().add(random.nextInt(10) - 5, 0, random.nextInt(10) - 5));
        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.5f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 1, false, false));

        org.metamechanists.odysseia.util.ParticleCompat.spawnDragonBreath(
                player.getWorld(), player.getLocation().add(0, 2, 0),
                80, 2.0, 2.0, 2.0, 0.1, 1.0f);
    }

    /** Evento 4: Espejismo de Herobrine en la niebla periférica. */
    private void triggerHerobrineMirage(Player player) {
        Location front = player.getLocation().add(player.getLocation().getDirection().multiply(8.0)).add(0, 0.2, 0);
        if (front.getWorld() == null) return;

        // Efecto visual: destello espectral con ojos blancos
        player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 1.8f, 0.3f);
        player.playSound(player.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 2.0f, 0.4f);

        Skeleton mirage = (Skeleton) front.getWorld().spawnEntity(front, EntityType.SKELETON);
        mirage.setCustomName(ChatColor.WHITE + "" + ChatColor.BOLD + "§k|||§f Herobrine §k|||");
        mirage.setCustomNameVisible(true);
        mirage.setGlowing(true);
        mirage.setAI(false);
        mirage.setInvulnerable(true);

        front.getWorld().spawnParticle(Particle.REVERSE_PORTAL, front.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.05);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&8[&f✦&8] &7Sentiste una mirada fría clavada en tu nuca desde la niebla..."));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (mirage.isValid()) {
                mirage.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, mirage.getLocation().add(0, 1, 0), 25, 0.3, 0.8, 0.3, 0.02);
                mirage.getWorld().playSound(mirage.getLocation(), Sound.ENTITY_WARDEN_DIG, 1.5f, 0.7f);
                mirage.remove();
            }
        }, 50L); // Desaparece tras 2.5 segundos
    }

    /** Evento 5: Alucinación auditiva (pasos y campanas fantasmales). */
    private void triggerAudioHallucination(Player player) {
        Location behind = player.getLocation().subtract(player.getLocation().getDirection().multiply(2.0));
        player.playSound(behind, Sound.ENTITY_ZOMBIE_STEP, 1.8f, 0.8f);
        player.playSound(behind, Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 0.4f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.5f, 0.3f);
            player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.6f);
        }, 20L);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, false));
    }

    /** Evento 6: Glitch dimensional / Colapso del Velo. */
    private void triggerDimensionalGlitch(Player player) {
        Location loc = player.getLocation();
        loc.getWorld().spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0, 1, 0), 60, 1.5, 1.5, 1.5, 0.1);
        player.playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 2.0f, 0.4f);
        player.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.5f);

        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 1, false, false));

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&c&l✦ [DISTORSIÓN] &4La realidad a tu alrededor parpadea. Un fragmento del multiverso se ha superpuesto a este mundo."));
    }

    /** Dropeo especial de ítems de Slimefun al matar monstruos en la Noche de Terror. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onNightMobDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Monster mob)) return;
        if (mob.getKiller() == null) return;

        World world = mob.getWorld();
        long time = world.getTime();
        boolean isNight = time >= 13000L && time <= 22000L;
        if (!isNight && !plugin.getBloodMoonManager().isActive(world)) return;

        double chance = isNight ? 0.35D : 0.20D;
        if (random.nextDouble() > chance) return;

        String[] slimefunItemIds = {
            "MAGICAL_LUMP_1", "MAGICAL_LUMP_2", "SULFUR", "COPPER_DUST",
            "GOLD_DUST", "IRON_DUST", "ALUMINUM_DUST", "ZINC_DUST", "TIN_DUST",
            "MAGNESIUM_DUST", "SILVER_DUST", "DAMASCUS_STEEL", "REINFORCED_ALLOY_INGOT",
            "CORINTHIAN_BRONZE_INGOT", "HARDENED_METAL_INGOT", "REDSTONE_ALLOY",
            "SYNTHETIC_EMERALD", "SIFTED_ORE", "STRANGE_NETHER_DUST", "ENDER_LUMP"
        };

        String selectedId = slimefunItemIds[random.nextInt(slimefunItemIds.length)];
        org.bukkit.inventory.ItemStack sfItem = getSlimefunItem(selectedId);
        if (sfItem != null) {
            sfItem.setAmount(random.nextInt(3) + 1);
            event.getDrops().add(sfItem);

            mob.getWorld().spawnParticle(Particle.WITCH, mob.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.05);
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }
    }

    /** Intenta obtener un ItemStack de Slimefun por su ID con fallback multi-paquete. */
    private org.bukkit.inventory.ItemStack getSlimefunItem(String id) {
        try {
            Class<?> sfClass = org.metamechanists.odysseia.util.SlimefunClasses.slimefunItem();
            java.lang.reflect.Method getById = sfClass.getMethod("getById", String.class);
            java.lang.reflect.Method getItem = sfClass.getMethod("getItem");
            Object sfObj = getById.invoke(null, id);
            if (sfObj != null) {
                org.bukkit.inventory.ItemStack item = (org.bukkit.inventory.ItemStack) getItem.invoke(sfObj);
                return item == null ? null : item.clone();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
