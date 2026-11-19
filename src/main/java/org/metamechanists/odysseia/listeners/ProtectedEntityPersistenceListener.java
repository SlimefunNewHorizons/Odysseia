package org.metamechanists.odysseia.listeners;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.Odysseia;

/**
 * Garantiza que aldeanos (con o sin nametag) y mascotas/animales domesticables
 * ubicados dentro de regiones protegidas de ProtectionStones o WorldGuard nunca
 * hagan despawn ni sean eliminados por cleaners automaticos.
 */
public final class ProtectedEntityPersistenceListener implements Listener {

    private static final String PDC_KEY = "region_protected_persistence";
    private static final long MIN_SWEEP_INTERVAL_TICKS = 200L;
    private static final long DEFAULT_SWEEP_INTERVAL_TICKS = 1200L;

    private final Odysseia plugin;
    private final NamespacedKey managedKey;
    private final boolean enabled;
    private final long sweepIntervalTicks;

    private final Method psRegionFromLocation;
    private final boolean worldGuardAvailable;
    private boolean protectionWarningLogged;
    private BukkitTask sweepTask;

    public ProtectedEntityPersistenceListener(Odysseia plugin) {
        this.plugin = plugin;
        this.managedKey = new NamespacedKey(plugin, PDC_KEY);
        this.enabled = plugin.getConfig().getBoolean("entity-protection.enabled", true);
        long configuredInterval = plugin.getConfig().getLong(
                "entity-protection.sweep-interval-ticks", DEFAULT_SWEEP_INTERVAL_TICKS);
        this.sweepIntervalTicks = Math.max(MIN_SWEEP_INTERVAL_TICKS, configuredInterval);
        this.psRegionFromLocation = findProtectionStonesLookup();
        this.worldGuardAvailable = isWorldGuardClassPresent();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void startSweep() {
        if (!enabled) {
            return;
        }
        stopSweep();
        this.sweepTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sweepLoadedEntities, 40L, sweepIntervalTicks);
    }

    public void stopSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!enabled) return;
        evaluateEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!enabled) return;
        for (Entity entity : event.getEntities()) {
            evaluateEntity(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTame(EntityTameEvent event) {
        if (!enabled) return;
        evaluateEntity(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        if (!enabled) return;
        evaluateEntity(event.getEntity());
        evaluateEntity(event.getMother());
        evaluateEntity(event.getFather());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        if (!enabled) return;
        for (Entity transformed : event.getTransformedEntities()) {
            evaluateEntity(transformed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!enabled) return;
        evaluateEntity(event.getRightClicked());
    }

    private void sweepLoadedEntities() {
        if (!enabled) return;
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                evaluateEntity(entity);
            }
        }
    }

    private void evaluateEntity(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (!isEligible(living) || !living.isValid() || living.isDead()) {
            return;
        }

        boolean inProtectedRegion = isInProtectedRegion(living.getLocation());
        PersistentDataContainer pdc = living.getPersistentDataContainer();
        boolean managed = pdc.has(managedKey, PersistentDataType.BYTE);
        boolean hasCustomName = living.customName() != null;
        boolean isTamed = living instanceof Tameable tameable && tameable.isTamed();

        ProtectedEntityPolicy.Action action = ProtectedEntityPolicy.evaluate(
                true, inProtectedRegion, managed, hasCustomName, isTamed);

        if (action == ProtectedEntityPolicy.Action.PROTECT) {
            if (living.getRemoveWhenFarAway()) {
                living.setRemoveWhenFarAway(false);
            }
            if (!living.isPersistent()) {
                living.setPersistent(true);
            }
            if (!managed) {
                pdc.set(managedKey, PersistentDataType.BYTE, (byte) 1);
            }
        } else if (action == ProtectedEntityPolicy.Action.RELEASE_MANAGED) {
            pdc.remove(managedKey);
            living.setRemoveWhenFarAway(true);
        }
    }

    private boolean isEligible(LivingEntity entity) {
        if (entity instanceof Player || entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof Villager || entity instanceof Tameable || entity instanceof AbstractHorse) {
            return true;
        }
        return ProtectedEntityPolicy.isEligibleTypeName(entity.getType().name());
    }

    private boolean isInProtectedRegion(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (psRegionFromLocation != null) {
            try {
                if (psRegionFromLocation.invoke(null, location) != null) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logProtectionWarningOnce(exception);
            }
        }
        if (worldGuardAvailable) {
            return isInWorldGuardNonGlobalRegion(location);
        }
        return false;
    }

    private boolean isInWorldGuardNonGlobalRegion(Location location) {
        try {
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = wgClass.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);

            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weLocation = bukkitAdapter.getMethod("adapt", Location.class).invoke(null, location);

            Class<?> weLocationClass = Class.forName("com.sk89q.worldedit.util.Location");
            Object applicable = query.getClass()
                    .getMethod("getApplicableRegions", weLocationClass)
                    .invoke(query, weLocation);

            Object regionsObj = applicable.getClass().getMethod("getRegions").invoke(applicable);
            if (!(regionsObj instanceof Iterable<?> regions)) {
                return false;
            }
            List<String> ids = new ArrayList<>();
            for (Object region : regions) {
                Object id = region.getClass().getMethod("getId").invoke(region);
                if (id instanceof String s) {
                    ids.add(s);
                }
            }
            return ProtectedEntityPolicy.hasNonGlobalRegion(ids);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logProtectionWarningOnce(exception);
            return false;
        }
    }

    private Method findProtectionStonesLookup() {
        try {
            Class<?> psRegion = Class.forName("dev.espi.protectionstones.PSRegion");
            return psRegion.getMethod("fromLocation", Location.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private boolean isWorldGuardClassPresent() {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private void logProtectionWarningOnce(Throwable exception) {
        if (protectionWarningLogged) {
            return;
        }
        protectionWarningLogged = true;
        plugin.getLogger().warning("[EntityProtection] No se pudo consultar WorldGuard/ProtectionStones: "
                + exception.getClass().getSimpleName() + ": " + exception.getMessage());
    }
}
