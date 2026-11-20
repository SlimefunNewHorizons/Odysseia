package org.metamechanists.odysseia.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Libro mayor de la economia (extension del SII): una linea JSON por cada cambio de saldo del monedero.
 *
 * <p>Escucha {@code UserBalanceUpdateEvent} de Essentials (por reflexion, sin dependencia dura): lo
 * lanza {@code User.setMoney}, asi que cubre /pay, /sell, /eco y cualquier plugin que cobre por Vault.
 * Como el evento corre en el hilo de quien cambia el saldo, se camina la pila para ver QUE plugin lo
 * hizo (QuickShop, CrazyAuctions, DrakesSlimeMarket, Slimefun...) sin tocar a ninguno.</p>
 *
 * <p>Los movimientos del banco/casino ya los deja sBank en {@code plugins/sBank/audit}; el informe
 * los une aqui. Un archivo por dia en {@code plugins/Odysseia/economia/}. La escritura es asincrona.</p>
 */
public final class EconomyLedger {
    private static final Set<String> NEUTROS = Set.of("Essentials", "EssentialsX", "Vault", "Odysseia");
    private static final String EVENTO = "net.ess3.api.events.UserBalanceUpdateEvent";

    private final JavaPlugin plugin;
    private final File dir;
    private final ConcurrentLinkedQueue<String> cola = new ConcurrentLinkedQueue<>();
    private Method getPlayer, getOld, getNew, getCause;
    private int taskId = -1;

    public EconomyLedger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "economia");
    }

    @SuppressWarnings("unchecked")
    public void start() {
        if (!plugin.getConfig().getBoolean("economy-ledger.enabled", true)) {
            return;
        }
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (ess == null) {
            plugin.getLogger().warning("[SII] Libro mayor: Essentials no esta; no se registran movimientos del monedero.");
            return;
        }
        try {
            Class<?> ev = ess.getClass().getClassLoader().loadClass(EVENTO);
            getPlayer = ev.getMethod("getPlayer");
            getOld = ev.getMethod("getOldBalance");
            getNew = ev.getMethod("getNewBalance");
            getCause = ev.getMethod("getCause");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) ev, new org.bukkit.event.Listener() { },
                    EventPriority.MONITOR, (l, e) -> registrar(e), plugin, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[SII] Libro mayor desactivado: no pude enlazar el evento de Essentials.", e);
            return;
        }
        dir.mkdirs();
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::volcar, 100L, 100L).getTaskId();
        plugin.getLogger().info("[SII] Libro mayor activo: " + dir.getPath());
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        volcar();
    }

    private void registrar(Event e) {
        try {
            Player p = (Player) getPlayer.invoke(e);
            if (p == null) {
                return;
            }
            double antes = ((BigDecimal) getOld.invoke(e)).doubleValue();
            double despues = ((BigDecimal) getNew.invoke(e)).doubleValue();
            double delta = despues - antes;
            if (Math.abs(delta) < 0.005) {
                return;
            }
            String causa = String.valueOf(getCause.invoke(e));
            String origen = origen(causa);
            cola.add("{\"ts\":\"" + ZonedDateTime.now(ZoneId.systemDefault()).toLocalDateTime().withNano(0) + "\",\"uuid\":\"" + p.getUniqueId()
                    + "\",\"nombre\":\"" + esc(p.getName()) + "\",\"delta\":" + redondear(delta) + ",\"antes\":" + redondear(antes)
                    + ",\"despues\":" + redondear(despues) + ",\"origen\":\"" + esc(origen) + "\",\"causa\":\"" + causa
                    + "\",\"mundo\":\"" + esc(p.getWorld().getName()) + "\"}\n");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.FINE, "[SII] Libro mayor: evento no registrado", ex);
        }
    }

    /** Primer plugin ajeno a Essentials/Vault en la pila; si no hay, la causa (COMMAND_PAY, COMMAND_SELL...). */
    private String origen(String causa) {
        String found = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .limit(80)
                .map(f -> nombrePlugin(f.getDeclaringClass()))
                .filter(n -> n != null && !NEUTROS.contains(n))
                .findFirst().orElse(null));
        return found != null ? found : "Essentials/" + causa;
    }

    private static String nombrePlugin(Class<?> c) {
        try {
            return JavaPlugin.getProvidingPlugin(c).getName();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return null;
        }
    }

    private static String redondear(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private synchronized void volcar() {
        if (cola.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = cola.poll()) != null) {
            sb.append(l);
        }
        try {
            dir.mkdirs();
            Files.writeString(new File(dir, "ledger-" + LocalDate.now() + ".jsonl").toPath(), sb, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[SII] Libro mayor: no pude escribir (se pierde este lote)", e);
        }
    }

    /**
     * Informe de los ultimos {@code dias} (1 = hoy): gasto e ingreso por origen y top de gastadores.
     * Lee tambien la auditoria de sBank (casino). Pensado para correr fuera del hilo principal.
     */
    public List<String> informe(int dias, String jugador) {
        Map<String, double[]> porOrigen = new TreeMap<>();   // [gasto, ingreso, movimientos]
        Map<String, Double> porJugador = new java.util.HashMap<>();
        double[] casino = new double[2];
        for (int i = 0; i < Math.max(1, dias); i++) {
            String dia = LocalDate.now().minusDays(i).toString();
            for (String l : leer(new File(dir, "ledger-" + dia + ".jsonl"))) {
                String nombre = campo(l, "nombre");
                if (jugador != null && !jugador.equalsIgnoreCase(nombre)) {
                    continue;
                }
                double d = Double.parseDouble(campo(l, "delta"));
                double[] a = porOrigen.computeIfAbsent(campo(l, "origen"), k -> new double[3]);
                a[d < 0 ? 0 : 1] += Math.abs(d);
                a[2]++;
                if (d < 0) {
                    porJugador.merge(nombre, -d, Double::sum);
                }
            }
            File sb = new File(plugin.getDataFolder().getParentFile(), "sBank/audit/transactions-" + dia + ".jsonl");
            for (String l : leer(sb)) {
                String nombre = campo(l, "username");
                if (jugador != null && !jugador.equalsIgnoreCase(nombre)) {
                    continue;
                }
                String mov = campo(l, "movement");
                double monto = Double.parseDouble(campo(l, "amount"));
                if (mov.equals("CASINO_BET")) {
                    casino[0] += monto;
                    porJugador.merge(nombre, monto, Double::sum);
                } else if (mov.equals("CASINO_PAYOUT")) {
                    casino[1] += monto;
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("&6Economia, ultimos " + Math.max(1, dias) + " dia(s)" + (jugador == null ? "" : " de " + jugador) + " &7(gasto / ingreso por origen)");
        porOrigen.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0])).limit(10)
                .forEach(e -> out.add(String.format(Locale.ROOT, "&e%s &7gasto &c%,.0f &7ingreso &a%,.0f &7(%d mov.)",
                        e.getKey(), e.getValue()[0], e.getValue()[1], (long) e.getValue()[2])));
        if (casino[0] + casino[1] > 0) {
            out.add(String.format(Locale.ROOT, "&eCasino (sBank) &7apostado &c%,.0f &7pagado &a%,.0f &7neto jugadores &f%,.0f",
                    casino[0], casino[1], casino[1] - casino[0]));
        }
        if (jugador == null) {
            out.add("&6Top gastadores:");
            porJugador.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue(), x.getValue())).limit(8)
                    .forEach(e -> out.add(String.format(Locale.ROOT, "&f%s &7%,.0f", e.getKey(), e.getValue())));
        }
        if (out.size() == 1) {
            out.add("&7Sin movimientos registrados en ese periodo.");
        }
        return out;
    }

    private static List<String> leer(File f) {
        try {
            return f.isFile() ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Valor de un campo de las lineas que escribimos nosotros o sBank (sin comillas escapadas en numeros). */
    static String campo(String l, String k) {
        int i = l.indexOf("\"" + k + "\":");
        if (i < 0) {
            return "0";
        }
        i += k.length() + 3;
        if (l.charAt(i) == '"') {
            int j = l.indexOf('"', i + 1);
            while (j > 0 && l.charAt(j - 1) == '\\') {
                j = l.indexOf('"', j + 1);
            }
            return l.substring(i + 1, j < 0 ? l.length() : j).replace("\\\"", "\"");
        }
        int j = i;
        while (j < l.length() && ",}".indexOf(l.charAt(j)) < 0) {
            j++;
        }
        return l.substring(i, j);
    }
}
