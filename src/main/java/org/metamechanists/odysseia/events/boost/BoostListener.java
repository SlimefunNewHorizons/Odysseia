package org.metamechanists.odysseia.events.boost;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escucha eventos del juego para aplicar los multiplicadores activos del ServerBoostManager
 * con topes estrictos anti-inflación, anti-spawner y aislamiento de modalidades.
 */
public class BoostListener implements Listener {

    public static final double MAX_DRACMAS_PER_MINUTE = 600.0;
    public static final double MAX_DRACMAS_PER_WINDOW = 8000.0;
    private static final int MAX_XP_BONUS_PER_ORB = 250;
    private static final int MAX_SF_XP_BONUS_PER_ORB = 150;
    private static final NamespacedKey SPAWNER_MOB_KEY = new NamespacedKey("odysseia", "spawner_origin");

    private final ServerBoostManager boostManager;
    private final Map<UUID, MinuteQuota> economyQuota = new ConcurrentHashMap<>();

    private static final class MinuteQuota {
        long minuteBucket;
        double minuteEarned;
        long halfHourBucket;
        double halfHourEarned;
    }

    public BoostListener(ServerBoostManager boostManager) {
        this.boostManager = boostManager;
    }

    private static boolean isEligibleSurvival(Player player) {
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) return false;
        World w = player.getWorld();
        if (w == null) return false;
        String name = w.getName().toLowerCase(Locale.ROOT);
        return !name.contains("lab") && !name.equals("lobby");
    }

    private static boolean isSlimefunWorld(World world) {
        if (world == null) return false;
        String name = world.getName().toLowerCase(Locale.ROOT);
        return !name.contains("clasico") && !name.contains("lab") && !name.equals("lobby");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.getEntity().getPersistentDataContainer().set(SPAWNER_MOB_KEY, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        int base = event.getAmount();
        if (base <= 0) return;
        Player player = event.getPlayer();
        if (!isEligibleSurvival(player)) return;

        int extra = 0;
        double xpMult = boostManager.getMultiplier(ServerBoostManager.BoostCategory.XP);
        if (xpMult > 1.0) {
            int xpBonus = (int) Math.round(base * (xpMult - 1.0));
            extra += Math.min(MAX_XP_BONUS_PER_ORB, Math.max(0, xpBonus));
        }

        double sfMult = boostManager.getMultiplier(ServerBoostManager.BoostCategory.SLIMEFUN);
        if (sfMult > 1.0 && isSlimefunWorld(player.getWorld())) {
            int sfBonus = (int) Math.round(base * (sfMult - 1.0));
            extra += Math.min(MAX_SF_XP_BONUS_PER_ORB, Math.max(0, sfBonus));
        }

        if (extra > 0) {
            event.setAmount(base + extra);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMonsterKill(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster mob)) return;
        Player killer = mob.getKiller();
        if (!isEligibleSurvival(killer)) return;
        if (mob.getPersistentDataContainer().has(SPAWNER_MOB_KEY, PersistentDataType.BYTE)) return;

        double mult = activeEconomyMultiplier();
        if (mult <= 1.0) return;
        // Recompensa base activa 18 ₯ escalada por el bono, acotada por minuto/sesión
        double bonus = Math.min(90.0, 18.0 * (mult - 1.0));
        grantCappedDracmas(killer, bonus);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishCatch(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player fisher = event.getPlayer();
        if (!isEligibleSurvival(fisher)) return;

        double mult = activeEconomyMultiplier();
        if (mult <= 1.0) return;
        double bonus = Math.min(120.0, 25.0 * (mult - 1.0));
        grantCappedDracmas(fisher, bonus);
    }

    private double activeEconomyMultiplier() {
        double eco = boostManager.getMultiplier(ServerBoostManager.BoostCategory.ECONOMIA);
        double drac = boostManager.getMultiplier(ServerBoostManager.BoostCategory.DRACMAS);
        return Math.max(eco, drac);
    }

    private void grantCappedDracmas(Player player, double rawAmount) {
        if (rawAmount <= 0.0) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        long minBucket = nowSec / 60L;
        long halfHourBucket = nowSec / 1800L;

        MinuteQuota q = economyQuota.computeIfAbsent(player.getUniqueId(), k -> new MinuteQuota());
        double grant;
        synchronized (q) {
            if (q.minuteBucket != minBucket) {
                q.minuteBucket = minBucket;
                q.minuteEarned = 0.0;
            }
            if (q.halfHourBucket != halfHourBucket) {
                q.halfHourBucket = halfHourBucket;
                q.halfHourEarned = 0.0;
            }
            double remMin = Math.max(0.0, MAX_DRACMAS_PER_MINUTE - q.minuteEarned);
            double remWin = Math.max(0.0, MAX_DRACMAS_PER_WINDOW - q.halfHourEarned);
            grant = Math.min(rawAmount, Math.min(remMin, remWin));
            if (grant < 1.0) return;
            q.minuteEarned += grant;
            q.halfHourEarned += grant;
        }

        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null && rsp.getProvider() != null) {
                rsp.getProvider().depositPlayer(player, Math.floor(grant));
            }
        } catch (Throwable ignored) {
        }
    }
}
