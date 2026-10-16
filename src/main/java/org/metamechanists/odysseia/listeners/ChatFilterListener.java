package org.metamechanists.odysseia.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.utils.WebhookSender;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Filtro persistente de chat, guardián anti-spam y centinela de tolerancia cero para discurso de odio.
 */
public final class ChatFilterListener implements Listener, CommandExecutor {

    private static final List<String> DEFAULT_HATE_SPEECH_WORDS = List.of(
            "nigger", "niggers", "nigga", "niggas", "n1gger", "n1gg3r", "negrata",
            "faggot", "faggots", "kys", "kill yourself", "heil hitler", "sieg heil",
            "negro de mierda", "muerte a los negros"
    );

    private record ChatRecord(long timestamp, String text) {}
    private static final Map<UUID, Deque<ChatRecord>> RECENT_CHAT = new ConcurrentHashMap<>();

    private final Odysseia plugin;
    private final File file;
    private final YamlConfiguration data;

    public ChatFilterListener(Odysseia plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "chat-warnings.yml");
        data = YamlConfiguration.loadConfiguration(file);
        importLegacyWarnings();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfig().getBoolean("chat-filter.enabled", true)
                || player.hasPermission("drakes.chatfilter.bypass")) {
            return;
        }

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        String normalizedMessage = normalize(rawMessage);

        // 1. Tolerancia Cero: Discurso de Odio / Slurs Racistas (Auto-Ban + Auto-Ban-IP Inmediato)
        String hateWord = findHateSpeech(normalizedMessage);
        if (hateWord != null) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> banHateSpeech(player, hateWord, rawMessage));
            return;
        }

        // 2. Anti-Spam / Rate-Limiting / Repetición masiva
        if (checkSpam(player, normalizedMessage)) {
            event.setCancelled(true);
            return;
        }

        // 3. Filtro de Lenguaje Inapropiado Regular (Sistema de Warns escalonados)
        String found = plugin.getConfig().getStringList("chat-filter.words").stream()
                .filter(word -> matches(normalizedMessage, word))
                .findFirst()
                .orElse(null);
        if (found == null) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> warn(player, found));
    }

    private String findHateSpeech(String normalizedMessage) {
        List<String> hateList = plugin.getConfig().getStringList("chat-filter.hate-speech-words");
        if (hateList.isEmpty()) {
            hateList = DEFAULT_HATE_SPEECH_WORDS;
        }
        for (String word : hateList) {
            if (matches(normalizedMessage, word)) {
                return word;
            }
        }
        return null;
    }

    private void banHateSpeech(Player player, String word, String raw) {
        String name = player.getName();
        String ip = (player.getAddress() != null && player.getAddress().getAddress() != null)
                ? player.getAddress().getAddress().getHostAddress() : null;

        Location location = player.getLocation();
        if (location.getWorld() != null) {
            location.getWorld().strikeLightningEffect(location);
            location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        }

        Bukkit.broadcastMessage(color("&c&lDrakesCraft &8· &f" + name + " &cfue &4&lBANEADO &cpermanentemente por discurso de odio."));

        String reason = "Discurso de odio / Hate speech detectado por Centinela";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason);
        if (ip != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban-ip " + ip + " " + reason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "banip " + name + " " + reason);
        }
        player.kick(Component.text("[DrakesCraft] Baneado permanentemente por discurso de odio.", NamedTextColor.RED));

        // Enviar alerta a webhook de moderación
        String webhookUrl = plugin.getConfig().getString("discord.webhook-moderation-url", "");
        if (!webhookUrl.isBlank()) {
            String jsonPayload = String.format(
                    "{\"username\":\"Odysseia Centinela\",\"embeds\":[{"
                            + "\"title\":\"🚨 BAN AUTOMÁTICO: Discurso de Odio\",\"description\":\"Se aplicó baneo permanente de cuenta e IP de forma inmediata.\","
                            + "\"color\":15158332,\"fields\":["
                            + "{\"name\":\"Jugador\",\"value\":\"`%s`\",\"inline\":true},"
                            + "{\"name\":\"IP\",\"value\":\"`%s`\",\"inline\":true},"
                            + "{\"name\":\"Término Bloqueado\",\"value\":\"`%s`\",\"inline\":true},"
                            + "{\"name\":\"Acción\",\"value\":\"`BAN PERMANENTE + BAN-IP`\",\"inline\":false}],"
                            + "\"footer\":{\"text\":\"Odysseia Centinela · DrakesCraft\"}}]}",
                    Odysseia.escapeJson(name),
                    Odysseia.escapeJson(ip != null ? ip : "Desconocida"),
                    Odysseia.escapeJson(word));
            WebhookSender.sendAsync(plugin, webhookUrl, jsonPayload);
        }
    }

    private boolean checkSpam(Player player, String message) {
        if (player.hasPermission("drakes.antispam.bypass")) return false;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Deque<ChatRecord> history = RECENT_CHAT.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst().timestamp() > 10_000L) {
                history.pollFirst();
            }

            // 1. Verificación de repetición consecutiva en menos de 4s
            if (!history.isEmpty()) {
                ChatRecord last = history.peekLast();
                if (now - last.timestamp() < 4000L && last.text().equalsIgnoreCase(message)) {
                    player.sendMessage(color("&c[!] No repitas el mismo mensaje tan rápido."));
                    history.addLast(new ChatRecord(now, message));
                    long repeats = history.stream().filter(r -> r.text().equalsIgnoreCase(message)).count();
                    if (repeats >= 3) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mute " + player.getName() + " 5m Repetición excesiva en chat");
                        });
                    }
                    return true;
                }
            }

            // 2. Verificación de flood / velocidad (más de 3 mensajes en 3 segundos)
            long recent3s = history.stream().filter(r -> now - r.timestamp() < 3000L).count();
            if (recent3s >= 3) {
                player.sendMessage(color("&c[!] Estás escribiendo demasiado rápido. Por favor espera unos segundos."));
                history.addLast(new ChatRecord(now, message));
                if (recent3s >= 5) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mute " + player.getName() + " 10m Flood masivo de chat");
                    });
                }
                return true;
            }

            history.addLast(new ChatRecord(now, message));
            return false;
        }
    }

    /**
     * Coincidencia por palabra completa, no por subcadena.
     *
     * Con {@code contains} cualquier termino corto de la lista censura palabras legitimas que lo
     * contengan. Las entradas con espacios ("hijo de puta") siguen funcionando porque el limite
     * solo se exige en los extremos.
     */
    static boolean matches(String normalizedMessage, String word) {
        String needle = normalize(word);
        if (needle.isBlank()) return false;
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])");
        return pattern.matcher(normalizedMessage).find();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int limit = plugin.getConfig().getInt("chat-filter.warns.ban", 7);
        if (args.length < 2 || !(args[0].equalsIgnoreCase("ver") || args[0].equalsIgnoreCase("limpiar"))) {
            sender.sendMessage(color("&eUso: &f/dwarn <ver|limpiar> <jugador>"));
            return true;
        }
        String path = "warns." + args[1].toLowerCase(Locale.ROOT);
        if (args[0].equalsIgnoreCase("limpiar")) {
            data.set(path, 0);
            save();
            sender.sendMessage(color("&aWarns limpiados."));
        } else {
            sender.sendMessage(color("&eWarns de &f" + args[1] + "&e: &c" + data.getInt(path, 0) + "/" + limit));
        }
        return true;
    }

    private void warn(Player player, String word) {
        String path = "warns." + player.getName().toLowerCase(Locale.ROOT);
        int total = data.getInt(path, 0) + 1;
        data.set(path, total);
        save();

        var config = plugin.getConfig();
        int muteAt = config.getInt("chat-filter.warns.mute", 3);
        int kickAt = config.getInt("chat-filter.warns.kick", 5);
        int banAt = config.getInt("chat-filter.warns.ban", 7);
        String muteTime = config.getString("chat-filter.duracion.mute", "10m");
        String banTime = config.getString("chat-filter.duracion.ban", "1d");

        player.sendMessage(color("&c[!] &eEse mensaje no se envió: lenguaje inapropiado. "
                + "Warn &c" + total + "&e/" + banAt + "."));

        // El staff sí ve la palabra; en el chat global no se repite, para no difundirla.
        Bukkit.getOnlinePlayers().stream()
                .filter(staff -> staff.hasPermission("drakes.staff"))
                .forEach(staff -> staff.sendMessage(color("&8[&cChatFilter&8] &7" + player.getName()
                        + " usó '&c" + word + "&7'. &c" + total + "/" + banAt)));

        if (total >= banAt) {
            announce(player, "&cbaneado por " + banTime, total, banAt);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "tempban " + player.getName() + " " + banTime + " Lenguaje inapropiado reiterado");
            data.set(path, 0);
            save();
        } else if (total >= kickAt) {
            announce(player, "&6expulsado", total, banAt);
            player.kickPlayer("[DrakesCraft] Modera tu vocabulario (" + total + "/" + banAt + " warns).");
        } else if (total >= muteAt) {
            announce(player, "&esilenciado por " + muteTime, total, banAt);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "mute " + player.getName() + " " + muteTime + " Lenguaje inapropiado reiterado");
        }
    }

    /**
     * Explica la sancion en el chat global.
     *
     * Sin esto el jugador aparece mudo de golpe y el resto no sabe por que; es la queja que
     * llego como "me mutearon porque dije X".
     */
    private void announce(Player player, String sanction, int total, int limit) {
        if (!plugin.getConfig().getBoolean("chat-filter.anuncio-global", true)) return;
        String message = color("&6DrakesCraft &8· &f" + player.getName() + " &7fue " + sanction
                + " &7por lenguaje inapropiado. &8(warn " + total + "/" + limit + ")");
        Bukkit.broadcastMessage(message);
    }

    private void importLegacyWarnings() {
        if (file.exists()) return;
        File legacy = new File(plugin.getDataFolder().getParentFile(), "Denizen/chat_warns.yml");
        if (!legacy.isFile()) return;
        YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
        if (old.isConfigurationSection("warns")) data.set("warns", old.getConfigurationSection("warns"));
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "[ERROR] No se pudo guardar chat-warnings.yml", exception);
        }
    }

    /**
     * Quita acentos, pasa a minusculas y deshace el leet mas comun.
     * Sin esto "m1erd4" pasaba el filtro sin tocarlo.
     */
    static String normalize(String value) {
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(plain.length());
        for (char current : plain.toCharArray()) {
            builder.append(switch (current) {
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '0' -> 'o';
                case '1' -> 'i';
                case '$', '5' -> 's';
                case '7' -> 't';
                default -> current;
            });
        }
        /*
         * "mierdaaa" y "mieeerda" son la misma palabra, asi que se colapsan los alargamientos.
         *
         * Pero solo a partir de TRES repeticiones. Colapsar tambien las dobles destrozaba el
         * filtro: la lista pasa por aqui igual, asi que "coon" quedaba en "con" --y "con" es la
         * preposicion mas comun del castellano--. El resultado era que cualquiera que escribiera
         * "con" se comia un warn. ZurielWiz junto dos en un minuto deseandole suerte a Rojo.
         *
         * Con el limite en tres, las dobles legitimas se respetan ("con" != "coon", "zora" !=
         * "zorra") y los alargamientos de verdad se siguen pillando, porque quien alarga una
         * palabra para colarla no escribe dos letras, escribe seis.
         */
        return builder.toString().replaceAll("(.)\\1{2,}", "$1");
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
