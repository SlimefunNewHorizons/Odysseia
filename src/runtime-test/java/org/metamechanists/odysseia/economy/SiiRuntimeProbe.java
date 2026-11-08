package org.metamechanists.odysseia.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Fixture exclusivo de ensayo: Paper real, cuenta sintetica, proveedor Vault ficticio. */
public final class SiiRuntimeProbe extends JavaPlugin {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private Path ledger;
    private Path wallet;
    private EconomyWatchdog watchdog;
    private Player player;
    private double balance;
    private int calls;
    private boolean crashAfterEffect;
    private String phase;

    @Override public void onEnable() {
        phase = System.getProperty("sii.probe.phase");
        ledger = getDataFolder().toPath().resolve("sii-retenciones.yml");
        wallet = getDataFolder().toPath().resolve("fixture-wallet.yml");
        getConfig().set("economy-watchdog.enabled", true);
        getConfig().set("economy-watchdog.poll-seconds", 3600);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                runPhase();
                watchdog.stop();
                Files.writeString(Path.of("probe-" + phase + ".ok"), "PASS\n");
                getLogger().info("SII_PROBE_PASS " + phase + " calls=" + calls + " balance=" + balance);
                Bukkit.shutdown();
            } catch (Throwable failure) {
                failure.printStackTrace();
                Runtime.getRuntime().halt(78);
            }
        }, 20);
    }

    private void runPhase() throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        if (phase.equals("normal") || phase.startsWith("crash-")) {
            Files.deleteIfExists(ledger);
            balance = 1000;
            calls = 0;
            saveWallet();
        } else {
            var stored = YamlConfiguration.loadConfiguration(wallet.toFile());
            balance = stored.getDouble("balance");
            calls = stored.getInt("calls");
        }
        player = (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ACCOUNT;
                    case "getName" -> "SiiFixture";
                    case "getPlayer" -> proxy;
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "isOnline" -> true;
                    case "sendMessage" -> null;
                    case "toString" -> "SiiFixture";
                    case "hashCode" -> ACCOUNT.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Economy economy = (Economy) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Economy.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBalance" -> balance;
                    case "withdrawPlayer", "depositPlayer" -> {
                        double amount = (Double) args[args.length - 1];
                        // La intencion ya debe estar en disco ANTES de tocar el proveedor.
                        var saved = YamlConfiguration.loadConfiguration(ledger.toFile());
                        check(saved.isConfigurationSection(ACCOUNT + ".movimiento"), "missing durable intent");
                        balance += method.getName().equals("depositPlayer") ? amount : -amount;
                        calls++;
                        saveWallet();
                        if (crashAfterEffect) {
                            Files.writeString(Path.of("probe-" + phase + ".crash"), "AFTER_EFFECT\n");
                            Runtime.getRuntime().halt(77);
                        }
                        yield new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
                    }
                    case "getName", "toString" -> "DurableFictionalVault";
                    case "isEnabled" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Bukkit.getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        reloadWatchdog();
        switch (phase) {
            case "normal" -> normalAndRestore();
            case "crash-debit" -> {
                watchdog.congelar(player, 1, "fixture");
                crashAfterEffect = true;
                retain();
                throw new AssertionError("crash not reached");
            }
            case "crash-credit" -> {
                watchdog.congelar(player, 1, "fixture");
                retain();
                crashAfterEffect = true;
                watchdog.liberar(player);
                throw new AssertionError("crash not reached");
            }
            case "resume-debit", "resume-credit" -> {
                int before = calls;
                check(balance == (phase.equals("resume-debit") ? 900 : 1000), "durable provider balance");
                blocked(() -> watchdog.liberar(player));
                blocked(() -> watchdog.confiscar(player));
                blocked(() -> watchdog.congelar(player, 1, "retry"));
                blocked(this::retain);
                reloadWatchdog();
                blocked(() -> watchdog.liberar(player));
                check(calls == before, "uncertain effect replayed");
                check(YamlConfiguration.loadConfiguration(ledger.toFile())
                        .isConfigurationSection(ACCOUNT + ".movimiento"), "intent lost");
            }
            default -> throw new IllegalArgumentException("unknown phase");
        }
    }

    private void normalAndRestore() throws Exception {
        watchdog.congelar(player, 1, "fixture");
        retain();
        check(balance == 900 && watchdog.fiscalizados().get(ACCOUNT).retenido == 100, "debit mismatch");
        watchdog.stop();
        byte[] savedLedger = Files.readAllBytes(ledger);
        byte[] savedWallet = Files.readAllBytes(wallet);
        Path backup = getDataFolder().toPath().resolve("fixture-consistent-backup");
        Files.createDirectory(backup);
        Files.write(backup.resolve("ledger.yml"), savedLedger);
        Files.write(backup.resolve("wallet.yml"), savedWallet);
        check(java.util.Arrays.equals(savedLedger, Files.readAllBytes(backup.resolve("ledger.yml"))), "backup ledger");
        check(java.util.Arrays.equals(savedWallet, Files.readAllBytes(backup.resolve("wallet.yml"))), "backup wallet");
        reloadWatchdog();
        check(watchdog.liberar(player) == 100 && balance == 1000, "credit mismatch");
        watchdog.stop();
        watchdog = null; // El servicio detenido no debe volver a guardar sobre la copia restaurada.
        // Restauracion consistente SOLO de los dos archivos ficticios y con el servicio detenido.
        Files.copy(backup.resolve("ledger.yml"), ledger, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(backup.resolve("wallet.yml"), wallet, StandardCopyOption.REPLACE_EXISTING);
        var restoredWallet = YamlConfiguration.loadConfiguration(wallet.toFile());
        balance = restoredWallet.getDouble("balance");
        calls = restoredWallet.getInt("calls");
        check(balance == 900 && calls == 1, "restored provider balance");
        reloadWatchdog();
        check(watchdog.fiscalizados().get(ACCOUNT).retenido == 100, "restored claim");
        check(watchdog.liberar(player) == 100 && balance == 1000 && calls == 2, "restored release");
        reloadWatchdog();
        check(watchdog.fiscalizados().isEmpty() && calls == 2, "confirmed credit replayed");

        // Fallo real de rename antes de Vault: el destino es temporalmente un directorio.
        watchdog.congelar(player, 1, "fixture-write-failure");
        watchdog.stop();
        savedLedger = Files.readAllBytes(ledger);
        Files.delete(ledger);
        Files.createDirectory(ledger);
        blocked(this::retain);
        check(calls == 2 && balance == 1000, "Vault called despite write failure");
        watchdog.stop();
        Files.delete(ledger);
        Files.write(ledger, savedLedger);
        reloadWatchdog();
        check(watchdog.fiscalizados().get(ACCOUNT).retenido == 0, "write failure lost state");
    }

    private void reloadWatchdog() {
        if (watchdog != null) watchdog.stop();
        watchdog = new EconomyWatchdog(this);
        watchdog.start();
    }

    private void retain() throws Exception {
        var method = EconomyWatchdog.class.getDeclaredMethod("retener", Player.class, double.class);
        method.setAccessible(true);
        try { method.invoke(watchdog, player, 100.0); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw (Error) e.getCause();
        }
    }

    private void saveWallet() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.set("balance", balance);
        yaml.set("calls", calls);
        Path tmp = wallet.resolveSibling("fixture-wallet.tmp");
        try (var channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.wrap(yaml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(tmp, wallet, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var directory = FileChannel.open(wallet.getParent(), StandardOpenOption.READ)) { directory.force(true); }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void blocked(Checked action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("unsafe operation was allowed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
