package org.metamechanists.odysseia.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.metamechanists.odysseia.Odysseia;

/** Opt-in fall protection, limited to explicitly configured island worlds. */
public final class IslandFallSafetyListener implements Listener {
    private final Odysseia plugin;

    public IslandFallSafetyListener(Odysseia plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) return;
        var config = plugin.getConfig();
        if (!config.getBoolean("island-fall-safety.enabled", false)) return;
        String worldName = player.getWorld().getName();
        if (config.getStringList("island-fall-safety.worlds").stream()
                .noneMatch(worldName::equalsIgnoreCase)) return;

        event.setCancelled(true);
        player.setFallDistance(0.0f);
    }
}
