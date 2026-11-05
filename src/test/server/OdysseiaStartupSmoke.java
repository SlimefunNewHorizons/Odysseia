package org.metamechanists.odysseia.smoke;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.listeners.ServerAutomationListener;

/** Disposable-server startup check; it never delivers rewards or creates player data. */
public final class OdysseiaStartupSmoke extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::checkStartup, 60L);
    }

    private void checkStartup() {
        try {
            Odysseia plugin = (Odysseia) Bukkit.getPluginManager().getPlugin("Odysseia");
            require(plugin != null && plugin.isEnabled(), "plugin enabled");
            require(plugin.getKitClaimService() != null, "claim service initialized");
            require(plugin.getModalityService() != null, "modality service initialized");
            long handlers = HandlerList.getRegisteredListeners(plugin).stream()
                    .filter(handler -> handler.getListener() instanceof ServerAutomationListener).count();
            require(handlers == 5L, "all five automation handlers registered");
            require(plugin.getConfig().getString("starter-kit.base-modality", "").isEmpty(),
                    "legacy base-kit default preserved");
            require(plugin.getConfig().getConfigurationSection("kits.inicial-clasico") != null,
                    "optional Classic kit present");
            require(plugin.getConfig().getConfigurationSection("kits.inicial-onechunk") != null,
                    "optional OneChunk kit present");
            require(plugin.getConfig().getString("kits.inicial-onechunk.protection-alias", "").isEmpty(),
                    "island kit has no ProtectionStone");
            require(new NamespacedKey(plugin, "starter_kit_delivered").getNamespace().equals("odysseia"),
                    "PDC namespace preserved");
            getLogger().info("ODYSSEIA_STARTUP_SMOKE PASS checks=9");
        } catch (Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "ODYSSEIA_STARTUP_SMOKE FAIL", failure);
        } finally {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 2L);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
