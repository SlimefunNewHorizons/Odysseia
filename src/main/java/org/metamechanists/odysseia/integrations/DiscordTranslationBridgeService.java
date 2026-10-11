package org.metamechanists.odysseia.integrations;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessageReceivedEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import lombok.extern.java.Log;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.metamechanists.odysseia.Odysseia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio de Puente de Traducción Bidireccional entre DiscordSRV y Minecraft
 * utilizando directamente la API de Star Translate (LibreTranslate en
 * https://translate.drakescraft.cl).
 *
 * Fix de timing: el registro con DiscordSRV se hace con 20 ticks de delay para asegurarse
 * de que DiscordSRV haya completado su inicialización asíncrona antes de intentar suscribir.
 */
@Log
public class DiscordTranslationBridgeService {

    private final Odysseia plugin;
    private final HttpClient httpClient;

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private boolean translateDiscordToMc;
    private boolean translateMcToDiscord;
    private String mcTargetLanguage;
    private String discordTargetLanguage;

    private volatile boolean subscribed = false;

    public DiscordTranslationBridgeService(Odysseia plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("discord-translator.enabled", true);
        String rawUrl = config.getString("discord-translator.api-url", "https://translate.drakescraft.cl");
        if (rawUrl.endsWith("/translate")) {
            this.baseUrl = rawUrl;
        } else {
            this.baseUrl = rawUrl.replaceAll("/+$", "") + "/translate";
        }

        this.apiKey = config.getString("discord-translator.api-key", "");
        this.translateDiscordToMc = config.getBoolean("discord-translator.translate-discord-to-mc", false);
        this.translateMcToDiscord = config.getBoolean("discord-translator.translate-mc-to-discord", false);
        this.mcTargetLanguage = config.getString("discord-translator.mc-target-language", "es");
        this.discordTargetLanguage = config.getString("discord-translator.discord-target-language", "en");

        if (enabled && !subscribed) {
            // Delay de 20 ticks (1 seg) para garantizar que DiscordSRV terminó su init asíncrono
            Bukkit.getScheduler().runTaskLater(plugin, this::registerDiscordSRV, 20L);
        }
    }

    public void registerDiscordSRV() {
        if (subscribed) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            // DiscordSRV es softdepend: su ausencia es una configuración válida, no una avería.
            log.info("[Odysseia] DiscordSRV no está instalado - puente de traducción inactivo.");
            return;
        }
        try {
            DiscordSRV.api.subscribe(this);
            subscribed = true;
            log.info("[Odysseia] ✅ Puente de traducción bidireccional registrado con DiscordSRV.");
        } catch (Throwable t) {
            log.warning("[Odysseia] No se pudo suscribir a DiscordSRV: " + t.getMessage());
        }
    }

    public void unregisterDiscordSRV() {
        if (!subscribed) return;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
                DiscordSRV.api.unsubscribe(this);
            }
            subscribed = false;
            log.info("[Odysseia] Puente de traducción desuscrito de DiscordSRV.");
        } catch (Throwable t) {
            // Silenciar en apagado
        }
    }

    // ─── Traducción ────────────────────────────────────────────────────────────

    /**
     * Detección de idioma a través del endpoint Star /detect
     */
    public CompletableFuture<String> detectLanguage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return CompletableFuture.completedFuture("");
        }

        String detectUrl = baseUrl.replaceAll("/translate$", "/detect");
        String jsonPayload = String.format("{\"q\":%s,\"api_key\":%s}",
                escapeJson(text), escapeJson(apiKey));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(detectUrl))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Odysseia/1.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String body = response.body();
                        // Estructura: [{"confidence":90,"language":"en"}]
                        Pattern pattern = Pattern.compile("\"language\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher matcher = pattern.matcher(body);
                        if (matcher.find()) {
                            return matcher.group(1);
                        }
                    }
                    return "";
                }).exceptionally(ex -> {
                    log.fine("[Odysseia] Excepción detectando idioma: " + ex.getMessage());
                    return "";
                });
    }

    /**
     * Traducción asíncrona de texto a través del endpoint Star /translate
     */
    public CompletableFuture<String> translateText(String text, String sourceLang, String targetLang) {
        if (text == null || text.trim().isEmpty()) {
            return CompletableFuture.completedFuture("");
        }

        String translateUrl = baseUrl.endsWith("/translate") ? baseUrl : baseUrl + "/translate";
        String jsonPayload = String.format("{\"q\":%s,\"source\":%s,\"target\":%s,\"api_key\":%s}",
                escapeJson(text), escapeJson(sourceLang), escapeJson(targetLang), escapeJson(apiKey));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(translateUrl))
                .timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Odysseia/1.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String body = response.body();
                        // Estructura: {"translatedText":"..."}
                        Pattern pattern = Pattern.compile("\"translatedText\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher matcher = pattern.matcher(body);
                        if (matcher.find()) {
                            return unescapeJson(matcher.group(1));
                        }
                    }
                    return "";
                }).exceptionally(ex -> {
                    log.fine("[Odysseia] Excepción traduciendo texto: " + ex.getMessage());
                    return "";
                });
    }

    // ─── Eventos DiscordSRV ────────────────────────────────────────────────────

    /**
     * Evento: Mensaje recibido desde Discord hacia Minecraft
     */
    @Subscribe
    public void onDiscordMessageReceived(DiscordGuildMessageReceivedEvent event) {
        if (!enabled || !translateDiscordToMc) return;

        // Ignorar bots
        if (event.getAuthor() == null || event.getAuthor().isBot()) return;

        // Validar estrictamente que el mensaje provenga del canal principal de chat de DiscordSRV
        if (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel mainChat = DiscordSRV.getPlugin().getMainTextChannel();
            if (mainChat == null || event.getChannel() == null || !event.getChannel().getId().equals(mainChat.getId())) {
                return;
            }
        }

        String messageContent = event.getMessage().getContentDisplay();
        if (messageContent == null || messageContent.trim().isEmpty() || messageContent.startsWith("!")) return;

        String authorName = event.getAuthor().getName();

        // Detectar idioma y traducir
        detectLanguage(messageContent).thenAccept(detectedLang -> {
            if (detectedLang.isEmpty() || detectedLang.equalsIgnoreCase(mcTargetLanguage)) {
                return; // Ya está en español (o no se pudo detectar)
            }

            translateText(messageContent, detectedLang, mcTargetLanguage).thenAccept(translatedText -> {
                if (translatedText.isEmpty() || translatedText.equalsIgnoreCase(messageContent)) return;

                String formattedNotice = ChatColor.translateAlternateColorCodes('&',
                        "&8[&bDiscord&8] &7" + authorName + "&f: " + messageContent +
                                " &8(&eTraducido: &f" + translatedText + "&8)");

                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(formattedNotice);
                    }
                });
            });
        });
    }

    /**
     * Evento: Mensaje enviado desde Minecraft hacia Discord
     */
    @Subscribe
    public void onMinecraftMessageToDiscord(GameChatMessagePreProcessEvent event) {
        if (!enabled) return;

        String messageContent = event.getMessage();
        if (messageContent == null || messageContent.trim().isEmpty()) return;

        Player player = event.getPlayer();
        String playerName = player != null ? player.getName() : "Jugador";

        if (!translateMcToDiscord) return;

        try {
            // DiscordSRV publica el mensaje al finalizar este evento. Esperamos solo en
            // su hilo de puente, con timeout, para que la traduccion llegue a Discord.
            String detectedLang = detectLanguage(messageContent).get(3, TimeUnit.SECONDS);
            if (detectedLang.isEmpty() || detectedLang.equalsIgnoreCase(discordTargetLanguage)) {
                return;
            }

            String translatedText = translateText(messageContent, detectedLang, discordTargetLanguage)
                    .get(3, TimeUnit.SECONDS);
            if (!translatedText.isEmpty() && !translatedText.equalsIgnoreCase(messageContent)) {
                event.setMessage(messageContent + " *(EN: " + translatedText + ")*");
            }
        } catch (Exception error) {
            // Fallback limpio: DiscordSRV conserva el mensaje original si Star falla.
            log.fine("Traduccion Minecraft a Discord omitida: " + error.getClass().getSimpleName());
        }
    }


    private String escapeJson(String text) {
        if (text == null) return "\"\"";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String unescapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
