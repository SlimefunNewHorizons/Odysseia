package org.metamechanists.odysseia.listeners;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.metamechanists.odysseia.Odysseia;

/**
 * Entrega el resource pack del servidor al entrar, sin depender de
 * {@code server.properties}: el arranque de Pterodactyl reescribe ese fichero
 * y borra los campos del pack (ticket 476). Aqui el pack vive en config.yml,
 * se recarga con {@code /odysseia reload} y las cuentas Bedrock (Floodgate) se
 * omiten porque Geyser gestiona sus propios packs.
 */
public final class ResourcePackListener implements Listener {
    private static final String NS = "odysseia:resource-pack:";

    private final Odysseia plugin;

    public ResourcePackListener(Odysseia plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection section() {
        return plugin.getConfig().getConfigurationSection("resource-pack");
    }

    public boolean enabled() {
        ConfigurationSection s = section();
        return s != null && s.getBoolean("enabled", false) && !s.getString("url", "").isBlank();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (isBedrock(player)) {
            return;
        }
        long delay = Math.max(1L, section().getLong("delay-ticks", 40L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, delay);
    }

    /** Envia el pack configurado; devuelve false si la config no es valida. */
    public boolean send(Player player) {
        ConfigurationSection s = section();
        if (s == null) {
            return false;
        }
        String url = s.getString("url", "").trim();
        String sha1 = s.getString("sha1", "").trim().toLowerCase(Locale.ROOT);
        boolean required = s.getBoolean("required", false);
        String prompt = colorize(s.getString("prompt",
                "§bDrakesCraft §7ofrece texturas para los items de Slimefun. §aOpcional."));
        if (url.isEmpty()) {
            return false;
        }
        byte[] hash = decodeSha1(sha1);
        if (hash == null) {
            plugin.getLogger().warning("[ResourcePack] sha1 invalido en config.yml; se envia sin hash.");
        }
        String finalUrl = url;
        if (s.getBoolean("cache-bust", true) && hash != null && !url.contains("?")) {
            finalUrl = url + "?v=" + sha1.substring(0, 12);
        }
        UUID id = UUID.nameUUIDFromBytes((NS + (hash != null ? sha1 : url))
                .getBytes(StandardCharsets.UTF_8));
        try {
            player.setResourcePack(id, finalUrl, hash, prompt, required);
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[ResourcePack] No se pudo enviar a "
                    + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled() || !section().getBoolean("log-status", true)) {
            return;
        }
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    plugin.getLogger().warning("[ResourcePack] " + event.getPlayer().getName()
                            + " -> " + event.getStatus());
            default -> { }
        }
    }

    /** El cliente muestra el prompt tal cual: traduce los codigos & de config.yml. */
    static String colorize(String text) {
        return text == null ? null : ChatColor.translateAlternateColorCodes('&', text);
    }

    static byte[] decodeSha1(String hex) {
        if (hex == null || hex.length() != 40 || !hex.matches("[0-9a-f]{40}")) {
            return null;
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Floodgate: API por reflexion si esta, y si no, el prefijo "." del nick. */
    public static boolean isBedrock(Player player) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method isFloodgate = api.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(isFloodgate.invoke(instance, player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return player.getName().startsWith(".");
        }
    }
}
