package org.metamechanists.odysseia.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.utils.WebhookSender;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Servicio de Impuestos Internos (SII): vigilante de la economia.
 *
 * <p>La semana del 2026-09-14 tres jugadores sacaron ~240 M de la tienda vendiendo sin tope
 * (INC-065) y nadie lo vio hasta que la inflacion rompio los precios. Este servicio mira el
 * patrimonio TOTAL de cada jugador conectado (monedero Vault + banco sBank, para que mover
 * plata entre ambos no cuente) cada pocos segundos y reacciona cuando crece mas de lo que
 * cualquier actividad legitima permite en una ventana corta.</p>
 *
 * <p>Que hace al detectar un salto:</p>
 * <ol>
 *   <li><b>Fiscaliza</b> al jugador durante {@code freeze-hours}: se le avisa en el chat y todo
 *       lo que gane por encima de su patrimonio en ese momento se <b>retiene</b> (sale del
 *       monedero/banco y queda anotado en {@code sii-retenciones.yml}). No se destruye: el staff
 *       lo libera o lo confisca con {@code /odysseia sii}.</li>
 *   <li>Retiene tambien el <b>exceso</b> del salto que disparo la alarma (lo que supera el
 *       umbral), asi el jugador se queda con lo que un jugador normal podria haber ganado.</li>
 *   <li>Avisa al staff por el webhook de moderacion y en consola, con las cifras.</li>
 * </ol>
 *
 * <p>Por que retener y no quitar: una venta grande en /ah o un premio de evento tambien son
 * saltos, y el jugador no tiene por que pagar por un falso positivo. El staff decide con la
 * evidencia delante; mientras tanto la plata no circula, que es lo que rompe la economia.</p>
 */
public final class EconomyWatchdog {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

    private record Sample(long at, double total) {}

    /** Estado de una cuenta fiscalizada. */
    public static final class Fiscalizacion {
        public long hasta;          // epoch ms
        public double base;         // patrimonio de referencia: por encima de esto se retiene
        public double retenido;     // acumulado retenido (pendiente de decision del staff)
        public String motivo;
        public long desde;
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Deque<Sample>> muestras = new HashMap<>();
    private final Map<UUID, Fiscalizacion> fiscalizados = new HashMap<>();
    private final File ficheroRetenciones;
    private Economy economy;
    private int taskId = -1;

    // sBank por reflexion (softdepend), con API global antigua y cuentas por modalidad.
    private Method sbankGetBanks;
    private Method sbankKey;
    private Method modalityOf;
    private Method sbankPersist;
    private Method bankGetBalance;
    private Method bankSetBalance;
    private Method sbankGetDb;
    private Method dbGetBank;

    public EconomyWatchdog(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ficheroRetenciones = new File(plugin.getDataFolder(), "sii-retenciones.yml");
    }

    // ------------------------------------------------------------------ config

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("economy-watchdog");
        return s != null ? s : plugin.getConfig().createSection("economy-watchdog");
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    private long ventanaMs() {
        return Math.max(60, cfg().getLong("window-seconds", 300)) * 1000L;
    }

    private double umbralVentana() {
        return cfg().getDouble("max-gain-per-window", 1_500_000);
    }

    private double umbralHora() {
        return cfg().getDouble("max-gain-per-hour", 4_000_000);
    }

    private long congelacionMs() {
        return (long) (Math.max(0.1, cfg().getDouble("freeze-hours", 5)) * 3600_000L);
    }

    private double tolerancia() {
        return cfg().getDouble("tolerance", 2_000);
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void start() {
        if (!enabled()) {
            plugin.getLogger().info("[SII] Vigilante de economia desactivado por config.");
            return;
        }
        RegisteredServiceProvider<Economy> reg = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = reg == null ? null : reg.getProvider();
        if (economy == null) {
            plugin.getLogger().warning("[SII] Sin proveedor Vault: vigilante de economia desactivado.");
            return;
        }
        hookSBank();
        cargarRetenciones();
        long periodo = Math.max(5, cfg().getLong("poll-seconds", 20)) * 20L;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, periodo, periodo).getTaskId();
        plugin.getLogger().info("[SII] Vigilante de economia activo: ventana " + (ventanaMs() / 1000) + "s, umbral "
                + MONEY.format(umbralVentana()) + " / " + MONEY.format(umbralHora()) + " por hora, fiscalizacion "
                + cfg().getDouble("freeze-hours", 5) + " h" + (sbankGetBanks != null ? ", sBank enlazado" : ", sin sBank"));
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        guardarRetenciones();
    }

    private void hookSBank() {
        if (Bukkit.getPluginManager().getPlugin("sBank") == null) {
            return;
        }
        try {
            Class<?> sbank = Class.forName("com.spearforge.sBank.SBank");
            Class<?> bank = Class.forName("com.spearforge.sBank.model.Bank");
            Class<?> modality;
            try {
                modality = Class.forName("com.spearforge.sBank.model.Modality");
            } catch (ClassNotFoundException legacy) {
                modality = null;
            }
            hookSBank(sbank, bank, modality);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[SII] No pude enlazar sBank por reflexion; solo se vigila el monedero.", e);
            sbankGetBanks = null;
        }
    }

    void hookSBank(Class<?> sbank, Class<?> bank, Class<?> modality) throws ReflectiveOperationException {
        // No publicar un enlace parcial si alguna firma requerida resulta incompatible.
        sbankGetBanks = null;
        sbankKey = null;
        modalityOf = null;
        sbankPersist = sbank.getMethod("persistBank", bank);
        sbankGetDb = sbank.getMethod("getDb");
        bankGetBalance = bank.getMethod("getBalance");
        bankSetBalance = bank.getMethod("setBalance", double.class);
        Class<?> database = sbankGetDb.invoke(null).getClass();
        try {
            dbGetBank = database.getMethod("getBank", String.class, String.class);
            if (modality == null) throw new ClassNotFoundException("sBank Modality");
            sbankKey = sbank.getMethod("key", Player.class);
            modalityOf = modality.getMethod("of", String.class);
        } catch (NoSuchMethodException incompatible) {
            // Solo la ausencia de la firma nueva permite usar la API global antigua.
            if (modality != null) throw incompatible;
            dbGetBank = database.getMethod("getBank", String.class);
        }
        sbankGetBanks = sbank.getMethod("getBanks");
    }

    // ------------------------------------------------------------------ lectura de patrimonio

    @SuppressWarnings("unchecked")
    private Object bancoDe(Player player) {
        if (sbankGetBanks == null) return null;
        try {
            Map<String, Object> banks = (Map<String, Object>) sbankGetBanks.invoke(null);
            String key = sbankKey == null ? player.getName() : (String) sbankKey.invoke(null, player);
            Object b = banks.get(key);
            if (b == null && dbGetBank != null) {
                Object database = sbankGetDb.invoke(null);
                b = sbankKey == null
                        ? dbGetBank.invoke(database, player.getName())
                        : dbGetBank.invoke(database, player.getName(),
                                modalityOf.invoke(null, player.getWorld().getName()));
            }
            return b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private double saldoBanco(Player player) {
        Object b = bancoDe(player);
        if (b == null) return 0;
        try {
            return (double) bankGetBalance.invoke(b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    public double patrimonio(Player player) {
        return economy.getBalance(player) + saldoBanco(player);
    }

    // ------------------------------------------------------------------ vigilancia

    private void tick() {
        long ahora = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("odysseia.sii.exempt")) continue;
            double total;
            try {
                total = patrimonio(p);
            } catch (RuntimeException e) {
                continue;
            }
            UUID id = p.getUniqueId();
            Fiscalizacion f = fiscalizados.get(id);
            if (f != null) {
                if (ahora >= f.hasta) {
                    fiscalizados.remove(id);
                    guardarRetenciones();
                    p.sendMessage(color("&6[SII] &aTu cuenta salio de fiscalizacion. "
                            + (f.retenido > 0 ? "Hay &e₯" + MONEY.format(f.retenido) + " &aretenidos a la espera del staff." : "")));
                } else {
                    double exceso = total - f.base - tolerancia();
                    if (exceso > 0) {
                        double ret = retener(p, exceso);
                        f.retenido += ret;
                        guardarRetenciones();
                        p.sendMessage(color("&6[SII] &cCuenta en fiscalizacion: &e₯" + MONEY.format(ret)
                                + " &cretenidos hasta revision del staff. Quedan &f" + horasRestantes(f) + " h&c."));
                    }
                    continue;
                }
            }

            Deque<Sample> cola = muestras.computeIfAbsent(id, k -> new ArrayDeque<>());
            cola.addLast(new Sample(ahora, total));
            while (!cola.isEmpty() && ahora - cola.peekFirst().at() > 3600_000L) {
                cola.pollFirst();
            }
            double gananciaVentana = total - minimoDesde(cola, ahora - ventanaMs());
            double gananciaHora = total - minimoDesde(cola, ahora - 3600_000L);
            if (gananciaVentana > umbralVentana()) {
                disparar(p, total, gananciaVentana, umbralVentana(), "+" + MONEY.format(gananciaVentana) + " en " + (ventanaMs() / 60000) + " min");
            } else if (gananciaHora > umbralHora()) {
                disparar(p, total, gananciaHora, umbralHora(), "+" + MONEY.format(gananciaHora) + " en 1 h");
            }
        }
    }

    private static double minimoDesde(Deque<Sample> cola, long desde) {
        double min = Double.MAX_VALUE;
        for (Sample s : cola) {
            if (s.at() >= desde && s.total() < min) min = s.total();
        }
        return min == Double.MAX_VALUE ? cola.peekLast().total() : min;
    }

    private void disparar(Player p, double total, double ganancia, double umbral, String detalle) {
        double exceso = ganancia - umbral;
        double retenido = exceso > 0 ? retener(p, exceso) : 0;
        Fiscalizacion f = new Fiscalizacion();
        f.desde = System.currentTimeMillis();
        f.hasta = f.desde + congelacionMs();
        f.base = patrimonio(p);
        f.retenido = retenido;
        f.motivo = detalle;
        fiscalizados.put(p.getUniqueId(), f);
        muestras.remove(p.getUniqueId());
        guardarRetenciones();

        p.sendMessage(color("&6&l[SII] &e¡Hey, mas despacio, gran economista!"));
        p.sendMessage(color("&7El Servicio de Impuestos Internos detecto &f" + detalle + "&7, muy por encima de lo normal."));
        p.sendMessage(color("&7Tu cuenta queda &cfiscalizada " + cfg().getDouble("freeze-hours", 5) + " h&7: lo que ganes en ese tiempo se retiene."
                + (retenido > 0 ? " Se retuvieron &e₯" + MONEY.format(retenido) + "&7 del salto." : "")));
        p.sendMessage(color("&7Si fue legitimo (venta en /ah, premio), el staff te lo devuelve. Abre ticket en Discord."));
        String log = "[SII] " + p.getName() + " " + detalle + " (umbral " + MONEY.format(umbral) + "); patrimonio "
                + MONEY.format(total) + "; retenido " + MONEY.format(retenido) + "; fiscalizado " + cfg().getDouble("freeze-hours", 5) + " h";
        plugin.getLogger().warning(log);
        Bukkit.getOnlinePlayers().stream().filter(s -> s.hasPermission("odysseia.sii.admin")).forEach(s -> s.sendMessage(color("&6[SII] &7" + log.substring(6))));
        webhook("Fiscalizacion automatica", p.getName(), detalle, total, retenido);
    }

    /** Saca `monto` del monedero y, si no alcanza, del banco. Devuelve lo que realmente se retuvo. */
    private double retener(Player p, double monto) {
        double restante = monto;
        double cartera = economy.getBalance(p);
        double delMonedero = Math.min(cartera, restante);
        if (delMonedero > 0 && economy.withdrawPlayer(p, delMonedero).transactionSuccess()) {
            restante -= delMonedero;
        }
        if (restante > 0.01 && sbankGetBanks != null) {
            Object b = bancoDe(p);
            if (b != null) {
                try {
                    double saldo = (double) bankGetBalance.invoke(b);
                    double delBanco = Math.min(saldo, restante);
                    if (delBanco > 0) {
                        bankSetBalance.invoke(b, saldo - delBanco);
                        sbankPersist.invoke(null, b);
                        restante -= delBanco;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "[SII] No pude retener del banco de " + p.getName(), e);
                }
            }
        }
        return monto - restante;
    }

    // ------------------------------------------------------------------ staff

    public Map<UUID, Fiscalizacion> fiscalizados() {
        return fiscalizados;
    }

    /** Devuelve lo retenido al jugador (monedero; si no esta conectado, se paga por Vault offline). */
    public double liberar(OfflinePlayer target) {
        Fiscalizacion f = fiscalizados.remove(target.getUniqueId());
        if (f == null || f.retenido <= 0) {
            if (f == null) return -1;
            guardarRetenciones();
            return 0;
        }
        economy.depositPlayer(target, f.retenido);
        guardarRetenciones();
        plugin.getLogger().info("[SII] Liberados " + MONEY.format(f.retenido) + " a " + target.getName());
        return f.retenido;
    }

    /** Confisca definitivamente lo retenido (queda solo en el registro). */
    public double confiscar(OfflinePlayer target) {
        Fiscalizacion f = fiscalizados.remove(target.getUniqueId());
        if (f == null) return -1;
        guardarRetenciones();
        registrarConfiscacion(target, f);
        plugin.getLogger().info("[SII] Confiscados " + MONEY.format(f.retenido) + " a " + target.getName());
        return f.retenido;
    }

    public void congelar(Player target, double horas, String motivo) {
        Fiscalizacion f = fiscalizados.computeIfAbsent(target.getUniqueId(), k -> new Fiscalizacion());
        f.desde = System.currentTimeMillis();
        f.hasta = f.desde + (long) (horas * 3600_000L);
        f.base = patrimonio(target);
        f.motivo = motivo;
        guardarRetenciones();
        target.sendMessage(color("&6[SII] &cTu cuenta queda fiscalizada " + horas + " h por el staff: " + motivo));
    }

    // ------------------------------------------------------------------ persistencia / avisos

    private synchronized void guardarRetenciones() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Fiscalizacion> e : fiscalizados.entrySet()) {
            String k = e.getKey().toString();
            Fiscalizacion f = e.getValue();
            y.set(k + ".hasta", f.hasta);
            y.set(k + ".base", f.base);
            y.set(k + ".retenido", f.retenido);
            y.set(k + ".motivo", f.motivo);
            y.set(k + ".desde", f.desde);
        }
        try {
            y.save(ficheroRetenciones);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[SII] No pude guardar sii-retenciones.yml", e);
        }
    }

    private void cargarRetenciones() {
        if (!ficheroRetenciones.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(ficheroRetenciones);
        for (String k : y.getKeys(false)) {
            try {
                Fiscalizacion f = new Fiscalizacion();
                f.hasta = y.getLong(k + ".hasta");
                f.base = y.getDouble(k + ".base");
                f.retenido = y.getDouble(k + ".retenido");
                f.motivo = y.getString(k + ".motivo", "");
                f.desde = y.getLong(k + ".desde");
                fiscalizados.put(UUID.fromString(k), f);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void registrarConfiscacion(OfflinePlayer target, Fiscalizacion f) {
        File log = new File(plugin.getDataFolder(), "sii-confiscaciones.log");
        try (java.io.FileWriter w = new java.io.FileWriter(log, true)) {
            w.write(System.currentTimeMillis() + "\t" + target.getUniqueId() + "\t" + target.getName() + "\t"
                    + MONEY.format(f.retenido) + "\t" + f.motivo + "\n");
        } catch (IOException ignored) {
        }
    }

    private void webhook(String titulo, String jugador, String detalle, double total, double retenido) {
        String url = plugin.getConfig().getString("discord.webhook-moderation-url", "");
        if (url == null || url.isBlank() || !WebhookSender.isDiscordWebhookUrl(url) || !WebhookSender.isAllowedHttpsUrl(url)) {
            return;
        }
        String json = String.format(Locale.ROOT,
                "{\"username\":\"SII DrakesCraft\",\"embeds\":[{\"title\":\"%s\",\"color\":15844367,\"fields\":["
                        + "{\"name\":\"Jugador\",\"value\":\"`%s`\",\"inline\":true},"
                        + "{\"name\":\"Salto\",\"value\":\"%s\",\"inline\":true},"
                        + "{\"name\":\"Patrimonio\",\"value\":\"₯%s\",\"inline\":true},"
                        + "{\"name\":\"Retenido\",\"value\":\"₯%s\",\"inline\":true}],"
                        + "\"footer\":{\"text\":\"/odysseia sii liberar|confiscar %s\"}}]}",
                Odysseia.escapeJson(titulo), Odysseia.escapeJson(jugador), Odysseia.escapeJsonCampo(detalle),
                MONEY.format(total), MONEY.format(retenido), Odysseia.escapeJson(jugador));
        WebhookSender.sendAsync(plugin, url, json);
    }

    private static String horasRestantes(Fiscalizacion f) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, f.hasta - System.currentTimeMillis()) / 3600_000.0);
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String resumen() {
        if (fiscalizados.isEmpty()) return "Sin cuentas fiscalizadas.";
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Fiscalizacion> e : fiscalizados.entrySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            Fiscalizacion f = e.getValue();
            out.add((op.getName() != null ? op.getName() : e.getKey().toString()) + ": retenido ₯" + MONEY.format(f.retenido)
                    + ", " + horasRestantes(f) + " h, " + f.motivo);
        }
        return String.join("\n", out);
    }
}
