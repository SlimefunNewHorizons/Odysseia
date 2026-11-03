package org.metamechanists.odysseia.events.rush;

import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.metamechanists.odysseia.events.calendar.EventOptOutStore;
import org.metamechanists.odysseia.events.calendar.EventTelemetryLogger;
import org.metamechanists.odysseia.events.model.ActiveEvent;
import org.metamechanists.odysseia.events.model.EventType;
import org.metamechanists.odysseia.utils.WebhookSender;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RushEvent implements ActiveEvent {

    public static final long MIN_SCORE_FOR_REWARD = 10L;
    private static final int MAX_TRACKED_PLACED_BLOCKS = 8192;

    public enum RushType {
        MINING(EventType.RUSH_MINING, "Minería", BarColor.YELLOW, "⛏️"),
        FISHING(EventType.RUSH_FISHING, "Pesca", BarColor.BLUE, "🎣"),
        HARVEST(EventType.RUSH_HARVEST, "Papa-Maratón / Cosecha", BarColor.GREEN, "🥔");

        @Getter private final EventType eventType;
        @Getter private final String displayName;
        @Getter private final BarColor barColor;
        @Getter private final String icon;

        RushType(EventType eventType, String displayName, BarColor barColor, String icon) {
            this.eventType = eventType;
            this.displayName = displayName;
            this.barColor = barColor;
            this.icon = icon;
        }
    }

    private final JavaPlugin plugin;
    @Getter private final RushType rushType;
    @Getter private final long durationSeconds;
    private long remainingSeconds;
    private boolean active = false;

    private final Map<UUID, Long> scores = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Set<String> placedBlockKeys = ConcurrentHashMap.newKeySet();
    private BossBar bossBar;
    private final String discordWebhookUrl;
    private final EventOptOutStore optOutStore;
    private final EventTelemetryLogger telemetry;

    public RushEvent(JavaPlugin plugin, RushType rushType, long durationSeconds, String discordWebhookUrl) {
        this(plugin, rushType, durationSeconds, discordWebhookUrl, null, null);
    }

    public RushEvent(
            JavaPlugin plugin,
            RushType rushType,
            long durationSeconds,
            String discordWebhookUrl,
            EventOptOutStore optOutStore,
            EventTelemetryLogger telemetry
    ) {
        this.plugin = plugin;
        this.rushType = rushType;
        this.durationSeconds = durationSeconds;
        this.remainingSeconds = durationSeconds;
        this.discordWebhookUrl = discordWebhookUrl;
        this.optOutStore = optOutStore;
        this.telemetry = telemetry;
    }

    public void markPlacedBlock(Block block) {
        if (!active || block == null || placedBlockKeys.size() >= MAX_TRACKED_PLACED_BLOCKS) return;
        placedBlockKeys.add(blockKey(block));
    }

    public boolean isPlacedBlock(Block block) {
        return block != null && placedBlockKeys.contains(blockKey(block));
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @Override
    public EventType getEventType() {
        return rushType.getEventType();
    }

    @Override
    public void start() {
        this.active = true;
        this.bossBar = Bukkit.createBossBar(
                getBossBarTitle(),
                rushType.getBarColor(),
                BarStyle.SEGMENTED_10
        );
        this.bossBar.setProgress(1.0);

        String startLine = ChatColor.translateAlternateColorCodes('&',
                "&8[&6" + rushType.getIcon() + " Evento&8] &e¡Inicia &6" + rushType.getDisplayName()
                + "&e! &7(" + formatTime(durationSeconds) + " · Podio Top 3: 10k/6k/3k ₯ · &f/evento silenciar&7)");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (optOutStore != null && optOutStore.isOptedOut(player.getUniqueId())) continue;
            this.bossBar.addPlayer(player);
            player.sendMessage(startLine);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }

        // Discord announcement
        sendDiscordEmbed("🚀 ¡Ha comenzado el Evento Flash: " + rushType.getDisplayName() + "!",
                "**Duración:** `" + formatTime(durationSeconds) + "`\n" +
                "¡Entra al servidor y compite en vivo por recompensas exclusivas, dracmas y llaves!",
                0xF1C40F);
    }

    public void addScore(Player player, long points) {
        if (!active || points <= 0) return;
        UUID uuid = player.getUniqueId();
        scores.merge(uuid, points, Long::sum);
        playerNames.put(uuid, player.getName());
    }

    public long getScore(UUID uuid) {
        return scores.getOrDefault(uuid, 0L);
    }

    @Override
    public void tick() {
        if (!active) return;
        remainingSeconds--;

        // Keep boss bar players synced (respetando opt-out)
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean optedOut = optOutStore != null && optOutStore.isOptedOut(p.getUniqueId());
            if (optedOut) {
                if (bossBar.getPlayers().contains(p)) {
                    bossBar.removePlayer(p);
                }
            } else if (!bossBar.getPlayers().contains(p)) {
                bossBar.addPlayer(p);
            }
        }

        double progress = Math.max(0.0, Math.min(1.0, (double) remainingSeconds / (double) durationSeconds));
        bossBar.setProgress(progress);
        bossBar.setTitle(getBossBarTitle());

        if (remainingSeconds <= 0) {
            finish();
        }
    }

    private String getBossBarTitle() {
        List<Map.Entry<UUID, Long>> top = getTopScores(3);
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GOLD).append(rushType.getIcon()).append(" ")
          .append(ChatColor.YELLOW).append(rushType.getDisplayName()).append(" ")
          .append(ChatColor.GRAY).append("[").append(ChatColor.WHITE).append(formatTime(remainingSeconds)).append(ChatColor.GRAY).append("]");

        if (!top.isEmpty()) {
            sb.append(ChatColor.DARK_GRAY).append(" · ");
            for (int i = 0; i < top.size(); i++) {
                Map.Entry<UUID, Long> entry = top.get(i);
                String name = playerNames.getOrDefault(entry.getKey(), "Jugador");
                ChatColor color = (i == 0) ? ChatColor.GREEN : ((i == 1) ? ChatColor.AQUA : ChatColor.WHITE);
                sb.append(color).append("#").append(i + 1).append(" ").append(name)
                  .append(" (").append(entry.getValue()).append(") ");
            }
        }
        return sb.toString().trim();
    }

    public List<Map.Entry<UUID, Long>> getTopScores(int limit) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private void finish() {
        this.active = false;
        if (bossBar != null) {
            bossBar.removeAll();
        }

        placedBlockKeys.clear();
        List<Map.Entry<UUID, Long>> top = getTopScores(3);
        Economy economy = getEconomy();
        StringBuilder podiumSummary = new StringBuilder();

        if (top.isEmpty()) {
            podiumSummary.append("&7Sin puntuaciones registradas.");
        } else {
            for (int i = 0; i < top.size(); i++) {
                Map.Entry<UUID, Long> entry = top.get(i);
                String name = playerNames.getOrDefault(entry.getKey(), "Jugador");
                long score = entry.getValue();
                double moneyReward = (score >= MIN_SCORE_FOR_REWARD)
                        ? ((i == 0) ? 10000.0 : ((i == 1) ? 6000.0 : 3000.0))
                        : 0.0;

                if (i > 0) podiumSummary.append(" &8· ");
                podiumSummary.append("&e#").append(i + 1).append(" &f").append(name)
                        .append(" &7(").append(score).append(" pts")
                        .append(moneyReward > 0 ? ", &a+" + (int) moneyReward + " ₯&7)" : "&7)");

                Player p = Bukkit.getPlayer(entry.getKey());
                if (moneyReward > 0 && economy != null && p != null && p.isOnline()
                        && !p.getWorld().getName().toLowerCase(Locale.ROOT).contains("lab")) {
                    economy.depositPlayer(p, moneyReward);
                    if (optOutStore == null || !optOutStore.isOptedOut(p.getUniqueId())) {
                        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.0f);
                    }
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&a&l[PREMIO] &7Has recibido &a" + (int) moneyReward + " ₯ &7por tu podio en " + rushType.getDisplayName() + "!"));
                }
            }
        }

        String closeLine = ChatColor.translateAlternateColorCodes('&',
                "&8[&6" + rushType.getIcon() + " Cierre&8] &eFin de &6" + rushType.getDisplayName() + " &8» " + podiumSummary);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (optOutStore != null && optOutStore.isOptedOut(p.getUniqueId())) continue;
            p.sendMessage(closeLine);
        }

        if (telemetry != null) {
            telemetry.record(0, "rush-" + rushType.name().toLowerCase(Locale.ROOT),
                    getEventType().name(), "FINISHED", scores.size(), ChatColor.stripColor(closeLine));
        }

        // Discord results
        StringBuilder discordDesc = new StringBuilder();
        if (top.isEmpty()) {
            discordDesc.append("No hubo participantes registrados en esta edición.");
        } else {
            for (int i = 0; i < top.size(); i++) {
                Map.Entry<UUID, Long> entry = top.get(i);
                String name = playerNames.getOrDefault(entry.getKey(), "Jugador");
                String medal = (i == 0) ? "🥇" : ((i == 1) ? "🥈" : "🥉");
                discordDesc.append(medal).append(" **#").append(i + 1).append(" ").append(name).append("** — `")
                           .append(entry.getValue()).append(" pts`\n");
            }
        }
        sendDiscordEmbed("🏆 Resultados: " + rushType.getDisplayName(), discordDesc.toString(), 0x2ECC71);
    }

    public void stop() {
        stop("Comando de finalización");
    }

    @Override
    public void stop(String reason) {
        this.active = false;
        if (bossBar != null) {
            bossBar.removeAll();
        }
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                "&c&l[EVENTO] &7El evento " + rushType.getDisplayName() + " fue cancelado: &f" + reason));
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public long getTimeRemainingSeconds() {
        return Math.max(0, remainingSeconds);
    }

    @Override
    public String getStatusSummary() {
        return rushType.getDisplayName() + " (" + formatTime(remainingSeconds) + " restantes, " + scores.size() + " participantes)";
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    private Economy getEconomy() {
        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            return (rsp != null) ? rsp.getProvider() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sendDiscordEmbed(String title, String description, int color) {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) return;
        String json = "{\n" +
                "  \"embeds\": [{\n" +
                "    \"title\": \"" + escapeJson(title) + "\",\n" +
                "    \"description\": \"" + escapeJson(description) + "\",\n" +
                "    \"color\": " + color + ",\n" +
                "    \"footer\": { \"text\": \"DrakesCraft Network · Eventos Oficiales\" }\n" +
                "  }]\n" +
                "}";
        WebhookSender.sendAsync(plugin, discordWebhookUrl, json);
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "");
    }
}
