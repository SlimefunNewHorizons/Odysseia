package org.metamechanists.odysseia.listeners;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.utils.WebhookSender;

/** Reports actual moderation actions to the dedicated Discord channel. */
public final class ModerationListener implements Listener {

    private final Odysseia plugin;
    /** Avoid a noisy warning for every moderation event when the optional endpoint is absent. */
    private final AtomicBoolean invalidWebhookWarningLogged = new AtomicBoolean();

    public ModerationListener(Odysseia plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        String reason = PlainTextComponentSerializer.plainText().serialize(event.reason());
        if (isOperationalKick(reason)) {
            return;
        }

        boolean isBan = event.getCause() == PlayerKickEvent.Cause.BANNED;

        // Keep the in-game feedback for real sanctions, never for maintenance kicks.
        Location location = player.getLocation();
        location.getWorld().strikeLightningEffect(location);
        location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        location.getWorld().spawnParticle(Particle.EXPLOSION, location.add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);

        // Va antes que Discord y sin depender de el: si el webhook falta o esta mal puesto, quien
        // esta jugando debe enterarse igual. Antes ambos avisos colgaban del mismo return.
        anunciarEnElServidor(player.getName(), reason, isBan);

        if (!plugin.getConfig().getBoolean("discord.enabled", true)) {
            return;
        }
        String webhookUrl = moderationWebhook();
        if (webhookUrl == null) {
            return;
        }

        String serverLabel = serverLabel();
        String title = isBan ? "Sanción aplicada · Baneo" : "Sanción aplicada · Expulsión";
        int color = isBan ? 15105570 : 16750848;
        String action = isBan ? "BANEADO" : "EXPULSADO";
        String jsonPayload = String.format(
                "{\"username\":\"Odysseia Moderación\",\"embeds\":[{"
                        + "\"title\":\"%s\",\"description\":\"Se registró una acción de moderación en DrakesCraft.\","
                        + "\"color\":%d,\"fields\":["
                        + "{\"name\":\"Jugador\",\"value\":\"`%s`\",\"inline\":true},"
                        + "{\"name\":\"Acción\",\"value\":\"%s\",\"inline\":true},"
                        + "{\"name\":\"Motivo\",\"value\":\"%s\",\"inline\":false}],"
                        + "\"footer\":{\"text\":\"%s\"}}]}",
                Odysseia.escapeJson(title), color, Odysseia.escapeJson(player.getName()), action,
                Odysseia.escapeJsonCampo(reason), Odysseia.escapeJsonCampo(serverLabel));
        WebhookSender.sendAsync(plugin, webhookUrl, jsonPayload);
    }

    /**
     * Sanciones que no expulsan a nadie y por eso no llegaban a Discord.
     *
     * Un mute o un jail no disparan PlayerKickEvent, asi que el canal de moderacion solo
     * recogia expulsiones y baneos: silenciar a alguien no dejaba rastro en ningun sitio salvo
     * la consola. Se leen los comandos ya ejecutados, tanto de consola como de staff en juego,
     * en vez de acoplarse a la API de Essentials.
     */
    private static final Map<String, String> SANCIONES = Map.ofEntries(
            Map.entry("mute", "SILENCIADO"), Map.entry("tempmute", "SILENCIADO TEMPORALMENTE"),
            Map.entry("unmute", "DESILENCIADO"), Map.entry("jail", "ENCARCELADO"),
            Map.entry("unjail", "LIBERADO"), Map.entry("tempban", "BANEADO TEMPORALMENTE"),
            Map.entry("banip", "BANEADO POR IP"), Map.entry("unban", "DESBANEADO"),
            Map.entry("warn", "ADVERTIDO"), Map.entry("drakeswarn", "ADVERTIDO"),
            Map.entry("dwarn", "ADVERTIDO"));

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        registrarSancion("CONSOLA", event.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStaffCommand(PlayerCommandPreprocessEvent event) {
        registrarSancion(event.getPlayer().getName(), event.getMessage().substring(1));
    }

    /** Reconoce el comando, saca a quien va dirigido y lo reporta. */
    private void registrarSancion(String autor, String comandoCompleto) {
        String[] partes = comandoCompleto.trim().split("\\s+");
        if (partes.length < 2) return;

        String etiqueta = partes[0].toLowerCase(Locale.ROOT);
        int separador = etiqueta.lastIndexOf(':');
        if (separador >= 0) etiqueta = etiqueta.substring(separador + 1);

        String accion = SANCIONES.get(etiqueta);
        if (accion == null) return;

        String objetivo = partes[1];
        String motivo = partes.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(partes, 2, partes.length))
                : "Sin motivo indicado";

        reportarSancion(autor, objetivo, accion, motivo);
    }

    /** Manda la sancion al canal de moderacion, con quien la aplico. */
    private void reportarSancion(String autor, String objetivo, String accion, String motivo) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return;
        String webhookUrl = moderationWebhook();
        if (webhookUrl == null) return;

        String jsonPayload = String.format(
                "{\"username\":\"Odysseia Moderación\",\"embeds\":[{"
                        + "\"title\":\"Sanción aplicada · %s\","
                        + "\"description\":\"Se registró una acción de moderación en DrakesCraft.\","
                        + "\"color\":16750848,\"fields\":["
                        + "{\"name\":\"Jugador\",\"value\":\"`%s`\",\"inline\":true},"
                        + "{\"name\":\"Acción\",\"value\":\"%s\",\"inline\":true},"
                        + "{\"name\":\"Aplicada por\",\"value\":\"`%s`\",\"inline\":true},"
                        + "{\"name\":\"Motivo\",\"value\":\"%s\",\"inline\":false}],"
                        + "\"footer\":{\"text\":\"%s\"}}]}",
                Odysseia.escapeJson(accion), Odysseia.escapeJson(objetivo),
                Odysseia.escapeJson(accion), Odysseia.escapeJson(autor),
                Odysseia.escapeJsonCampo(motivo), Odysseia.escapeJsonCampo(serverLabel()));
        WebhookSender.sendAsync(plugin, webhookUrl, jsonPayload);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)
                || event.getResult() != PlayerLoginEvent.Result.KICK_BANNED) {
            return;
        }

        String webhookUrl = moderationWebhook();
        if (webhookUrl == null) {
            return;
        }

        String jsonPayload = String.format(
                "{\"username\":\"Odysseia Moderación\",\"embeds\":[{"
                        + "\"title\":\"Acceso bloqueado · Jugador baneado\","
                        + "\"description\":\"Un jugador baneado intentó conectarse a DrakesCraft.\","
                        + "\"color\":15158332,\"fields\":["
                        + "{\"name\":\"Jugador\",\"value\":\"`%s`\",\"inline\":true},"
                        + "{\"name\":\"Mensaje de bloqueo\",\"value\":\"%s\",\"inline\":false}],"
                        + "\"footer\":{\"text\":\"%s\"}}]}",
                Odysseia.escapeJson(event.getPlayer().getName()),
                Odysseia.escapeJsonCampo(event.getKickMessage()), Odysseia.escapeJsonCampo(serverLabel()));
        WebhookSender.sendAsync(plugin, webhookUrl, jsonPayload);
    }

    /**
     * Cuenta la sancion en el chat del servidor.
     *
     * El aviso solo salia por Discord, asi que quien estaba jugando veia desaparecer a alguien sin
     * saber por que. Enterarse dentro es justamente lo que hace que una sancion tenga efecto sobre
     * los demas.
     *
     * Los kicks por inactividad se anuncian con otro tono: son automaticos, pasan a diario y
     * tratarlos como un castigo confundiria a quien los lea.
     */
    private void anunciarEnElServidor(String nombre, String motivo, boolean esBaneo) {
        if (!plugin.getConfig().getBoolean("moderacion.anuncio-ingame.enabled", true)) return;

        boolean porInactividad = motivo != null
                && motivo.toLowerCase(Locale.ROOT).contains("inactiv");
        if (porInactividad && !plugin.getConfig().getBoolean("moderacion.anuncio-ingame.incluir-afk", true)) {
            return;
        }

        String texto;
        if (porInactividad) {
            texto = "&8[&7Servidor&8] &7" + nombre + " &8fue desconectado por inactividad.";
        } else if (esBaneo) {
            texto = "&6DrakesCraft &8· &c&l" + nombre + " &cha sido baneado."
                    + (motivo == null || motivo.isBlank() ? "" : " &7Motivo: &f" + motivo);
        } else {
            texto = "&6DrakesCraft &8· &e" + nombre + " &6ha sido expulsado."
                    + (motivo == null || motivo.isBlank() ? "" : " &7Motivo: &f" + motivo);
        }

        String mensaje = org.bukkit.ChatColor.translateAlternateColorCodes('&', texto);
        Bukkit.getOnlinePlayers().forEach(destinatario -> destinatario.sendMessage(mensaje));
        plugin.getLogger().info("[Moderation] " + org.bukkit.ChatColor.stripColor(mensaje));
    }

    // Package-visible for the focused no-webhook regression test; callers remain internal listeners.
    String moderationWebhook() {
        String url = plugin.getConfig().getString("discord.webhook-moderation-url", "");
        if (!isValidModerationWebhook(url)) {
            if (invalidWebhookWarningLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("[Moderation] Webhook de moderación inválido o no configurado; "
                        + "los avisos posteriores se omitirán hasta el próximo arranque.");
            }
            return null;
        }
        return url;
    }

    static boolean isValidModerationWebhook(String url) {
        return url != null && !url.isBlank() && !url.startsWith("REPLACE_ME")
                && WebhookSender.isDiscordWebhookUrl(url) && WebhookSender.isAllowedHttpsUrl(url);
    }

    private String serverLabel() {
        String label = plugin.getConfig().getString("presence.server-label", "");
        return label == null || label.isBlank() ? Bukkit.getServer().getName() : label;
    }

    private boolean isOperationalKick(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return normalized.contains("reinicio") || normalized.contains("restarting")
                || normalized.contains("maintenance") || normalized.contains("mantenimiento")
                // Network disconnects are not moderation actions and must never alert staff.
                || normalized.contains("timed out") || normalized.contains("timeout")
                || normalized.contains("connection reset") || normalized.contains("connection closed")
                || normalized.contains("forcibly closed") || normalized.contains("disconnected");
    }
}
