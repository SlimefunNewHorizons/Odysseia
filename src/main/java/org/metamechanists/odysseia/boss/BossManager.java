package org.metamechanists.odysseia.boss;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.boss.instances.CirceBoss;
import org.metamechanists.odysseia.boss.instances.PolifemoBoss;
import org.metamechanists.odysseia.boss.instances.DiosCorruptoBoss;
import org.metamechanists.odysseia.boss.instances.ThorBoss;
import org.metamechanists.odysseia.boss.instances.AresBoss;
import org.metamechanists.odysseia.boss.instances.HadesBoss;
import org.metamechanists.odysseia.boss.instances.PoseidonBoss;
import org.metamechanists.odysseia.boss.instances.ZeusBoss;
import org.metamechanists.odysseia.boss.instances.LokiBoss;
import org.metamechanists.odysseia.boss.instances.OdinBoss;
import org.metamechanists.odysseia.boss.instances.KratosBoss;
import org.metamechanists.odysseia.boss.instances.HeimdallBoss;
import org.metamechanists.odysseia.boss.instances.HidraBoss;
import org.metamechanists.odysseia.boss.instances.CerberoBoss;
import org.metamechanists.odysseia.boss.instances.ArtemisaBoss;
import org.metamechanists.odysseia.boss.instances.TifonBoss;
import org.metamechanists.odysseia.boss.instances.PrometeoBoss;
import org.metamechanists.odysseia.boss.instances.ColosoEndBoss;
import org.metamechanists.odysseia.boss.instances.WitherStormBoss;
import org.metamechanists.odysseia.boss.instances.DragonAncestralBoss;
import org.metamechanists.odysseia.boss.instances.EgyptianBoss;
import org.metamechanists.odysseia.boss.instances.JaxDisplayBoss;
import org.metamechanists.odysseia.boss.skills.PolymorphSkill;
import org.metamechanists.odysseia.boss.combat.BossCombatDirector;
import org.metamechanists.odysseia.utils.WebhookSender;
import org.metamechanists.odysseia.integrations.DiosesDrakesBossBridge;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.IOException;

public class BossManager implements Listener {

    private final Odysseia plugin;
    private final Map<UUID, OdysseyBoss> activeBosses = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> naturalBosses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSpawnAnnouncements = new ConcurrentHashMap<>();
    /** Daño efectivo aportado por jugador a cada boss, usado para repartir el loot. */
    private final Map<UUID, Map<UUID, Double>> bossContributions = new ConcurrentHashMap<>();
    /** Ventanas breves para que una espada de endgame no borre un boss por ráfaga. */
    private final Map<UUID, Map<UUID, DamageWindow>> bossDamageWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> adaptiveCounterCooldowns = new ConcurrentHashMap<>();
    // Debounce: evita encolar múltiples updateBossBar por hit en el mismo tick
    private final java.util.Set<UUID> pendingBarUpdate = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastMobility = new ConcurrentHashMap<>();
    /** Arena visual temporal de invocaciones manuales; nunca modifica bloques del mundo. */
    private final Map<UUID, BukkitTask> activeDomains = new ConcurrentHashMap<>();
    private final DiosesDrakesBossBridge divineBossBridge;
    private final BossCombatDirector combatDirector;
    private final File pendingRewardsFile;
    private final YamlConfiguration pendingRewards;
    private final java.lang.reflect.Method slimefunGetById;
    private final java.lang.reflect.Method slimefunGetItem;
    private boolean slimefunLootWarningLogged;
    private BukkitTask updateTask;
    private BukkitTask skillTask;
    private BukkitTask naturalSpawnTask;

    private record DamageWindow(long startedAt, double damage) {
    }

    /** Jefes elegibles para spawn natural por defecto (si la config no especifica lista). */
    private static final java.util.List<String> DEFAULT_NATURAL_BOSSES = java.util.List.of(
            "thor", "ares", "hades", "poseidon", "zeus", "loki", "odin", "kratos",
            "heimdall", "hidra", "cerbero", "artemisa", "tifon", "prometeo", "coloso_end", "dragon_ancestral"
    );

    public BossManager(Odysseia plugin) {
        this.plugin = plugin;
        this.divineBossBridge = new DiosesDrakesBossBridge(plugin);
        this.combatDirector = new BossCombatDirector(plugin);
        this.pendingRewardsFile = new File(plugin.getDataFolder(), "boss-rewards.yml");
        this.pendingRewards = YamlConfiguration.loadConfiguration(pendingRewardsFile);
        java.lang.reflect.Method getById = null;
        java.lang.reflect.Method getItem = null;
        try {
            Class<?> slimefunItem = SlimefunClasses.slimefunItem();
            getById = slimefunItem.getMethod("getById", String.class);
            getItem = slimefunItem.getMethod("getItem");
        } catch (ReflectiveOperationException ignored) {
            // Slimefun is optional; boss reliquias siguen funcionando sin su pool aleatorio.
        }
        this.slimefunGetById = getById;
        this.slimefunGetItem = getItem;
        startTasks();
    }

    private void startTasks() {
        // Update BossBars and Pathfinding every 1 second (20 ticks)
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, OdysseyBoss> entry : activeBosses.entrySet()) {
                UUID bossId = entry.getKey();
                OdysseyBoss boss = entry.getValue();
                LivingEntity entity = boss.getEntity();
                if (entity == null || entity.isDead() || !entity.isValid()) {
                    removeBoss(bossId, null);
                    continue;
                }
                boss.updateBossBar();
                boss.checkPhases();
                boss.tickAura();
                pressureWithMobility(boss);
                if (boss instanceof PolifemoBoss polifemo) {
                    polifemo.updatePathfinding();
                }
            }
        }, 20L, 20L);

        // Los jefes presionan al grupo con rotaciones frecuentes sin ejecutar IA cada tick.
        long skillPeriod = Math.clamp(plugin.getConfig().getLong("boss-balance.skill-interval-ticks", 60L), 40L, 200L);
        skillTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (OdysseyBoss boss : activeBosses.values()) {
                try {
                    boss.executeSkillsRotation();
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("[Bosses] Habilidad de " + boss.getId() + " cancelada: " + ex.getMessage());
                    plugin.getLogger().fine("[Bosses] Detalle de la habilidad fallida: " + ex);
                }
                try {
                    combatDirector.tick(boss);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("[BossDirector] Rotación de " + boss.getId()
                            + " cancelada: " + ex.getMessage());
                }
            }
        }, skillPeriod, skillPeriod);

        startNaturalSpawnTask();
    }

    /**
     * Spawn natural: cada cierto intervalo, con una probabilidad configurable, invoca un jefe
     * aleatorio cerca de un jugador (en la superficie). No genera estructuras.
     * Config en config.yml → sección "natural-spawn".
     */
    private void startNaturalSpawnTask() {
        if (!plugin.getConfig().getBoolean("natural-spawn.enabled", false)) {
            return;
        }
        int intervalSeconds = Math.max(60, plugin.getConfig().getInt("natural-spawn.interval-seconds", 1800));
        long period = intervalSeconds * 20L;
        naturalSpawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tryNaturalSpawn, period, period);
        plugin.getLogger().info("[NaturalSpawn] Spawn natural de jefes activado (cada " + intervalSeconds + "s).");
    }

    private void tryNaturalSpawn() {
        try {
            var cfg = plugin.getConfig();
            if (Math.random() > cfg.getDouble("natural-spawn.chance", 0.25)) {
                return;
            }
            // Respeta el máximo de jefes naturales activos
            naturalBosses.removeIf(uuid -> !activeBosses.containsKey(uuid));
            if (naturalBosses.size() >= cfg.getInt("natural-spawn.max-active", 2)) {
                return;
            }

            java.util.List<String> allowedWorlds = cfg.getStringList("natural-spawn.worlds");
            java.util.List<String> bosses = cfg.getStringList("natural-spawn.bosses");
            if (bosses.isEmpty()) {
                bosses = DEFAULT_NATURAL_BOSSES;
            }

            // Candidatos: jugadores vivos en mundos permitidos (lista vacía = todos)
            java.util.List<Player> candidates = new java.util.ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (allowedWorlds.isEmpty() || allowedWorlds.contains(p.getWorld().getName())) {
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                return;
            }

            java.util.Random rnd = new java.util.Random();
            Player anchor = candidates.get(rnd.nextInt(candidates.size()));
            double minDist = cfg.getDouble("natural-spawn.min-distance", 30);
            double maxDist = cfg.getDouble("natural-spawn.max-distance", 60);
            double dist = minDist + rnd.nextDouble() * Math.max(0, maxDist - minDist);
            double angle = rnd.nextDouble() * Math.PI * 2;

            Location base = anchor.getLocation().clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            int y = base.getWorld().getHighestBlockYAt(base);
            Location spawnLoc = new Location(base.getWorld(), base.getBlockX() + 0.5, y + 1, base.getBlockZ() + 0.5);

            String type = bosses.get(rnd.nextInt(bosses.size()));
            OdysseyBoss boss = spawnBoss(type, spawnLoc);
            if (boss != null) {
                naturalBosses.add(boss.getEntity().getUniqueId());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[NaturalSpawn] Error al intentar spawn natural: " + e.getMessage());
        }
    }

    public OdysseyBoss spawnBoss(String type, Location loc) {
        return spawnBoss(type, loc, false);
    }

    /**
     * Vanilla replaces piglins and similar entities during transformations.
     * A replacement has a new UUID and loses the boss state, gear and phases,
     * so bosses must remain their original entity for their full encounter.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBossTransform(EntityTransformEvent event) {
        if (activeBosses.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            plugin.getLogger().fine("[Bosses] Transformación vanilla bloqueada para "
                    + activeBosses.get(event.getEntity().getUniqueId()).getId());
        }
    }

    /** Validates a boss id before an arena can charge an entry fee. */
    public boolean supportsBossType(String type) {
        if (type == null) return false;
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "circe", "polifemo", "dios_corrupto", "dios-corrupto", "thor", "ares", "hades", "poseidon",
                    "zeus", "loki", "odin", "kratos", "heimdall", "hidra", "cerbero", "artemisa", "tifon",
                    "tifón", "prometeo", "coloso_end", "coloso-end", "coloso", "wither", "wither_storm",
                    "wither-storm", "witherstorm", "dragon_ancestral", "dragon-ancestral", "dragon", "ra", "isis",
                    "anubis", "set" -> true;
            case "jax", "ajax" -> plugin.getConfig().getBoolean("bosses.jax.enabled", true);
            default -> false;
        };
    }

    /** Invoca un jefe y, si corresponde, su dominio visual temporal. */
    public OdysseyBoss spawnBoss(String type, Location loc, boolean createDomain) {
        LivingEntity entity;
        OdysseyBoss boss;

        if (type.equalsIgnoreCase("circe")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITCH);
            boss = new CirceBoss(entity);
        } else if (type.equalsIgnoreCase("polifemo")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.GIANT);
            boss = new PolifemoBoss(entity);
        } else if (type.equalsIgnoreCase("dios_corrupto") || type.equalsIgnoreCase("dios-corrupto")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);
            boss = new DiosCorruptoBoss(entity);
        } else if (type.equalsIgnoreCase("thor")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.PIGLIN_BRUTE);
            boss = new ThorBoss(entity);
        } else if (type.equalsIgnoreCase("ares")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.VINDICATOR);
            boss = new AresBoss(entity);
        } else if (type.equalsIgnoreCase("hades")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);
            boss = new HadesBoss(entity);
        } else if (type.equalsIgnoreCase("poseidon")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.DROWNED);
            boss = new PoseidonBoss(entity);
        } else if (type.equalsIgnoreCase("zeus")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);
            boss = new ZeusBoss(entity);
        } else if (type.equalsIgnoreCase("loki")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.ILLUSIONER);
            boss = new LokiBoss(entity);
        } else if (type.equalsIgnoreCase("odin")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.STRAY);
            boss = new OdinBoss(entity);
        } else if (type.equalsIgnoreCase("kratos")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.PIGLIN_BRUTE);
            boss = new KratosBoss(entity);
        } else if (type.equalsIgnoreCase("heimdall")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.STRAY);
            boss = new HeimdallBoss(entity);
        } else if (type.equalsIgnoreCase("hidra")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.RAVAGER);
            boss = new HidraBoss(entity);
        } else if (type.equalsIgnoreCase("cerbero")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.RAVAGER);
            boss = new CerberoBoss(entity);
        } else if (type.equalsIgnoreCase("artemisa")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.SKELETON);
            boss = new ArtemisaBoss(entity);
        } else if (type.equalsIgnoreCase("tifon") || type.equalsIgnoreCase("tifón")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.GIANT);
            boss = new TifonBoss(entity);
        } else if (type.equalsIgnoreCase("prometeo")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.BLAZE);
            boss = new PrometeoBoss(entity);
        } else if (type.equalsIgnoreCase("coloso_end") || type.equalsIgnoreCase("coloso-end") || type.equalsIgnoreCase("coloso")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.ENDERMAN);
            boss = new ColosoEndBoss(entity);
        } else if (type.equalsIgnoreCase("wither") || type.equalsIgnoreCase("wither_storm") || type.equalsIgnoreCase("wither-storm") || type.equalsIgnoreCase("witherstorm")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITHER);
            boss = new WitherStormBoss(entity);
        } else if (type.equalsIgnoreCase("dragon_ancestral") || type.equalsIgnoreCase("dragon-ancestral") || type.equalsIgnoreCase("dragon")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.ENDER_DRAGON);
            boss = new DragonAncestralBoss(entity);
        } else if (type.equalsIgnoreCase("ra")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.BLAZE);
            boss = new EgyptianBoss(entity, EgyptianBoss.Kind.RA);
        } else if (type.equalsIgnoreCase("isis")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.EVOKER);
            boss = new EgyptianBoss(entity, EgyptianBoss.Kind.ISIS);
        } else if (type.equalsIgnoreCase("anubis")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.WITHER_SKELETON);
            boss = new EgyptianBoss(entity, EgyptianBoss.Kind.ANUBIS);
        } else if (type.equalsIgnoreCase("set")) {
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.HUSK);
            boss = new EgyptianBoss(entity, EgyptianBoss.Kind.SET);
        } else if (type.equalsIgnoreCase("jax") || type.equalsIgnoreCase("ajax")) {
            if (!plugin.getConfig().getBoolean("bosses.jax.enabled", true)) {
                return null;
            }
            entity = (LivingEntity) loc.getWorld().spawnEntity(loc, EntityType.RAVAGER);
            boss = new JaxDisplayBoss(entity);
        } else {
            return null;
        }

        applyCombatBalance(boss);
        activeBosses.put(entity.getUniqueId(), boss);
        if (createDomain) {
            createDomain(boss, loc);
        }
        broadcastSpawn(boss);
        sendDiscordWebhook(boss, true, null);
        return boss;
    }

    /** Aplica estadísticas comunes después de que cada clase haya definido su entidad base. */
    private void applyCombatBalance(OdysseyBoss boss) {
        LivingEntity entity = boss.getEntity();
        double speedMultiplier = Math.clamp(plugin.getConfig().getDouble("boss-balance.movement-speed-multiplier", 1.30D), 1.0D, 3.0D);
        double knockbackResistance = Math.clamp(plugin.getConfig().getDouble("boss-balance.knockback-resistance", 0.85D), 0.0D, 1.0D);
        double followRange = Math.clamp(plugin.getConfig().getDouble("boss-balance.follow-range", 64.0D), 16.0D, 128.0D);

        var movement = entity.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * speedMultiplier);
        }
        var knockback = entity.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(Math.max(knockback.getBaseValue(), knockbackResistance));
        }
        var range = entity.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(Math.max(range.getBaseValue(), followRange));
        }
    }

    /** Repositions a boss beside a distant combatant without forcing chunk loads or clipping into blocks. */
    private void pressureWithMobility(OdysseyBoss boss) {
        if (!plugin.getConfig().getBoolean("boss-balance.mobility.enabled", true)) {
            return;
        }
        LivingEntity entity = boss.getEntity();
        double targetRange = Math.clamp(plugin.getConfig().getDouble(
                "boss-balance.mobility.target-range", 56.0D), 16.0D, 96.0D);
        Player target = boss.nearestCombatTarget(targetRange);
        if (target == null || target.getWorld() != entity.getWorld()) {
            return;
        }

        double minimumDistance = Math.clamp(plugin.getConfig().getDouble(
                "boss-balance.mobility.minimum-distance", 12.0D), 4.0D, 32.0D);
        if (entity.getLocation().distanceSquared(target.getLocation()) < minimumDistance * minimumDistance) {
            return;
        }

        long now = System.currentTimeMillis();
        long configuredCooldown = Math.clamp(plugin.getConfig().getLong(
                "boss-balance.mobility.cooldown-seconds", 7L), 3L, 30L) * 1000L;
        long phaseCooldown = Math.max(2500L, configuredCooldown - ((long) (boss.getCurrentPhase() - 1) * 1500L));
        if (now - lastMobility.getOrDefault(entity.getUniqueId(), 0L) < phaseCooldown) {
            return;
        }

        Location destination = safeFlankLocation(entity.getLocation(), target.getLocation());
        if (destination == null || !entity.teleport(destination)) {
            return;
        }
        lastMobility.put(entity.getUniqueId(), now);
        destination.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, destination.clone().add(0, 1, 0),
                36, 0.45, 0.7, 0.45, 0.08);
        destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.1f,
                boss.getCurrentPhase() >= 3 ? 0.65f : 0.85f);
    }

    /** Searches only the target's loaded chunk and returns an open, grounded flank. */
    private Location safeFlankLocation(Location bossLocation, Location targetLocation) {
        Vector direction = bossLocation.toVector().subtract(targetLocation.toVector()).setY(0);
        if (direction.lengthSquared() < 0.01D) {
            direction = targetLocation.getDirection().setY(0).multiply(-1);
        }
        direction.normalize();
        double distance = Math.clamp(plugin.getConfig().getDouble(
                "boss-balance.mobility.flank-distance", 4.0D), 2.0D, 8.0D);
        for (double side : new double[] {0.0D, 1.0D, -1.0D}) {
            Vector perpendicular = new Vector(-direction.getZ(), 0, direction.getX()).multiply(side * 2.0D);
            Location candidate = targetLocation.clone().add(direction.clone().multiply(distance)).add(perpendicular);
            candidate.setDirection(targetLocation.toVector().subtract(bossLocation.toVector()));
            int chunkX = candidate.getBlockX() >> 4;
            int chunkZ = candidate.getBlockZ() >> 4;
            if (!candidate.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                    && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                return candidate;
            }
        }
        return null;
    }

    public void removeBoss(UUID uuid, Player killer) {
        OdysseyBoss boss = activeBosses.remove(uuid);
        BukkitTask domain = activeDomains.remove(uuid);
        if (domain != null) domain.cancel();
        naturalBosses.remove(uuid);
        bossContributions.remove(uuid);
        bossDamageWindows.remove(uuid);
        adaptiveCounterCooldowns.remove(uuid);
        lastMobility.remove(uuid);
        combatDirector.cleanup(uuid);
        if (boss != null) {
            if (killer != null) {
                broadcastDeath(boss, killer);
                sendDiscordWebhook(boss, false, killer);
            }
            boss.cleanup();
        }
    }

    /**
     * Renders a temporary arena from particles instead of blocks so an admin summon
     * cannot overwrite claims, terrain, or player constructions.
     */
    private void createDomain(OdysseyBoss boss, Location origin) {
        if (!plugin.getConfig().getBoolean("boss-domains.enabled", true) || origin.getWorld() == null) {
            return;
        }
        double radius = Math.clamp(plugin.getConfig().getDouble("boss-domains.radius", 26.0D), 12.0D, 48.0D);
        double height = Math.clamp(plugin.getConfig().getDouble("boss-domains.height", 16.0D), 6.0D, 32.0D);
        long duration = Math.clamp(plugin.getConfig().getLong("boss-domains.duration-seconds", 900L), 60L, 1800L) * 20L;
        Location center = origin.clone().add(0.0D, 0.1D, 0.0D);
        UUID bossId = boss.getEntity().getUniqueId();
        long startedAt = System.currentTimeMillis();

        BukkitTask domain = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            LivingEntity entity = boss.getEntity();
            if (!activeBosses.containsKey(bossId) || entity == null || entity.isDead()
                    || System.currentTimeMillis() - startedAt >= duration * 50L) {
                BukkitTask task = activeDomains.remove(bossId);
                if (task != null) task.cancel();
                return;
            }

            var world = center.getWorld();
            double phase = (System.currentTimeMillis() - startedAt) / 1000.0D;
            for (int index = 0; index < 40; index++) {
                double angle = (Math.PI * 2.0D * index / 40.0D) + phase * 0.35D;
                double x = center.getX() + Math.cos(angle) * radius;
                double z = center.getZ() + Math.sin(angle) * radius;
                world.spawnParticle(org.bukkit.Particle.END_ROD, x, center.getY() + 0.15D, z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                if (index % 4 == 0) {
                    world.spawnParticle(org.bukkit.Particle.DUST, x, center.getY() + height, z,
                            1, new org.bukkit.Particle.DustOptions(Color.fromRGB(121, 48, 255), 1.4F));
                }
            }
            for (int index = 0; index < 16; index++) {
                double angle = Math.PI * 2.0D * index / 16.0D;
                double x = center.getX() + Math.cos(angle) * radius;
                double z = center.getZ() + Math.sin(angle) * radius;
                world.spawnParticle(org.bukkit.Particle.PORTAL, x, center.getY() + height / 2.0D, z,
                        2, 0.15D, height / 2.0D, 0.15D, 0.02D);
            }
        }, 0L, 10L);
        activeDomains.put(bossId, domain);
        origin.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.5F, 0.55F);
    }

    public void shutdown() {
        if (updateTask != null) updateTask.cancel();
        if (skillTask != null) skillTask.cancel();
        if (naturalSpawnTask != null) naturalSpawnTask.cancel();
        combatDirector.shutdown();

        // Clean up active bosses
        for (OdysseyBoss boss : activeBosses.values()) {
            boss.cleanup();
        }
        activeBosses.clear();

        // Restore any polymorphed players
        for (UUID uuid : PolymorphSkill.getPolymorphedHelmets().keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                PolymorphSkill.restorePlayer(p);
            }
        }
        PolymorphSkill.getPolymorphedHelmets().clear();
    }

    private void broadcastSpawn(OdysseyBoss boss) {
        String bossName = boss.getDisplayName();
        long now = System.currentTimeMillis();
        long lastAnnounce = lastSpawnAnnouncements.getOrDefault(bossName, 0L);

        if (now - lastAnnounce > 10000L) { // Cooldown de 10 segundos por tipo de jefe
            lastSpawnAnnouncements.put(bossName, now);
            String msg = ChatColor.translateAlternateColorCodes('&',
                    "&c&l[MÍTICO] &f¡El jefe ancestral " + bossName + " &fha aparecido cerca!");
            notifyNearby(boss.getEntity().getLocation(), msg, 1.0f);
        } else {
            String msg = ChatColor.translateAlternateColorCodes('&',
                    "&c&l[MÍTICO] &f¡El jefe ancestral " + bossName + " &fha aparecido cerca!");
            notifyNearby(boss.getEntity().getLocation(), msg, 0.8f);
        }
    }

    private void broadcastDeath(OdysseyBoss boss, Player killer) {
        String msg = ChatColor.translateAlternateColorCodes('&',
                "&c&l[MÍTICO] &f¡El jefe " + boss.getDisplayName() + " &fha sido derrotado por &a" + killer.getName() + "&f!");
        notifyNearby(boss.getEntity().getLocation(), msg, 0.7f);
    }

    /** Los eventos de combate no deben contaminar el chat global. */
    private void notifyNearby(Location location, String message, float volume) {
        if (location.getWorld() == null) {
            return;
        }
        double radius = Math.clamp(plugin.getConfig().getDouble("boss-balance.announcement-radius", 64.0D), 16.0D, 128.0D);
        double radiusSquared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                player.sendMessage(message);
                player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, volume, 0.5f);
            }
        }
    }

    private void sendDiscordWebhook(OdysseyBoss boss, boolean isSpawn, Player killer) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank() || url.equals("REPLACE_ME") || !WebhookSender.isAllowedHttpsUrl(url)) {
            return;
        }

        String title = isSpawn ? "⚔️ ¡Jefe Mítico Invocado!" : "💀 ¡Jefe Mítico Derrotado!";
        String desc = isSpawn
                ? "El jefe **" + ChatColor.stripColor(boss.getDisplayName()) + "** ha despertado en las coordenadas: " +
                  "`X: " + boss.getEntity().getLocation().getBlockX() +
                  ", Y: " + boss.getEntity().getLocation().getBlockY() +
                  ", Z: " + boss.getEntity().getLocation().getBlockZ() + "`."
                : "El jefe **" + ChatColor.stripColor(boss.getDisplayName()) + "** fue derrotado por **" + killer.getName() + "**.";
        int color = isSpawn ? 16753920 : 3066993; // Orange for spawn, Green for death

        String json = "{\"username\":\"Odysseia Mitología\",\"embeds\":[{\"title\":\"" + title + "\",\"description\":\""
                + Odysseia.escapeJson(desc) + "\",\"color\":" + color + "}]}";

        WebhookSender.sendAsync(plugin, url, json);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // Handle Loki clone hit
        if (event.getEntity() instanceof org.bukkit.entity.Illusioner illusioner) {
            String name = illusioner.getCustomName();
            if (name != null && name.equals("§aIlusión de Loki")) {
                event.setCancelled(true);
                illusioner.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, illusioner.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.05);
                illusioner.getWorld().playSound(illusioner.getLocation(), Sound.ENTITY_BAT_DEATH, 0.8f, 1.2f);
                illusioner.remove();
                return;
            }
        }

        if (activeBosses.containsKey(event.getEntity().getUniqueId())) {
            OdysseyBoss boss = activeBosses.get(event.getEntity().getUniqueId());
            if (boss.isPhaseShielded()) {
                event.setCancelled(true);
                boss.getEntity().getWorld().spawnParticle(org.bukkit.Particle.ENCHANTED_HIT,
                        boss.getEntity().getLocation().add(0, 1, 0), 8, 0.4, 0.7, 0.4, 0.03);
            } else if (boss instanceof PrometeoBoss prometeo
                    && event.getFinalDamage() >= prometeo.getEntity().getHealth()
                    && prometeo.beginPhoenixRebirth()) {
                // El golpe letal se consume: no hay muerte ni recompensas hasta la derrota real.
                event.setCancelled(true);
                prometeo.updateBossBar();
            } else if (event.getFinalDamage() >= boss.getEntity().getHealth()
                    && boss.beginConfiguredRebirth()) {
                // Renacer común: conserva la misma entidad y evita cualquier recompensa prematura.
                event.setCancelled(true);
                boss.updateBossBar();
            } else if (boss instanceof DiosCorruptoBoss dios && dios.isShieldActive()) {
                event.setCancelled(true);
                dios.getEntity().getWorld().playSound(dios.getEntity().getLocation(), Sound.ENTITY_SHULKER_BULLET_HIT, 1.0f, 1.5f);
                dios.getEntity().getWorld().spawnParticle(org.bukkit.Particle.REVERSE_PORTAL, dios.getEntity().getLocation().add(0, 1.5, 0), 15, 0.5, 0.5, 0.5, 0.05);
            } else if (boss instanceof PrometeoBoss prometeo && prometeo.isInvulnerable()) {
                // Fénix: invulnerable durante la resurrección
                event.setCancelled(true);
                prometeo.getEntity().getWorld().spawnParticle(org.bukkit.Particle.FLAME, prometeo.getEntity().getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0.05);
            } else if (boss.isRebirthInvulnerable()) {
                event.setCancelled(true);
                boss.getEntity().getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                        boss.getEntity().getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0.05);
            } else {
                // Debounced bossbar update — sólo una tarea pendiente por boss a la vez
                UUID bid = boss.getEntity().getUniqueId();
                if (pendingBarUpdate.add(bid)) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        pendingBarUpdate.remove(bid);
                        boss.updateBossBar();
                    }, 1L);
                }
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onBossCombat(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        org.bukkit.entity.Entity damager = event.getDamager();
        org.bukkit.entity.Entity victim = event.getEntity();

        // Determinar el jugador atacante (físico o con proyectiles)
        Player attackerPlayer = null;
        if (damager instanceof Player p) {
            attackerPlayer = p;
        } else if (damager instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) {
            attackerPlayer = p;
        }

        // Caso A: jugadores atacan a un jefe. Los retos de arena absorben daño
        // antes de aplicar ventajas VIP, manteniendo un escalado efectivo sin
        // superar los límites del atributo MAX_HEALTH de Minecraft.
        OdysseyBoss defendingBoss = activeBosses.get(victim.getUniqueId());
        if (defendingBoss != null && attackerPlayer != null) {
            event.setDamage(defendingBoss.scaleIncomingArenaDamage(event.getDamage()));
            applyAdaptiveWeaponCounter(defendingBoss, attackerPlayer, event);
            if (attackerPlayer.hasPermission("odysseia.boss.vip_advantage")) {
                event.setDamage(event.getDamage() * 1.25);
                // Partículas críticas de poder celestial
                victim.getWorld().spawnParticle(org.bukkit.Particle.CRIT, victim.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.1);
            }
            bossContributions
                    .computeIfAbsent(victim.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                    .merge(attackerPlayer.getUniqueId(), Math.max(0.0, event.getFinalDamage()), Double::sum);
            return;
        }

        // Caso B: el multiplicador cubre golpes físicos, proyectiles y habilidades con entity.damage(..., boss).
        OdysseyBoss attackingBoss = resolveBossDamager(damager);
        if (victim instanceof Player player && attackingBoss != null) {
            double damageMultiplier = Math.clamp(plugin.getConfig().getDouble("boss-balance.damage-multiplier", 1.50D), 1.0D, 5.0D);
            event.setDamage(event.getDamage() * damageMultiplier);
            if (player.hasPermission("odysseia.boss.vip_advantage")) {
                event.setDamage(event.getDamage() * 0.80);
                // Efecto de escudo dorado al recibir el golpe del boss
                player.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.05);
            }
        }
    }

    private OdysseyBoss resolveBossDamager(org.bukkit.entity.Entity damager) {
        org.bukkit.entity.Entity source = damager;
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            source = shooter;
        }
        return activeBosses.get(source.getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (activeBosses.containsKey(entity.getUniqueId())) {
            OdysseyBoss boss = activeBosses.get(entity.getUniqueId());
            Player killer = entity.getKiller();
            Map<UUID, Double> contributions = new HashMap<>(bossContributions.getOrDefault(entity.getUniqueId(), Map.of()));
            List<Player> participants = findEligibleRecipients(killer, contributions);
            Player creditedKiller = resolveCreditedKiller(killer, participants, contributions);
            removeBoss(entity.getUniqueId(), killer);

            event.getDrops().clear();
            if (boss != null) {
                distributeCustomDrops(boss.getId(), entity.getLocation(), creditedKiller, participants, contributions);
                divineBossBridge.rewardParticipants(entity.getUniqueId(), boss, participants, contributions);
                if (plugin.getStarTelemetry() != null && plugin.getPurchaseEngine() != null) {
                    plugin.getStarTelemetry().publishBossDefeat(plugin.getPurchaseEngine(), boss.getId(), participants.size());
                }
                if (creditedKiller != null && creditedKiller != killer) {
                    broadcastDeath(boss, creditedKiller);
                    sendDiscordWebhook(boss, false, creditedKiller);
                }
            }

            event.setDroppedExp(Math.max(5000, plugin.getConfig().getInt("boss-loot.experience", 5000)));
            
            Location loc = entity.getLocation();
            loc.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }


    /** Entrega un único premio: reliquia del boss, material SF seguro o ningún drop. */
    private void distributeCustomDrops(String bossId, Location dropLocation, Player creditedKiller,
                                       List<Player> recipients, Map<UUID, Double> contributions) {
        if (!plugin.getConfig().getBoolean("boss-loot.enabled", true)) {
            return;
        }

        if (creditedKiller == null && recipients.isEmpty()) {
            return;
        }

        ConfigurationSection drops = plugin.getConfig().getConfigurationSection("boss-loot.bosses." + bossId.toLowerCase() + ".drops");
        if (drops == null) {
            plugin.getLogger().warning("[Bosses] Sin tabla de loot configurada para " + bossId + ".");
            return;
        }

        double relicChance = Math.clamp(plugin.getConfig().getDouble("boss-loot.boss-relic-chance", 0.30D), 0.0D, 1.0D);
        double slimefunChance = Math.clamp(plugin.getConfig().getDouble("boss-loot.slimefun-reward-chance", 0.60D), 0.0D, 1.0D - relicChance);
        double roll = ThreadLocalRandom.current().nextDouble();
        org.bukkit.inventory.ItemStack item = roll < relicChance
                ? rollBossReward(drops)
                : roll < relicChance + slimefunChance ? rollSlimefunReward() : null;
        if (item == null) {
            return;
        }

        Player recipient = creditedKiller != null && creditedKiller.isOnline()
                ? creditedKiller
                : pickRecipient(recipients, contributions);

        Map<Integer, org.bukkit.inventory.ItemStack> overflow = recipient.getInventory().addItem(item);
        for (org.bukkit.inventory.ItemStack leftover : overflow.values()) {
            queuePendingReward(recipient.getUniqueId(), leftover);
        }
        recipient.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&6&l[MÍTICO] &eRecibiste &f" + item.getItemMeta().getDisplayName() + " &epor derrotar a &f" + bossId + "&e."));
        if (!overflow.isEmpty()) {
            recipient.sendMessage("§6[MÍTICO] §eInventario lleno: tu recompensa quedó guardada y se entregará al tener espacio.");
        }
    }

    /** Limits extreme Slimefun/Tinker bursts while leaving normal weapons untouched. */
    private void applyAdaptiveWeaponCounter(OdysseyBoss boss, Player attacker, org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("boss-balance.adaptive-counters.enabled", true)) return;

        var healthAttribute = boss.getEntity().getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double maxHealth = healthAttribute == null ? boss.getEntity().getHealth() : healthAttribute.getValue();
        boolean highPowerWeapon = isHighPowerWeapon(attacker);
        String prefix = highPowerWeapon ? "boss-balance.adaptive-counters.high-power" : "boss-balance.adaptive-counters.normal";
        double perHitFraction = Math.clamp(plugin.getConfig().getDouble(prefix + ".max-hit-health-fraction", highPowerWeapon ? 0.025D : 0.045D), 0.001D, 1.0D);
        double maxPerHit = Math.max(1.0D, maxHealth * perHitFraction);
        double adjustedDamage = Math.min(event.getDamage(), maxPerHit);

        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, plugin.getConfig().getLong("boss-balance.adaptive-counters.burst-window-seconds", 4L)) * 1_000L;
        double burstFraction = Math.clamp(plugin.getConfig().getDouble(prefix + ".max-burst-health-fraction", highPowerWeapon ? 0.12D : 0.20D), perHitFraction, 1.0D);
        double burstLimit = Math.max(maxPerHit, maxHealth * burstFraction);
        Map<UUID, DamageWindow> windows = bossDamageWindows.computeIfAbsent(boss.getEntity().getUniqueId(), ignored -> new ConcurrentHashMap<>());
        DamageWindow previous = windows.get(attacker.getUniqueId());
        boolean sameWindow = previous != null && now - previous.startedAt() <= windowMillis;
        double priorDamage = sameWindow ? previous.damage() : 0.0D;
        adjustedDamage = Math.min(adjustedDamage, Math.max(0.0D, burstLimit - priorDamage));
        windows.put(attacker.getUniqueId(), new DamageWindow(sameWindow ? previous.startedAt() : now, priorDamage + adjustedDamage));

        boolean countered = adjustedDamage + 0.01D < event.getDamage();
        event.setDamage(adjustedDamage);
        if (countered) triggerAdaptiveCounter(boss, attacker, highPowerWeapon);
    }

    /** Detects Slimefun/Tinker metadata and unusually high enchant levels without fixed item IDs. */
    private boolean isHighPowerWeapon(Player attacker) {
        org.bukkit.inventory.ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) return false;
        int sharpness = weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS);
        if (sharpness >= plugin.getConfig().getInt("boss-balance.adaptive-counters.high-power.sharpness-threshold", 7)) return true;
        org.bukkit.inventory.meta.ItemMeta meta = weapon.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().getKeys().stream().anyMatch(key ->
                key.getNamespace().equals("slimefun") || key.getNamespace().equals("slimetinker"));
    }

    /** Gives a readable, cooldown-protected retaliation instead of silently nullifying a weapon. */
    private void triggerAdaptiveCounter(OdysseyBoss boss, Player attacker, boolean highPowerWeapon) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1L, plugin.getConfig().getLong("boss-balance.adaptive-counters.counter-cooldown-seconds", 12L)) * 1_000L;
        Map<UUID, Long> counters = adaptiveCounterCooldowns.computeIfAbsent(boss.getEntity().getUniqueId(), ignored -> new ConcurrentHashMap<>());
        if (now < counters.getOrDefault(attacker.getUniqueId(), 0L)) return;
        counters.put(attacker.getUniqueId(), now + cooldown);

        attacker.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS,
                highPowerWeapon ? 80 : 40, highPowerWeapon ? 1 : 0, true, true, true));
        attacker.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, attacker.getLocation().add(0, 1, 0), 32, 0.35, 0.55, 0.35, 0.1);
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.9F, 0.65F);
        attacker.sendActionBar(ChatColor.translateAlternateColorCodes('&',
                highPowerWeapon ? "&5&lEl jefe adapta su defensa a tu arma de endgame." : "&6&lEl jefe bloquea tu ráfaga de daño."));
    }

    /** Sortea una reliquia configurada sin permitir que varias caigan en la misma muerte. */
    private org.bukkit.inventory.ItemStack rollBossReward(ConfigurationSection drops) {
        List<String> ids = new ArrayList<>(drops.getKeys(false));
        while (!ids.isEmpty()) {
            String id = ids.remove(ThreadLocalRandom.current().nextInt(ids.size()));
            org.bukkit.inventory.ItemStack item = org.metamechanists.odysseia.items.OdysseyItemManager.createBossDrop(id);
            if (item != null) {
                return item;
            }
            plugin.getLogger().warning("[Bosses] Drop desconocido en config: " + id);
        }
        return null;
    }

    /** Crea materiales SF útiles definidos por lista blanca; nunca objetos de endgame. */
    private org.bukkit.inventory.ItemStack rollSlimefunReward() {
        if (slimefunGetById == null || slimefunGetItem == null) {
            return null;
        }
        List<String> ids = new ArrayList<>(plugin.getConfig().getStringList("boss-loot.slimefun-rewards.item-ids"));
        while (!ids.isEmpty()) {
            String id = ids.remove(ThreadLocalRandom.current().nextInt(ids.size()));
            try {
                Object slimefunItem = slimefunGetById.invoke(null, id);
                if (slimefunItem == null) continue;
                org.bukkit.inventory.ItemStack item = (org.bukkit.inventory.ItemStack) slimefunGetItem.invoke(slimefunItem);
                if (item == null) continue;
                int minimum = Math.max(1, plugin.getConfig().getInt("boss-loot.slimefun-rewards.minimum-amount", 1));
                int maximum = Math.max(minimum, plugin.getConfig().getInt("boss-loot.slimefun-rewards.maximum-amount", 3));
                item = item.clone();
                item.setAmount(Math.min(item.getMaxStackSize(), ThreadLocalRandom.current().nextInt(minimum, maximum + 1)));
                return item;
            } catch (ReflectiveOperationException exception) {
                if (!slimefunLootWarningLogged) {
                    slimefunLootWarningLogged = true;
                    plugin.getLogger().warning("[Bosses] No se pudo generar recompensa Slimefun: " + exception.getMessage());
                }
                return null;
            }
        }
        return null;
    }

    /** Keeps boss rewards out of the world and persists them across restarts until delivery succeeds. */
    private synchronized void queuePendingReward(UUID playerId, org.bukkit.inventory.ItemStack item) {
        String path = "players." + playerId;
        List<org.bukkit.inventory.ItemStack> queued = new ArrayList<>(pendingRewards.getList(path, List.of()).stream()
                .filter(org.bukkit.inventory.ItemStack.class::isInstance)
                .map(org.bukkit.inventory.ItemStack.class::cast)
                .toList());
        queued.add(item.clone());
        pendingRewards.set(path, queued);
        savePendingRewards();
    }

    /** Delivers the persistent mailbox on join and retains only items that still do not fit. */
    private synchronized void deliverPendingRewards(Player player) {
        String path = "players." + player.getUniqueId();
        List<org.bukkit.inventory.ItemStack> queued = pendingRewards.getList(path, List.of()).stream()
                .filter(org.bukkit.inventory.ItemStack.class::isInstance)
                .map(org.bukkit.inventory.ItemStack.class::cast)
                .toList();
        if (queued.isEmpty()) return;

        List<org.bukkit.inventory.ItemStack> remaining = new ArrayList<>();
        for (org.bukkit.inventory.ItemStack item : queued) {
            remaining.addAll(player.getInventory().addItem(item).values());
        }
        pendingRewards.set(path, remaining.isEmpty() ? null : remaining);
        savePendingRewards();
        if (remaining.isEmpty()) {
            player.sendMessage("§6[MÍTICO] §eTus recompensas pendientes fueron entregadas.");
        } else {
            player.sendMessage("§6[MÍTICO] §eAún tienes recompensas pendientes: libera espacio en tu inventario.");
        }
    }

    private void savePendingRewards() {
        try {
            pendingRewards.save(pendingRewardsFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("[Bosses] No se pudo guardar boss-rewards.yml: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> deliverPendingRewards(event.getPlayer()));
    }

    private List<Player> findEligibleRecipients(Player killer, Map<UUID, Double> contributions) {
        double totalDamage = contributions.values().stream().mapToDouble(Double::doubleValue).sum();
        double minDamage = Math.max(0.0, plugin.getConfig().getDouble("boss-loot.contributors.minimum-damage", 25.0));
        double minShare = Math.clamp(plugin.getConfig().getDouble("boss-loot.contributors.minimum-share", 0.02), 0.0, 1.0);
        List<Player> recipients = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : contributions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && entry.getValue() >= minDamage
                    && (totalDamage <= 0.0 || entry.getValue() / totalDamage >= minShare)) {
                recipients.add(player);
            }
        }
        if (recipients.isEmpty() && killer != null && killer.isOnline()) {
            recipients.add(killer);
        }
        return recipients;
    }

    private Player pickRecipient(List<Player> recipients, Map<UUID, Double> contributions) {
        double totalWeight = recipients.stream()
                .mapToDouble(player -> Math.max(1.0, contributions.getOrDefault(player.getUniqueId(), 0.0)))
                .sum();
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (Player player : recipients) {
            roll -= Math.max(1.0, contributions.getOrDefault(player.getUniqueId(), 0.0));
            if (roll <= 0.0) {
                return player;
            }
        }
        return recipients.get(recipients.size() - 1);
    }

    /** Uses the highest contributing online player when Minecraft has no direct killer. */
    private Player resolveCreditedKiller(Player killer, List<Player> participants, Map<UUID, Double> contributions) {
        if (killer != null && killer.isOnline()) {
            return killer;
        }
        return participants.stream()
                .max(java.util.Comparator.comparingDouble(player -> contributions.getOrDefault(player.getUniqueId(), 0.0D)))
                .orElse(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PolymorphSkill.restorePlayer(player);
        
        // Remove from boss bars
        for (OdysseyBoss boss : activeBosses.values()) {
            boss.getBossBar().removePlayer(player);
            boss.getPlayersWatching().remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        PolymorphSkill.restorePlayer(player);
        
        // Remove from boss bars
        for (OdysseyBoss boss : activeBosses.values()) {
            boss.getBossBar().removePlayer(player);
            boss.getPlayersWatching().remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockChange(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fallingBlock) {
            NamespacedKey key = new NamespacedKey(plugin, "boss_rock");
            if (fallingBlock.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.EnderCrystal crystal) {
            NamespacedKey key = new NamespacedKey(plugin, "boss_crystal");
            if (crystal.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                event.blockList().clear();
            }
        }
    }
}
