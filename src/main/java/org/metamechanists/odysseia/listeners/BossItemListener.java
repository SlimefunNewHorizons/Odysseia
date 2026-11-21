package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.items.OdysseyItemManager;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class BossItemListener implements Listener {

    private final Odysseia plugin;
    private final NamespacedKey kratosTempOffhandKey;
    private final Map<UUID, Long> scepterCooldowns = new HashMap<>();
    private final Map<UUID, Long> leviathanCooldowns = new HashMap<>();
    private final Map<UUID, PendingLeviathanAxe> pendingLeviathanAxes = new HashMap<>();
    private final Map<UUID, BukkitTask> activeLeviathanOrbits = new HashMap<>();
    private final Set<UUID> syntheticDamageTargets = new HashSet<>();
    private static final Map<UUID, ItemStack> savedOffhands = new HashMap<>();

    private record PendingLeviathanAxe(ItemStack item) {
    }

    public BossItemListener(Odysseia plugin) {
        this.plugin = plugin;
        this.kratosTempOffhandKey = new NamespacedKey(plugin, "kratos_temp_offhand");

        // Periodic check for Odin's Helmet every 2 seconds (40 ticks)
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkOdinHelmet, 40L, 40L);
    }

    private void checkOdinHelmet() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack helmet = p.getInventory().getHelmet();
            if (helmet != null && helmet.hasItemMeta()) {
                ItemMeta meta = helmet.getItemMeta();
                String type = meta.getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
                if ("odin_helmet".equals(type)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 100, 0, true, false, false));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 2, true, false, false));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damagerEntity = event.getDamager();
        Entity targetEntity = event.getEntity();

        if (!(targetEntity instanceof LivingEntity target)) return;
        if (syntheticDamageTargets.contains(target.getUniqueId())) return;

        // 1. Direct melee hit by player
        if (damagerEntity instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                String type = meta.getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
                if (type != null) {
                    if (type.equals("kratos_blade") && player.getHealth() < 12.0) {
                        event.setDamage(event.getDamage() * 1.30);
                    }
                    handleCustomMeleeHit(player, target, type);
                }
            }
        }

        // 2. Projectile hit (custom tridents)
        if (damagerEntity instanceof Trident trident) {
            if (trident.getPersistentDataContainer().has(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING)) {
                String type = trident.getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
                if ("odin_spear".equals(type)) {
                    Location loc = target.getLocation();
                    loc.getWorld().strikeLightning(loc);
                    loc.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
                } else if ("poseidon_trident".equals(type)) {
                    triggerTsunami(target.getLocation(), trident.getShooter() instanceof Entity s ? s : null);
                }
            }
        }

        // 2.5 Projectile hit (Artemis Bow)
        if (damagerEntity instanceof org.bukkit.entity.Arrow arrow && arrow.hasMetadata("artemis_arrow")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0, false, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 4, false, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, true));
            Location tLoc = target.getLocation();
            tLoc.getWorld().spawnParticle(Particle.SNOWFLAKE, tLoc.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.05);
            tLoc.getWorld().playSound(tLoc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.7f);
        }

        // 3. Escudo Espartano de Ares — refleja el 20% del daño bloqueado al atacante
        if (targetEntity instanceof Player blocker && blocker.isBlocking() && isHoldingItem(blocker, "ares_shield")) {
            if (damagerEntity instanceof LivingEntity attacker && !attacker.equals(blocker)) {
                double reflected = event.getDamage() * 0.20;
                if (reflected > 0) {
                    applySyntheticDamage(attacker, reflected, blocker);
                    Location aLoc = attacker.getLocation();
                    aLoc.getWorld().spawnParticle(Particle.CRIT, aLoc.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
                    aLoc.getWorld().playSound(aLoc, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.7f);
                }
            }
        }
    }

    /** Ola de Poseidón: empuja a todos los enemigos cercanos al punto de impacto. */
    private void triggerTsunami(Location center, Entity source) {
        center.getWorld().spawnParticle(Particle.SPLASH, center, 80, 3.0, 1.0, 3.0, 0.2);
        center.getWorld().spawnParticle(Particle.BUBBLE, center, 60, 3.0, 1.0, 3.0, 0.1);
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.6f);
        for (Entity e : center.getWorld().getNearbyEntities(center, 6, 4, 6)) {
            if (e instanceof LivingEntity victim && !e.equals(source)) {
                Vector push = victim.getLocation().toVector().subtract(center.toVector());
                if (push.lengthSquared() < 0.01) {
                    push = new Vector(0, 1, 0);
                }
                push.normalize().multiply(1.5).setY(0.8);
                victim.setVelocity(push);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true));
            }
        }
    }

    /** Comprueba si el jugador sostiene (en cualquier mano) un item custom con el typeId dado. */
    private boolean isHoldingItem(Player player, String typeId) {
        return matchesType(player.getInventory().getItemInMainHand(), typeId)
                || matchesType(player.getInventory().getItemInOffHand(), typeId);
    }

    private boolean matchesType(ItemStack item, String typeId) {
        if (item == null || !item.hasItemMeta()) return false;
        String type = item.getItemMeta().getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
        return typeId.equals(type);
    }

    private String getCustomItemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
    }

    private boolean isKratosBlade(ItemStack item) {
        return "kratos_blade".equals(getCustomItemType(item));
    }

    private boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private boolean isKratosTempOffhand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(kratosTempOffhandKey, PersistentDataType.BYTE);
    }

    private void synchronizeKratosBlades(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        UUID playerId = player.getUniqueId();

        if (isKratosBlade(mainHand)) {
            if (isKratosBlade(offHand) && !isKratosTempOffhand(offHand)) {
                return;
            }

            if (!savedOffhands.containsKey(playerId)) {
                savedOffhands.put(playerId, isAir(offHand) ? null : offHand.clone());
            }

            if (!isKratosTempOffhand(offHand)) {
                ItemStack visualClone = mainHand.clone();
                ItemMeta cloneMeta = visualClone.getItemMeta();
                if (cloneMeta != null) {
                    cloneMeta.getPersistentDataContainer().set(kratosTempOffhandKey, PersistentDataType.BYTE, (byte) 1);
                    visualClone.setItemMeta(cloneMeta);
                }
                player.getInventory().setItemInOffHand(visualClone);
            }
            return;
        }

        restoreKratosOffhand(player, false);
    }

    private void restoreKratosOffhand(Player player, boolean forceRestore) {
        UUID playerId = player.getUniqueId();
        ItemStack saved = savedOffhands.get(playerId);
        if (saved == null && !savedOffhands.containsKey(playerId)) {
            return;
        }

        ItemStack currentOffHand = player.getInventory().getItemInOffHand();
        if (!forceRestore && !isKratosTempOffhand(currentOffHand)) {
            return;
        }

        savedOffhands.remove(playerId);
        player.getInventory().setItemInOffHand(isAir(saved) ? null : saved.clone());
    }

    private void handleCustomMeleeHit(Player player, LivingEntity target, String itemType) {
        Location targetLoc = target.getLocation();

        switch (itemType) {
            case "loki_dagger":
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 2, false, true));
                targetLoc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, targetLoc.add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 1.2f);
                // 20% probabilidad de volverse invisible por 3 segundos
                if (Math.random() < 0.20) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, false, false, true));
                    player.sendMessage("§a§o[Loki] Te has desvanecido en las sombras...");
                }
                break;

            case "kratos_blade":
                target.setFireTicks(100);
                // Pull target toward player
                Vector pull = player.getLocation().toVector().subtract(targetLoc.toVector());
                if (pull.lengthSquared() > 0.01) {
                    pull.normalize().multiply(0.8).setY(0.3);
                    target.setVelocity(pull);
                }
                // Dibujar línea de partículas de fuego (simula las cadenas de Kratos)
                Location start = player.getEyeLocation().subtract(0, 0.3, 0);
                Location end = target.getLocation().add(0, 1.0, 0);
                Vector direction = end.toVector().subtract(start.toVector());
                double distance = direction.length();
                if (distance > 0.1) {
                    direction.normalize();
                    for (double d = 0; d < distance; d += 0.25) {
                        Location point = start.clone().add(direction.clone().multiply(d));
                        point.getWorld().spawnParticle(Particle.FLAME, point, 1, 0.0, 0.0, 0.0, 0.0);
                        point.getWorld().spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0,
                                new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 50, 0), 0.7f));
                    }
                }
                targetLoc.getWorld().spawnParticle(Particle.FLAME, targetLoc.add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.1);
                targetLoc.getWorld().playSound(targetLoc, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.9f);
                break;

            case "leviathan_axe":
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 4, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 2, false, true));
                targetLoc.getWorld().spawnParticle(Particle.SNOWFLAKE, targetLoc.add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.05);
                targetLoc.getWorld().playSound(targetLoc, Sound.BLOCK_GLASS_BREAK, 0.8f, 0.5f);
                break;

            case "polifemo_club": {
                targetLoc.getWorld().spawnParticle(Particle.BLOCK, targetLoc, 35, 1.5, 0.2, 1.5, 0.1,
                        org.bukkit.Bukkit.createBlockData(org.bukkit.Material.COBBLESTONE));
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.7f);
                for (Entity nearby : target.getNearbyEntities(3.5, 2.0, 3.5)) {
                    if (nearby instanceof LivingEntity victim && !victim.equals(player)) {
                        Vector push = victim.getLocation().toVector().subtract(targetLoc.toVector());
                        if (push.lengthSquared() > 0.01) {
                            victim.setVelocity(push.normalize().multiply(0.9).setY(0.35));
                        }
                    }
                }
                break;
            }

            case "corrupted_god_blade":
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 3, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 1, false, true));
                targetLoc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, targetLoc.add(0, 1, 0), 24, 0.3, 0.5, 0.3, 0.03);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_WITHER_HURT, 0.8f, 0.7f);
                break;

            case "odin_spear":
                targetLoc.getWorld().strikeLightning(targetLoc);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
                break;

            case "mjolnir":
                // Furia del Trueno — rayo directo sobre el objetivo
                targetLoc.getWorld().strikeLightning(targetLoc);
                targetLoc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, targetLoc.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.1);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.1f);
                break;

            case "zeus_mace": {
                // Tormenta Divina — 3 rayos en radio 5 alrededor del objetivo
                for (int i = 0; i < 3; i++) {
                    double ox = (Math.random() - 0.5) * 10;
                    double oz = (Math.random() - 0.5) * 10;
                    targetLoc.getWorld().strikeLightning(targetLoc.clone().add(ox, 0, oz));
                }
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f);
                break;
            }

            case "hades_scythe": {
                // Drenaje de Alma — roba 3♥ (6 HP) y los suma a la salud del jugador
                double maxHp = 20.0;
                var maxAttr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (maxAttr != null) {
                    maxHp = maxAttr.getValue();
                }
                player.setHealth(Math.min(maxHp, player.getHealth() + 6.0));
                targetLoc.getWorld().spawnParticle(Particle.SOUL, targetLoc.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
                targetLoc.getWorld().playSound(targetLoc, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.6f);
                break;
            }

            case "gjallarhorn": {
                // Onda de choque sónica - fuerza de los 9 mundos
                targetLoc.getWorld().playSound(targetLoc, Sound.EVENT_RAID_HORN, 1.2f, 1.5f);
                targetLoc.getWorld().spawnParticle(Particle.SONIC_BOOM, targetLoc, 1);
                for (Entity e : target.getNearbyEntities(4, 3, 4)) {
                    if (e instanceof LivingEntity victim && !victim.equals(player)) {
                        applySyntheticDamage(victim, 8.0, player);
                        victim.setVelocity(victim.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(1.2).setY(0.4));
                    }
                }
                break;
            }

            case "hydra_fang": {
                // Veneno de Lerna
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 4, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 4, false, true));
                targetLoc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, targetLoc, 20, 0.4, 0.4, 0.4, 0.05);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_SPIDER_DEATH, 0.8f, 0.5f);
                break;
            }

            case "tifon_claw": {
                // Furia Primordial - Erupción volcánica y daño porcentual
                target.setFireTicks(120);
                target.setVelocity(new Vector(0, 1.2, 0));
                double eruptionDamage = target.getHealth() * 0.10;
                applySyntheticDamage(target, Math.max(4.0, eruptionDamage), player);
                targetLoc.getWorld().spawnParticle(Particle.LAVA, targetLoc, 20, 0.3, 0.3, 0.3, 0.1);
                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
                break;
            }

            case "prometeo_flame": {
                // Chispa Divina - Incendio en área
                for (Entity e : target.getNearbyEntities(4, 3, 4)) {
                    if (e instanceof LivingEntity victim && !victim.equals(player)) {
                        victim.setFireTicks(120);
                        applySyntheticDamage(victim, 4.0, player);
                    }
                }
                targetLoc.getWorld().spawnParticle(Particle.FLAME, targetLoc, 35, 1.5, 0.5, 1.5, 0.05);
                targetLoc.getWorld().playSound(targetLoc, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.8f);
                break;
            }
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile proj = event.getEntity();
        if (proj instanceof Trident trident && trident.getShooter() instanceof Player player) {
            // Propaga el typeId del tridente custom (mano o secundaria) al proyectil lanzado
            String type = tridentType(trident.getItemStack());
            if (type == null) {
                type = tridentType(player.getInventory().getItemInMainHand());
            }
            if (type == null) {
                type = tridentType(player.getInventory().getItemInOffHand());
            }
            if ("odin_spear".equals(type) || "poseidon_trident".equals(type)) {
                trident.getPersistentDataContainer().set(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING, type);
            }
        }
    }

    private String tridentType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Bukkit emits a second interaction for the off-hand; summoners are main-hand only.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (item == null || !item.hasItemMeta()) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemMeta meta = item.getItemMeta();
        
        // ── Invocador de Jefes Celestiales ──
        NamespacedKey summonKey = new NamespacedKey(plugin, "boss_summoner");
        String bossId = meta.getPersistentDataContainer().get(summonKey, PersistentDataType.STRING);
        if (bossId != null) {
            event.setCancelled(true);
            
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
                return;
            }

            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&e[BossArena] Este listener fue migrado a DrakesBosses; el objeto no se consumió."));
            return;
        }

        String type = meta.getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);

        if ("loki_scepter".equals(type)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long lastUse = scepterCooldowns.getOrDefault(player.getUniqueId(), 0L);
            long diff = now - lastUse;

            if (diff < 5000) {
                double remaining = (5000 - diff) / 1000.0;
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&c&l[COOLDOWN] &eEl Cetro de Loki está recargando. Espera &c" + String.format("%.1f", remaining) + "s&e."));
                return;
            }

            scepterCooldowns.put(player.getUniqueId(), now);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.0f);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 1.1f);

            Snowball ball = player.launchProjectile(Snowball.class);
            ball.setMetadata("loki_magic", new FixedMetadataValue(plugin, true));

            // Green particle trail task
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (ball.isDead() || !ball.isValid()) {
                    task.cancel();
                    return;
                }
                ball.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, ball.getLocation(), 3, 0.1, 0.1, 0.1, 0.02);
            }, 1L, 1L);
        }

        // ── Hacha Leviatán: órbita rúnica o lanzamiento y retorno seguro ──
        if ("leviathan_axe".equals(type)) {
            event.setCancelled(true);
            if (player.isSneaking()) {
                startLeviathanOrbit(player, item);
            } else {
                throwLeviathanAxe(player, event.getHand(), item);
            }
            return;
        }

        if ("circe_staff".equals(type) && tryUseCooldown(player, 10_000L, "&dTransmutación")) {
            Location center = player.getLocation();
            for (Entity nearby : player.getNearbyEntities(5, 3, 5)) {
                if (nearby instanceof LivingEntity victim && !victim.equals(player)) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, false, true));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 1, false, true));
                }
            }
            center.getWorld().spawnParticle(Particle.WITCH, center.add(0, 1, 0), 70, 2.5, 1.0, 2.5, 0.1);
            center.getWorld().playSound(center, Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 0.8f);
        }

        if ("prometeo_flame".equals(type) && tryUseCooldown(player, 12_000L, "&6Chispa Divina")) {
            Location center = player.getLocation();
            for (Entity nearby : player.getNearbyEntities(5, 3, 5)) {
                if (nearby instanceof LivingEntity victim && !victim.equals(player)) {
                    victim.setFireTicks(Math.max(victim.getFireTicks(), 100));
                    applySyntheticDamage(victim, 4.0, player);
                }
            }
            center.getWorld().spawnParticle(Particle.FLAME, center.add(0, 1, 0), 80, 2.5, 1.0, 2.5, 0.05);
            center.getWorld().playSound(center, Sound.ITEM_FIRECHARGE_USE, 1.1f, 0.8f);
        }

        if (activateWeaponAbility(player, type)) {
            event.setCancelled(true);
        }
    }

    /** Gives every boss weapon a bounded active ability without changing terrain. */
    private boolean activateWeaponAbility(Player player, String type) {
        if (type == null) {
            return false;
        }

        if (!switch (type) {
            case "loki_dagger", "odin_spear", "mjolnir", "ares_blade", "ares_shield",
                 "hades_scythe", "poseidon_trident", "zeus_mace", "gjallarhorn",
                 "hydra_fang", "artemis_bow", "tifon_claw", "polifemo_club",
                 "corrupted_god_blade" -> true;
            default -> false;
        }) return false;
        if (!tryUseCooldown(player, 8_000L, abilityName(type))) return true;

        Location origin = player.getLocation().add(0, 1, 0);
        switch (type) {
            case "loki_dagger" -> {
                Vector dash = player.getLocation().getDirection().normalize().multiply(1.35);
                dash.setY(Math.max(0.18, dash.getY()));
                player.setVelocity(dash);
                visualBurst(origin, Particle.PORTAL, Sound.ENTITY_ENDERMAN_TELEPORT, 35, 1.0f);
            }
            case "odin_spear" -> {
                Location strike = targetLocation(player, 18);
                strike.getWorld().strikeLightningEffect(strike);
                strike.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, strike, 45, 0.6, 1.0, 0.6, 0.08);
                damageNearby(strike, 3.0, 8.0, player, PotionEffectType.GLOWING);
            }
            case "mjolnir" -> {
                visualBurst(origin, Particle.ELECTRIC_SPARK, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 70, 1.0f);
                damageNearby(player.getLocation(), 5.0, 7.0, player, PotionEffectType.SLOWNESS);
            }
            case "ares_blade" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 140, 1, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 140, 0, false, true));
                visualBurst(origin, Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 45, 0.8f);
            }
            case "ares_shield" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 180, 1, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 180, 2, false, true));
                visualBurst(origin, Particle.TOTEM_OF_UNDYING, Sound.ITEM_SHIELD_BLOCK, 45, 1.0f);
            }
            case "hades_scythe" -> {
                int hits = damageNearby(player.getLocation(), 5.0, 5.0, player, PotionEffectType.WITHER);
                heal(player, Math.min(6.0, hits * 1.5));
                visualBurst(origin, Particle.SOUL, Sound.PARTICLE_SOUL_ESCAPE, 55, 0.7f);
            }
            case "poseidon_trident" -> {
                Location wave = player.getLocation().add(player.getLocation().getDirection().normalize().multiply(4));
                wave.getWorld().spawnParticle(Particle.SPLASH, wave, 100, 3.0, 1.0, 3.0, 0.2);
                wave.getWorld().playSound(wave, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.7f);
                damageNearby(wave, 5.0, 6.0, player, PotionEffectType.SLOWNESS);
            }
            case "zeus_mace" -> {
                Location skyfall = targetLocation(player, 20);
                skyfall.getWorld().strikeLightningEffect(skyfall);
                skyfall.getWorld().spawnParticle(Particle.END_ROD, skyfall, 60, 1.0, 2.0, 1.0, 0.1);
                damageNearby(skyfall, 4.0, 9.0, player, PotionEffectType.WEAKNESS);
            }
            case "gjallarhorn" -> {
                visualBurst(origin, Particle.SONIC_BOOM, Sound.EVENT_RAID_HORN, 1, 1.0f);
                damageNearby(player.getLocation(), 8.0, 5.0, player, PotionEffectType.SLOWNESS);
            }
            case "hydra_fang" -> {
                damageNearby(player.getLocation(), 5.0, 4.0, player, PotionEffectType.POISON);
                visualBurst(origin, Particle.HAPPY_VILLAGER, Sound.ENTITY_SPIDER_AMBIENT, 70, 0.6f);
            }
            case "artemis_bow" -> {
                RayTraceResult hit = player.getWorld().rayTraceEntities(player.getEyeLocation(),
                    player.getEyeLocation().getDirection(), 24.0, 0.35,
                    entity -> entity instanceof LivingEntity && entity != player);
                if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 300, 0, false, true));
                    applySyntheticDamage(target, 7.0, player);
                    target.getWorld().spawnParticle(Particle.GLOW, target.getLocation().add(0, 1, 0), 35, 0.3, 0.6, 0.3, 0.05);
                }
                visualBurst(origin, Particle.CRIT, Sound.ENTITY_ARROW_SHOOT, 20, 1.2f);
            }
            case "tifon_claw" -> {
                Vector leap = player.getLocation().getDirection().normalize().multiply(0.8);
                leap.setY(0.85);
                player.setVelocity(leap);
                damageNearby(player.getLocation(), 4.0, 6.0, player, PotionEffectType.SLOWNESS);
                visualBurst(origin, Particle.LAVA, Sound.ENTITY_GENERIC_EXPLODE, 60, 0.8f);
            }
            case "polifemo_club" -> {
                damageNearby(player.getLocation(), 6.0, 8.0, player, PotionEffectType.SLOWNESS);
                visualBurst(origin, Particle.CLOUD, Sound.ENTITY_GENERIC_EXPLODE, 80, 0.65f);
            }
            case "corrupted_god_blade" -> {
                damageNearby(player.getLocation(), 5.0, 7.0, player, PotionEffectType.WITHER);
                visualBurst(origin, Particle.SOUL_FIRE_FLAME, Sound.ENTITY_WITHER_HURT, 80, 0.6f);
            }
            default -> { }
        }
        return true;
    }

    private String abilityName(String type) {
        return switch (type) {
            case "loki_dagger" -> "Salto de Loki";
            case "odin_spear" -> "Sentencia de Odín";
            case "mjolnir" -> "Pulso de Thor";
            case "ares_blade" -> "Furia de Ares";
            case "ares_shield" -> "Égida de Ares";
            case "hades_scythe" -> "Cosecha de Hades";
            case "poseidon_trident" -> "Marea de Poseidón";
            case "zeus_mace" -> "Juicio de Zeus";
            case "gjallarhorn" -> "Llamado de Gjallarhorn";
            case "hydra_fang" -> "Veneno de Hidra";
            case "artemis_bow" -> "Marca de Artemisa";
            case "tifon_claw" -> "Salto de Tifón";
            case "polifemo_club" -> "Terremoto de Polifemo";
            case "corrupted_god_blade" -> "Nova Corrupta";
            default -> "Habilidad";
        };
    }

    private Location targetLocation(Player player, int range) {
        Block block = player.getTargetBlockExact(range);
        return block == null ? player.getLocation().add(player.getLocation().getDirection().normalize().multiply(range)) : block.getLocation().add(0.5, 1, 0.5);
    }

    private int damageNearby(Location center, double radius, double damage, Player source, PotionEffectType effect) {
        int hits = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity victim) || victim.equals(source) || victim instanceof ArmorStand) continue;
            applySyntheticDamage(victim, damage, source);
            victim.addPotionEffect(new PotionEffect(effect, 100, 1, false, true));
            Vector push = victim.getLocation().toVector().subtract(center.toVector());
            if (push.lengthSquared() > 0.01) victim.setVelocity(push.normalize().multiply(0.65).setY(0.25));
            hits++;
        }
        return hits;
    }

    private void visualBurst(Location center, Particle particle, Sound sound, int count, float volume) {
        center.getWorld().spawnParticle(particle, center, count, 1.4, 0.8, 1.4, 0.08);
        center.getWorld().playSound(center, sound, volume, 1.0f);
    }

    private void heal(Player player, double amount) {
        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double maxHealth = attribute == null ? 20.0 : attribute.getValue();
        player.setHealth(Math.min(maxHealth, player.getHealth() + Math.max(0.0, amount)));
    }

    /** Starts the bounded orbit mode without removing the real item from the player's inventory. */
    private void startLeviathanOrbit(Player player, ItemStack axe) {
        UUID playerId = player.getUniqueId();
        if (activeLeviathanOrbits.containsKey(playerId)) {
            player.sendMessage("§b[Leviatán] §7El hacha ya está orbitando.");
            return;
        }
        if (!tryUseLeviathanCooldown(player, 20_000L, "Órbita rúnica")) return;

        ItemDisplay display = player.getWorld().spawn(player.getLocation().add(0, 1.2, 0), ItemDisplay.class);
        display.setItemStack(axe.clone());
        display.setInvulnerable(true);
        display.setPersistent(false);

        AtomicInteger orbitTicks = new AtomicInteger();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    display.remove();
                    activeLeviathanOrbits.remove(playerId);
                    cancel();
                    return;
                }

                int tick = orbitTicks.getAndIncrement();
                if (tick >= 100) {
                    display.remove();
                    activeLeviathanOrbits.remove(playerId);
                    cancel();
                    player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.9f, 1.2f);
                    return;
                }

                double angle = tick * 0.36;
                Location center = player.getLocation().add(0, 1.15, 0);
                Location position = center.clone().add(Math.cos(angle) * 2.2, Math.sin(angle * 2.0) * 0.35, Math.sin(angle) * 2.2);
                display.teleport(position);
                display.setRotation((float) Math.toDegrees(-angle), (float) (Math.sin(angle) * 35));
                player.getWorld().spawnParticle(Particle.SNOWFLAKE, position, 2, 0.08, 0.08, 0.08, 0.01);

                if (tick % 10 != 0) return;
                for (Entity nearby : display.getNearbyEntities(1.4, 1.4, 1.4)) {
                    if (!(nearby instanceof LivingEntity victim) || victim instanceof Player || victim instanceof ArmorStand || victim.equals(player)) continue;
                    applySyntheticDamage(victim, 5.0, player);
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        activeLeviathanOrbits.put(playerId, task);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.9f, 0.7f);
        player.sendMessage("§b[Leviatán] §fÓrbita rúnica activada.");
    }

    /** Throws the axe and returns the exact item through addItem, never overwriting a hand slot. */
    private void throwLeviathanAxe(Player player, EquipmentSlot hand, ItemStack item) {
        UUID playerId = player.getUniqueId();
        if (pendingLeviathanAxes.containsKey(playerId)) return;
        if (!tryUseLeviathanCooldown(player, 2_000L, "Lanzamiento rúnico")) return;

        ItemStack axe = item.clone();
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(null);
        } else {
            player.getInventory().setItemInOffHand(null);
        }
        pendingLeviathanAxes.put(playerId, new PendingLeviathanAxe(axe));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 1.0f, 0.5f);

        Snowball ball = player.launchProjectile(Snowball.class);
        ball.setMetadata("leviathan_axe", new FixedMetadataValue(plugin, true));
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (ball.isDead() || !ball.isValid()) {
                task.cancel();
                return;
            }
            ball.getWorld().spawnParticle(Particle.SNOWFLAKE, ball.getLocation(), 4, 0.1, 0.1, 0.1, 0.01);
        }, 1L, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> returnLeviathanAxe(player), 20L);
    }

    /** Restores a pending axe safely; overflow drops only the exact missing axe at the player. */
    private void returnLeviathanAxe(Player player) {
        PendingLeviathanAxe pending = pendingLeviathanAxes.remove(player.getUniqueId());
        if (pending == null) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(pending.item());
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover).setOwner(player.getUniqueId());
        }
        if (player.isOnline()) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 1.2f);
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 10, 0.2, 0.2, 0.2, 0.05);
        }
    }

    private boolean tryUseLeviathanCooldown(Player player, long cooldownMillis, String abilityName) {
        long now = System.currentTimeMillis();
        long lastUse = leviathanCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remaining = cooldownMillis - (now - lastUse);
        if (remaining > 0L) {
            player.sendMessage("§b[Leviatán] §7" + abilityName + " disponible en §f" + String.format("%.1f", remaining / 1000.0) + "s§7.");
            return false;
        }
        leviathanCooldowns.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();

        // Tsunami del Tridente de Poseidón al impactar (bloque o entidad)
        if (proj instanceof Trident trident
                && "poseidon_trident".equals(trident.getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING))) {
            Location loc = event.getHitBlock() != null
                    ? event.getHitBlock().getLocation().add(0.5, 1, 0.5)
                    : trident.getLocation();
            triggerTsunami(loc, trident.getShooter() instanceof Entity s ? s : null);
        }

        if (proj instanceof Snowball ball) {
            if (ball.hasMetadata("loki_magic")) {
                Location loc = ball.getLocation();
                loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 35, 1.0, 1.0, 1.0, 0.1);
                loc.getWorld().spawnParticle(Particle.CRIT, loc, 15, 0.5, 0.5, 0.5, 0.05);
                loc.getWorld().playSound(loc, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f);

                if (event.getHitEntity() instanceof LivingEntity victim) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 2, false, true));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, false, true));
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_BAT_DEATH, 0.8f, 0.5f);
                }
            } else if (ball.hasMetadata("leviathan_axe")) {
                Location loc = ball.getLocation();
                loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc, 30, 0.5, 0.5, 0.5, 0.1);
                loc.getWorld().playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f);

                if (event.getHitEntity() instanceof LivingEntity victim) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 5, false, true));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, true));
                    if (ball.getShooter() instanceof org.bukkit.entity.Entity shooter) {
                        applySyntheticDamage(victim, 10.0, shooter);
                    } else {
                        applySyntheticDamage(victim, 10.0, null);
                    }
                }
            }
        }
    }

    /** Applies secondary damage without recursively triggering custom weapon effects. */
    private void applySyntheticDamage(LivingEntity target, double amount, Entity source) {
        UUID targetId = target.getUniqueId();
        syntheticDamageTargets.add(targetId);
        try {
            if (source == null) {
                target.damage(amount);
            } else {
                target.damage(amount, source);
            }
        } finally {
            syntheticDamageTargets.remove(targetId);
        }
    }

    /** Comparte un cooldown por jugador entre poderes activables para evitar spam de tareas y partículas. */
    private boolean tryUseCooldown(Player player, long cooldownMillis, String abilityName) {
        long now = System.currentTimeMillis();
        long lastUse = scepterCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remaining = cooldownMillis - (now - lastUse);
        if (remaining > 0L) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&c[COOLDOWN] &e" + abilityName + " estará lista en &c" + String.format("%.1f", remaining / 1000.0) + "s&e."));
            return false;
        }
        scepterCooldowns.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        // Sed de Sangre del Filo de Ares — cada víctima otorga Fuerza acumulable (hasta V)
        Player killer = event.getEntity().getKiller();
        if (killer == null || !isHoldingItem(killer, "ares_blade")) {
            return;
        }
        int nextAmplifier = 0;
        PotionEffect current = killer.getPotionEffect(PotionEffectType.STRENGTH);
        if (current != null) {
            nextAmplifier = Math.min(current.getAmplifier() + 1, 4); // cap Fuerza V (amplifier 4)
        }
        killer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 600, nextAmplifier, false, true)); // 30s, refresca
        Location kLoc = killer.getLocation();
        kLoc.getWorld().spawnParticle(Particle.DUST, kLoc.clone().add(0, 1, 0), 15, 0.4, 0.6, 0.4,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(180, 0, 0), 1.5f));
        kLoc.getWorld().playSound(kLoc, Sound.ENTITY_WITHER_HURT, 0.5f, 1.4f);
    }

    @EventHandler
    public void onBowShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            ItemStack bow = event.getBow();
            if (bow != null && bow.hasItemMeta()) {
                String type = bow.getItemMeta().getPersistentDataContainer().get(OdysseyItemManager.ITEM_KEY, PersistentDataType.STRING);
                if ("artemis_bow".equals(type)) {
                    // Marcar la flecha
                    event.getProjectile().setMetadata("artemis_arrow", new FixedMetadataValue(plugin, true));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                synchronizeKratosBlades(player);
            }
        });
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                synchronizeKratosBlades(player);
            }
        });
    }

    static boolean shouldCancelKratosInventoryClick(
            ClickType click,
            int slot,
            int hotbarButton,
            boolean currentIsTemp,
            boolean cursorIsTemp,
            boolean offhandIsTemp,
            boolean hotbarIsTemp
    ) {
        if (currentIsTemp || cursorIsTemp || hotbarIsTemp) {
            return true;
        }
        if (click == ClickType.SWAP_OFFHAND && offhandIsTemp) {
            return true;
        }
        if (slot == 40 && offhandIsTemp) {
            return true;
        }
        return click == ClickType.NUMBER_KEY && slot == 40 && offhandIsTemp;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int hotbarButton = event.getHotbarButton();
        ItemStack hotbarItem = (hotbarButton >= 0 && hotbarButton < 9)
                ? player.getInventory().getItem(hotbarButton)
                : null;
        if (shouldCancelKratosInventoryClick(
                event.getClick(),
                event.getSlot(),
                hotbarButton,
                isKratosTempOffhand(event.getCurrentItem()),
                isKratosTempOffhand(event.getCursor()),
                isKratosTempOffhand(player.getInventory().getItemInOffHand()),
                isKratosTempOffhand(hotbarItem)
        )) {
            event.setCancelled(true);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                synchronizeKratosBlades(player);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isKratosTempOffhand(event.getOldCursor()) || isKratosTempOffhand(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isKratosTempOffhand(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getItemDrop().remove();
        }
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isKratosBlade(event.getMainHandItem())
                || isKratosBlade(event.getOffHandItem())
                || isKratosTempOffhand(event.getOffHandItem())) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    synchronizeKratosBlades(player);
                }
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        returnLeviathanAxe(event.getPlayer());
        restoreKratosOffhand(event.getPlayer(), true);
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        PendingLeviathanAxe pending = pendingLeviathanAxes.remove(playerId);
        if (pending != null) {
            // The axe was removed only for flight; it must participate in the same death flow as any held item.
            event.getDrops().add(pending.item());
        }
        if (!savedOffhands.containsKey(playerId)) {
            return;
        }

        ItemStack originalOffHand = savedOffhands.remove(playerId);
        event.getDrops().removeIf(this::isKratosTempOffhand);

        if (event.getKeepInventory()) {
            player.getInventory().setItemInOffHand(isAir(originalOffHand) ? null : originalOffHand.clone());
            return;
        }

        if (!isAir(originalOffHand)) {
            event.getDrops().add(originalOffHand.clone());
        }
    }
}
