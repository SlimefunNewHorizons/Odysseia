package org.metamechanists.odysseia.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.utils.WebhookSender;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.function.BooleanSupplier;

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
    private enum TipoMovimiento { RETENER_VAULT, RETENER_BANCO, LIBERAR_VAULT }
    private record Movimiento(String id, TipoMovimiento tipo, double monto, double saldoAntes, long desde) {}

    /** Estado de una cuenta fiscalizada. */
    public static final class Fiscalizacion {
        public long hasta;          // epoch ms
        public double base;         // patrimonio de referencia: por encima de esto se retiene
        public double retenido;     // acumulado retenido (pendiente de decision del staff)
        public String motivo;
        public long desde;
        public String modalidad;    // null: registro antiguo, requiere revision sin mover dinero
        private Movimiento pendiente; // Resultado incierto: nunca reproducir automaticamente.
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Deque<Sample>> muestras = new HashMap<>();
    private final Map<UUID, String> modalidadMuestras = new HashMap<>();
    private final Map<UUID, Fiscalizacion> fiscalizados = new HashMap<>();
    private final File ficheroRetenciones;
    private Economy economy;
    private int taskId = -1;
    private boolean retencionesCargadas;
    private boolean persistenciaSegura = true;

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
        try {
            cargarRetenciones();
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.SEVERE, "[SII] Persistencia no disponible; vigilancia detenida sin mover dinero.", e);
            return;
        }
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
        if (retencionesCargadas && persistenciaSegura) guardarRetenciones();
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

    private String modalidad(Player player) {
        if (sbankGetBanks != null && modalityOf != null) {
            try {
                return (String) modalityOf.invoke(null, player.getWorld().getName());
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("No pude determinar la modalidad de la cuenta.", e);
            }
        }
        // Sin API de modalidad, no mezclar siquiera dos mundos distintos.
        return "world:" + player.getWorld().getName();
    }

    private void tick() {
        if (!persistenciaSegura) return;
        long ahora = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!persistenciaSegura) break;
            if (p.hasPermission("odysseia.sii.exempt")) continue;
            double total;
            String modalidad;
            try {
                modalidad = modalidad(p);
                total = patrimonio(p);
            } catch (RuntimeException e) {
                continue;
            }
            UUID id = p.getUniqueId();
            if (!modalidad.equals(modalidadMuestras.put(id, modalidad))) muestras.remove(id);
            Fiscalizacion f = fiscalizados.get(id);
            if (f != null) {
                if (f.pendiente != null) continue;
                // Una fiscalizacion por jugador: queda en pausa fuera de su cuenta original.
                // Los registros antiguos sin modalidad se conservan para revision manual.
                if (!modalidad.equals(f.modalidad)) continue;
                if (ahora >= f.hasta) {
                    if (f.retenido > 0) continue; // No perder la deuda al terminar el plazo.
                    try {
                        eliminarFiscalizacion(id, f);
                    } catch (IllegalStateException e) {
                        plugin.getLogger().log(Level.WARNING, "[SII] No pude cerrar la fiscalizacion.", e);
                        continue;
                    }
                    p.sendMessage(color("&6[SII] &aTu cuenta salio de fiscalizacion. "
                            + (f.retenido > 0 ? "Hay &e₯" + MONEY.format(f.retenido) + " &aretenidos a la espera del staff." : "")));
                } else {
                    double exceso = total - f.base - tolerancia();
                    if (exceso > 0) {
                        double ret;
                        try {
                            ret = retener(p, exceso);
                        } catch (IllegalStateException e) {
                            plugin.getLogger().log(Level.WARNING, "[SII] Retencion pausada; requiere conciliacion antes de reintentar.", e);
                            continue;
                        }
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
            try {
                if (gananciaVentana > umbralVentana()) {
                    disparar(p, total, gananciaVentana, umbralVentana(), "+" + MONEY.format(gananciaVentana) + " en " + (ventanaMs() / 60000) + " min");
                } else if (gananciaHora > umbralHora()) {
                    disparar(p, total, gananciaHora, umbralHora(), "+" + MONEY.format(gananciaHora) + " en 1 h");
                }
            } catch (IllegalStateException e) {
                plugin.getLogger().log(Level.WARNING, "[SII] Fiscalizacion pausada; no se reintenta el movimiento incierto.", e);
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
        asegurarPersistencia();
        Fiscalizacion f = new Fiscalizacion();
        f.desde = System.currentTimeMillis();
        f.hasta = f.desde + congelacionMs();
        f.base = total;
        f.motivo = detalle;
        f.modalidad = modalidad(p);
        fiscalizados.put(p.getUniqueId(), f);
        muestras.remove(p.getUniqueId());
        guardarRetenciones();
        double retenido = exceso > 0 ? retener(p, exceso) : 0;
        f.base = total - retenido;
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
        comprobarCuenta(p);
        if (!Double.isFinite(monto) || monto < 0) throw new IllegalArgumentException("Monto invalido.");
        double restante = monto;
        double cartera = economy.getBalance(p);
        double delMonedero = Math.min(cartera, restante);
        if (delMonedero > 0) {
            restante -= mover(p, TipoMovimiento.RETENER_VAULT, delMonedero, cartera,
                    () -> economy.withdrawPlayer(p, delMonedero).transactionSuccess());
        }
        if (restante > 0.01 && sbankGetBanks != null) {
            Object b = bancoDe(p);
            if (b != null) {
                try {
                    double saldo = (double) bankGetBalance.invoke(b);
                    double delBanco = Math.min(saldo, restante);
                    if (delBanco > 0) {
                        restante -= mover(p, TipoMovimiento.RETENER_BANCO, delBanco, saldo, () -> {
                            boolean persistido = false;
                            try {
                                bankSetBalance.invoke(b, saldo - delBanco);
                                Object resultado = sbankPersist.invoke(null, b);
                                persistido = sbankPersist.getReturnType() == void.class || Boolean.TRUE.equals(resultado);
                                // sBank devuelve false al capturar SQLException: no demuestra ausencia de commit.
                                if (!persistido) throw new IllegalStateException("Persistencia bancaria rechazada; requiere conciliacion.");
                                return persistido;
                            } catch (ReflectiveOperationException e) {
                                throw new IllegalStateException("Resultado bancario incierto.", e);
                            } finally {
                                if (!persistido) {
                                    try {
                                        bankSetBalance.invoke(b, saldo);
                                    } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException("No pude restaurar la cache bancaria.", e);
                                    }
                                }
                            }
                        });
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("No pude consultar el banco antes de retener.", e);
                }
            }
        }
        return monto - restante;
    }

    // ------------------------------------------------------------------ staff

    public Map<UUID, Fiscalizacion> fiscalizados() {
        return fiscalizados;
    }

    private void comprobarCuenta(OfflinePlayer target) {
        asegurarPersistencia();
        Fiscalizacion f = fiscalizados.get(target.getUniqueId());
        if (f == null) return;
        Player online = target.getPlayer();
        if (f.pendiente != null) {
            throw new IllegalStateException("Movimiento pendiente de conciliacion: no se permite repetir ni eliminar la reclamacion.");
        }
        if (f.modalidad == null || online == null || !f.modalidad.equals(modalidad(online))) {
            throw new IllegalStateException("Retencion conservada: requiere jugador conectado en la modalidad original; registros antiguos requieren revision manual.");
        }
    }

    /** Devuelve lo retenido solo en la modalidad original y tras confirmar el abono. */
    public double liberar(OfflinePlayer target) {
        comprobarCuenta(target);
        Fiscalizacion f = fiscalizados.get(target.getUniqueId());
        if (f == null || f.retenido <= 0) {
            if (f == null) return -1;
            eliminarFiscalizacion(target.getUniqueId(), f);
            return 0;
        }
        double monto = f.retenido;
        if (mover(target.getPlayer(), TipoMovimiento.LIBERAR_VAULT, monto, economy.getBalance(target),
                () -> economy.depositPlayer(target, monto).transactionSuccess()) == 0) {
            throw new IllegalStateException("Abono rechazado: la retencion sigue pendiente.");
        }
        plugin.getLogger().info("[SII] Liberados " + MONEY.format(f.retenido) + " a " + target.getName());
        return f.retenido;
    }

    /** Confisca definitivamente lo retenido (queda solo en el registro). */
    public double confiscar(OfflinePlayer target) {
        comprobarCuenta(target);
        Fiscalizacion f = fiscalizados.get(target.getUniqueId());
        if (f == null) return -1;
        eliminarFiscalizacion(target.getUniqueId(), f);
        registrarConfiscacion(target, f);
        plugin.getLogger().info("[SII] Confiscados " + MONEY.format(f.retenido) + " a " + target.getName());
        return f.retenido;
    }

    public void congelar(Player target, double horas, String motivo) {
        comprobarCuenta(target);
        Fiscalizacion anterior = fiscalizados.get(target.getUniqueId());
        Fiscalizacion f = new Fiscalizacion();
        f.retenido = anterior == null ? 0 : anterior.retenido;
        f.desde = System.currentTimeMillis();
        f.hasta = f.desde + (long) (horas * 3600_000L);
        f.base = patrimonio(target);
        f.motivo = motivo;
        f.modalidad = modalidad(target);
        fiscalizados.put(target.getUniqueId(), f);
        try {
            guardarRetenciones();
        } catch (IllegalStateException e) {
            if (anterior == null) fiscalizados.remove(target.getUniqueId());
            else fiscalizados.put(target.getUniqueId(), anterior);
            throw e;
        }
        target.sendMessage(color("&6[SII] &cTu cuenta queda fiscalizada " + horas + " h por el staff: " + motivo));
    }

    // ------------------------------------------------------------------ persistencia / avisos

    private void asegurarPersistencia() {
        if (!persistenciaSegura) throw new IllegalStateException("Persistencia SII bloqueada hasta revision y reinicio del servicio.");
        if (!retencionesCargadas) cargarRetenciones();
    }

    /** Intencion durable antes del efecto externo; una caida nunca provoca replay automatico. */
    private double mover(Player player, TipoMovimiento tipo, double monto, double saldoAntes, BooleanSupplier accion) {
        comprobarCuenta(player);
        Fiscalizacion f = fiscalizados.get(player.getUniqueId());
        if (f == null || !Double.isFinite(monto) || monto <= 0 || !Double.isFinite(saldoAntes)
                || !Double.isFinite(f.retenido) || f.retenido < 0
                || !Double.isFinite(f.retenido + monto)) {
            throw new IllegalStateException("Movimiento sin fiscalizacion o saldo valido.");
        }
        Movimiento movimiento = new Movimiento(UUID.randomUUID().toString(), tipo, monto, saldoAntes, System.currentTimeMillis());
        f.pendiente = movimiento;
        guardarRetenciones(); // Si falla, no se toca Vault ni sBank.
        boolean confirmado;
        try {
            confirmado = accion.getAsBoolean();
        } catch (RuntimeException e) {
            // La API externa puede haber aplicado el cambio antes de lanzar la excepcion.
            throw new IllegalStateException("Resultado externo incierto; intencion conservada sin reintento.", e);
        }
        double retenidoAnterior = f.retenido;
        if (confirmado) {
            if (tipo == TipoMovimiento.LIBERAR_VAULT) fiscalizados.remove(player.getUniqueId());
            else f.retenido += monto;
        }
        f.pendiente = null;
        try {
            guardarRetenciones();
        } catch (IllegalStateException e) {
            f.retenido = retenidoAnterior;
            f.pendiente = movimiento;
            fiscalizados.put(player.getUniqueId(), f);
            throw e;
        }
        return confirmado ? monto : 0;
    }

    private void eliminarFiscalizacion(UUID id, Fiscalizacion f) {
        fiscalizados.remove(id);
        try {
            guardarRetenciones();
        } catch (IllegalStateException e) {
            fiscalizados.put(id, f);
            throw e;
        }
    }

    private synchronized void guardarRetenciones() {
        asegurarPersistencia();
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Fiscalizacion> e : fiscalizados.entrySet()) {
            String k = e.getKey().toString();
            Fiscalizacion f = e.getValue();
            y.set(k + ".hasta", f.hasta);
            y.set(k + ".base", f.base);
            y.set(k + ".retenido", f.retenido);
            y.set(k + ".motivo", f.motivo);
            y.set(k + ".desde", f.desde);
            y.set(k + ".modalidad", f.modalidad);
            if (f.pendiente != null) {
                Movimiento m = f.pendiente;
                y.set(k + ".movimiento.id", m.id());
                y.set(k + ".movimiento.tipo", m.tipo().name());
                y.set(k + ".movimiento.monto", m.monto());
                y.set(k + ".movimiento.saldo-antes", m.saldoAntes());
                y.set(k + ".movimiento.desde", m.desde());
            }
        }
        java.nio.file.Path temporal = null;
        try {
            java.nio.file.Path destino = ficheroRetenciones.toPath().toAbsolutePath();
            Files.createDirectories(destino.getParent());
            temporal = Files.createTempFile(destino.getParent(), ".sii-retenciones-", ".tmp");
            try (FileChannel canal = FileChannel.open(temporal, StandardOpenOption.WRITE)) {
                ByteBuffer datos = StandardCharsets.UTF_8.encode(y.saveToString());
                while (datos.hasRemaining()) canal.write(datos);
                canal.force(true);
            }
            Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directorio = FileChannel.open(destino.getParent(), StandardOpenOption.READ)) {
                directorio.force(true);
            }
        } catch (IOException | RuntimeException e) {
            persistenciaSegura = false;
            throw new IllegalStateException("No pude confirmar persistencia SII; movimientos detenidos.", e);
        } finally {
            if (temporal != null) {
                try { Files.deleteIfExists(temporal); }
                catch (IOException e) { plugin.getLogger().warning("[SII] Temporal de persistencia pendiente de limpieza."); }
            }
        }
    }

    private void cargarRetenciones() {
        if (retencionesCargadas) return;
        if (!persistenciaSegura) throw new IllegalStateException("Persistencia SII bloqueada.");
        YamlConfiguration y = new YamlConfiguration();
        Map<UUID, Fiscalizacion> cargadas = new HashMap<>();
        try {
            if (ficheroRetenciones.exists()) y.load(ficheroRetenciones);
            for (String k : y.getKeys(false)) {
                UUID id = UUID.fromString(k);
                if (!y.isConfigurationSection(k)) throw new IllegalArgumentException("Registro SII invalido.");
                for (String campo : List.of("hasta", "base", "retenido", "desde")) {
                    if (!(y.get(k + "." + campo) instanceof Number)) {
                        throw new IllegalArgumentException("Campo numerico SII invalido.");
                    }
                }
                Fiscalizacion f = new Fiscalizacion();
                f.hasta = y.getLong(k + ".hasta");
                f.base = y.getDouble(k + ".base");
                f.retenido = y.getDouble(k + ".retenido");
                f.motivo = y.getString(k + ".motivo", "");
                f.desde = y.getLong(k + ".desde");
                f.modalidad = y.getString(k + ".modalidad");
                if (!Double.isFinite(f.base) || !Double.isFinite(f.retenido) || f.retenido < 0) {
                    throw new IllegalArgumentException("Saldo SII invalido.");
                }
                if (y.contains(k + ".movimiento")) {
                    String m = k + ".movimiento";
                    String idMovimiento = UUID.fromString(y.getString(m + ".id", "")).toString();
                    TipoMovimiento tipo = TipoMovimiento.valueOf(y.getString(m + ".tipo", ""));
                    double monto = y.getDouble(m + ".monto", Double.NaN);
                    double saldo = y.getDouble(m + ".saldo-antes", Double.NaN);
                    if (!Double.isFinite(monto) || monto <= 0 || !Double.isFinite(saldo) || f.modalidad == null) {
                        throw new IllegalArgumentException("Intencion SII invalida.");
                    }
                    f.pendiente = new Movimiento(idMovimiento, tipo, monto, saldo, y.getLong(m + ".desde"));
                }
                cargadas.put(id, f);
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            persistenciaSegura = false;
            throw new IllegalStateException("Retenciones ilegibles; se conserva el archivo sin mover dinero.", e);
        }
        fiscalizados.putAll(cargadas);
        retencionesCargadas = true;
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
                    + ", " + (f.modalidad == null ? "modalidad pendiente de revision" : f.modalidad)
                    + ", " + horasRestantes(f) + " h, " + f.motivo
                    + (f.pendiente == null ? "" : ", movimiento pendiente de conciliacion (sin reintento)"));
        }
        return String.join("\n", out);
    }
}
