package org.metamechanists.odysseia.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyWatchdogSafetyTest {
    @TempDir Path directory;
    private JavaPlugin plugin;
    private EconomyWatchdog watchdog;
    private Economy economy;
    private Player player;
    private World world;

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        plugin.getConfig().set("economy-watchdog.max-gain-per-window", 100);
        economy = mock(Economy.class);
        watchdog = new EconomyWatchdog(plugin);
        invoke(watchdog, "cargarRetenciones", new Class<?>[0]);
        var field = EconomyWatchdog.class.getDeclaredField("economy");
        field.setAccessible(true);
        field.set(watchdog, economy);
        player = mock(Player.class);
        world = mock(World.class);
        when(player.getName()).thenReturn("Fixture");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(player.getPlayer()).thenReturn(player);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("survival");
        BankApi.banks.clear();
        BankApi.result = true;
        BankApi.throwsOnPersist = false;
        BankApi.beforePersist = null;
        watchdog.hookSBank(BankApi.class, Bank.class, Modality.class);
    }

    @Test
    void changingModalityStartsNewSamplesWithoutFlaggingExistingWealth() throws Exception {
        when(economy.getBalance(player)).thenReturn(10.0);
        tick();
        when(world.getName()).thenReturn("skyblock");
        when(economy.getBalance(player)).thenReturn(10_000.0);
        tick();
        assertTrue(watchdog.fiscalizados().isEmpty());
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
    }

    @Test
    void realGainWithinSameModalityStillTriggersRetention() throws Exception {
        when(economy.getBalance(player)).thenReturn(10.0);
        tick();
        when(economy.getBalance(player)).thenReturn(10_000.0);
        when(economy.withdrawPlayer(player, 9890)).thenReturn(response(true));
        tick();
        var f = watchdog.fiscalizados().get(player.getUniqueId());
        assertNotNull(f);
        assertEquals("survival", f.modalidad);
        assertEquals(9890, f.retenido);
        verify(economy).withdrawPlayer(player, 9890);
    }

    @Test
    void fiscalizationDoesNotRetainOrGetReassignedInAnotherModality() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        var fiscalization = watchdog.fiscalizados().get(player.getUniqueId());
        when(world.getName()).thenReturn("skyblock");
        when(economy.getBalance(player)).thenReturn(100_000.0);
        tick();
        assertEquals(0, fiscalization.retenido);
        assertThrows(IllegalStateException.class, () -> watchdog.congelar(player, 5, "other"));
        assertEquals("survival", fiscalization.modalidad);
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
    }

    @Test
    void oldRecordsRemainQuarantinedAndSurviveSaving() throws Exception {
        var f = new EconomyWatchdog.Fiscalizacion();
        f.hasta = Long.MAX_VALUE;
        f.retenido = 25;
        watchdog.fiscalizados().put(player.getUniqueId(), f);
        when(economy.getBalance(player)).thenReturn(100_000.0);
        tick();
        assertThrows(IllegalStateException.class, () -> watchdog.liberar(player));
        assertThrows(IllegalStateException.class, () -> watchdog.confiscar(player));
        watchdog.stop();
        EconomyWatchdog loaded = new EconomyWatchdog(plugin);
        invoke(loaded, "cargarRetenciones", new Class<?>[0]);
        assertNull(loaded.fiscalizados().get(player.getUniqueId()).modalidad);
        assertEquals(25, loaded.fiscalizados().get(player.getUniqueId()).retenido);
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
    }

    @Test
    void modalityIsSavedAndRestoredWithoutMigratingAccountMoney() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        EconomyWatchdog loaded = new EconomyWatchdog(plugin);
        invoke(loaded, "cargarRetenciones", new Class<?>[0]);
        assertEquals("survival", loaded.fiscalizados().get(player.getUniqueId()).modalidad);
    }

    @Test
    void failedPersistenceRestoresCachedBankAndQuarantinesUncertainResult() throws Exception {
        Bank bank = bank(100);
        BankApi.result = false;
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertEquals(100, bank.getBalance());
        assertQuarantined(reload());
    }

    @Test
    void thrownPersistenceRestoresCachedBankAndReportsNoRetention() throws Exception {
        Bank bank = bank(100);
        BankApi.throwsOnPersist = true;
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertEquals(100, bank.getBalance());
        assertQuarantined(reload());
    }

    @Test
    void confirmedPersistenceCountsOnlyDebitedBankAmount() throws Exception {
        Bank bank = bank(20);
        assertEquals(20, retain(30));
        assertEquals(0, bank.getBalance());
    }

    @Test
    void failedBankWriteDoesNotUndoConfirmedWalletWithdrawal() throws Exception {
        Bank bank = bank(100);
        when(economy.getBalance(player)).thenReturn(10.0);
        when(economy.withdrawPlayer(player, 10)).thenReturn(response(true));
        BankApi.result = false;
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertEquals(100, bank.getBalance());
        assertEquals(10, reload().fiscalizados().get(player.getUniqueId()).retenido);
        assertQuarantined(reload());
        verify(economy).withdrawPlayer(player, 10);
    }

    @Test
    void legacyVoidPersistenceRemainsSupported() throws Exception {
        watchdog.hookSBank(VoidBankApi.class, Bank.class, Modality.class);
        Bank bank = bank(100);
        assertEquals(30, retain(30));
        assertEquals(70, bank.getBalance());
    }

    @Test
    void releaseCannotCreditAnotherModalityOrOfflineAccount() {
        watchdog.congelar(player, 5, "fixture");
        var f = watchdog.fiscalizados().get(player.getUniqueId());
        f.retenido = 25;
        when(world.getName()).thenReturn("skyblock");
        assertThrows(IllegalStateException.class, () -> watchdog.liberar(player));
        when(world.getName()).thenReturn("survival");
        when(player.getPlayer()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> watchdog.liberar(player));
        assertSame(f, watchdog.fiscalizados().get(player.getUniqueId()));
        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
    }

    @Test
    void rejectedReleasePreservesClaimAndConfirmedReleaseRemovesIt() {
        watchdog.congelar(player, 5, "fixture");
        var f = watchdog.fiscalizados().get(player.getUniqueId());
        f.retenido = 25;
        when(economy.depositPlayer(player, 25)).thenReturn(response(false), response(true));
        assertThrows(IllegalStateException.class, () -> watchdog.liberar(player));
        assertSame(f, watchdog.fiscalizados().get(player.getUniqueId()));
        assertEquals(25, watchdog.liberar(player));
        assertTrue(watchdog.fiscalizados().isEmpty());
    }

    @Test
    void expirationDoesNotDeleteUnpaidRetention() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        var f = watchdog.fiscalizados().get(player.getUniqueId());
        f.hasta = 0;
        f.retenido = 25;
        tick();
        assertSame(f, watchdog.fiscalizados().get(player.getUniqueId()));
        assertEquals(25, f.retenido);
    }

    @Test
    void walletIntentIsDurableBeforeWithdrawalAndCompletionSurvivesReload() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        when(economy.getBalance(player)).thenReturn(100.0);
        when(economy.withdrawPlayer(player, 30)).thenAnswer(call -> {
            var y = ledger();
            String key = player.getUniqueId() + ".movimiento";
            assertEquals("RETENER_VAULT", y.getString(key + ".tipo"));
            assertEquals(30, y.getDouble(key + ".monto"));
            assertEquals(100, y.getDouble(key + ".saldo-antes"));
            assertEquals("survival", y.getString(player.getUniqueId() + ".modalidad"));
            return response(true);
        });
        assertEquals(30, retain(30));
        var loaded = reload();
        assertEquals(30, loaded.fiscalizados().get(player.getUniqueId()).retenido);
        assertFalse(ledger().contains(player.getUniqueId() + ".movimiento"));
        try (var files = Files.list(directory)) {
            assertEquals(List.of("sii-retenciones.yml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void crashBeforeWalletEffectLeavesIntentWithoutAutomaticRetry() throws Exception {
        when(economy.getBalance(player)).thenReturn(100.0);
        when(economy.withdrawPlayer(player, 30)).thenThrow(new SimulatedCrash());
        assertThrows(SimulatedCrash.class, () -> retain(30));
        assertQuarantined(reload());
        verify(economy, times(1)).withdrawPlayer(player, 30);
    }

    @Test
    void crashAfterWalletEffectKeepsRecoverableAmountAndNeverRepeatsDebit() throws Exception {
        double[] wallet = {100};
        when(economy.getBalance(player)).thenAnswer(call -> wallet[0]);
        when(economy.withdrawPlayer(player, 30)).thenAnswer(call -> {
            wallet[0] -= 30;
            throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> retain(30));
        assertEquals(70, wallet[0]);
        var y = ledger();
        assertEquals(30, y.getDouble(player.getUniqueId() + ".movimiento.monto"));
        assertEquals(0, y.getDouble(player.getUniqueId() + ".retenido"));
        assertQuarantined(reload());
        watchdog.stop();
        assertQuarantined(reload());
        verify(economy, times(1)).withdrawPlayer(player, 30);
    }

    @Test
    void crashAfterReleaseNeverRepeatsCreditOrDeletesClaim() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        watchdog.fiscalizados().get(player.getUniqueId()).retenido = 25;
        double[] wallet = {10};
        when(economy.getBalance(player)).thenAnswer(call -> wallet[0]);
        when(economy.depositPlayer(player, 25)).thenAnswer(call -> {
            assertEquals("LIBERAR_VAULT", ledger().getString(player.getUniqueId() + ".movimiento.tipo"));
            wallet[0] += 25;
            throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> watchdog.liberar(player));
        var loaded = reload();
        assertEquals(25, loaded.fiscalizados().get(player.getUniqueId()).retenido);
        assertQuarantined(loaded);
        assertEquals(35, wallet[0]);
        verify(economy, times(1)).depositPlayer(player, 25);
    }

    @Test
    void providerExceptionAfterEffectAlsoQuarantinesInsteadOfRetrying() throws Exception {
        when(economy.getBalance(player)).thenReturn(100.0);
        when(economy.withdrawPlayer(player, 30)).thenThrow(new IllegalArgumentException("provider uncertain"));
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertQuarantined(watchdog);
        assertQuarantined(reload());
        verify(economy, times(1)).withdrawPlayer(player, 30);
    }

    @Test
    void failedIntentWriteDoesNotTouchMoneyAndPreservesPreviousSnapshot() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        byte[] original = Files.readAllBytes(ledgerPath());
        Files.move(ledgerPath(), directory.resolve("recoverable.yml"));
        Files.createDirectory(ledgerPath()); // Simula destino que rechaza el rename atomico.
        when(economy.getBalance(player)).thenReturn(100.0);
        assertThrows(IllegalStateException.class, () -> retain(30));
        verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
        verify(economy, never()).depositPlayer(any(org.bukkit.OfflinePlayer.class), anyDouble());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("recoverable.yml")));
        watchdog.stop();
        assertTrue(Files.isDirectory(ledgerPath()));
    }

    @Test
    void failedCompletionWriteRetainsIntentInMemoryAndAfterRestart() throws Exception {
        when(economy.getBalance(player)).thenReturn(100.0);
        byte[][] intent = {null};
        when(economy.withdrawPlayer(player, 30)).thenAnswer(call -> {
            intent[0] = Files.readAllBytes(ledgerPath());
            Files.delete(ledgerPath());
            Files.createDirectory(ledgerPath());
            return response(true);
        });
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertQuarantined(watchdog);
        Files.delete(ledgerPath());
        Files.write(ledgerPath(), intent[0]); // Restaura exactamente la intencion durable del fixture.
        watchdog.stop();
        assertArrayEquals(intent[0], Files.readAllBytes(ledgerPath()));
        assertQuarantined(reload());
        verify(economy, times(1)).withdrawPlayer(player, 30);
    }

    @Test
    void confirmedReleaseSurvivesRestartWithoutLeavingAnotherClaim() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        watchdog.fiscalizados().get(player.getUniqueId()).retenido = 25;
        when(economy.depositPlayer(player, 25)).thenReturn(response(true));
        assertEquals(25, watchdog.liberar(player));
        assertEquals(-1, reload().liberar(player));
        verify(economy, times(1)).depositPlayer(player, 25);
    }

    @Test
    void malformedLedgerIsNeverReplacedByEmptyStateOnStop() throws Exception {
        byte[] corrupt = "broken: [unterminated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(ledgerPath(), corrupt);
        var loaded = new EconomyWatchdog(plugin);
        assertThrows(IllegalStateException.class, () -> invoke(loaded, "cargarRetenciones", new Class<?>[0]));
        assertThrows(IllegalStateException.class, () -> loaded.liberar(player));
        loaded.stop();
        assertArrayEquals(corrupt, Files.readAllBytes(ledgerPath()));
        verifyNoInteractions(economy);
    }

    @Test
    void invalidStoredAmountDoesNotSilentlyBecomeZero() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        var y = ledger();
        y.set(player.getUniqueId() + ".retenido", "invalid");
        y.save(ledgerPath().toFile());
        byte[] original = Files.readAllBytes(ledgerPath());
        var loaded = new EconomyWatchdog(plugin);
        assertThrows(IllegalStateException.class, () -> invoke(loaded, "cargarRetenciones", new Class<?>[0]));
        loaded.stop();
        assertArrayEquals(original, Files.readAllBytes(ledgerPath()));
    }

    @Test
    void disabledServiceStopDoesNotOverwriteUnloadedClaims() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        byte[] original = Files.readAllBytes(ledgerPath());
        plugin.getConfig().set("economy-watchdog.enabled", false);
        var disabled = new EconomyWatchdog(plugin);
        disabled.start();
        disabled.stop();
        assertArrayEquals(original, Files.readAllBytes(ledgerPath()));
    }

    @Test
    void bankIntentIsDurableBeforePersistenceAndConfirmedAmountReloads() throws Exception {
        Bank bank = bank(100);
        BankApi.beforePersist = () -> {
            var y = ledger();
            assertEquals("RETENER_BANCO", y.getString(player.getUniqueId() + ".movimiento.tipo"));
            assertEquals(30, y.getDouble(player.getUniqueId() + ".movimiento.monto"));
        };
        assertEquals(30, retain(30));
        assertEquals(70, bank.getBalance());
        assertEquals(30, reload().fiscalizados().get(player.getUniqueId()).retenido);
    }

    @Test
    void bankCrashAfterDatabaseEffectDoesNotReplayOrTrustRestoredCache() throws Exception {
        bank(100);
        double[] database = {100};
        BankApi.beforePersist = () -> {
            database[0] = BankApi.banks.get("Fixture@survival").getBalance();
            throw new SimulatedCrash();
        };
        assertThrows(IllegalStateException.class, () -> retain(30));
        assertEquals(70, database[0]);
        assertQuarantined(reload());
    }

    @Test
    void releaseCompletionFailureRestoresClaimWithIntentInsteadOfRepeatingCredit() throws Exception {
        watchdog.congelar(player, 5, "fixture");
        watchdog.fiscalizados().get(player.getUniqueId()).retenido = 25;
        byte[][] intent = {null};
        when(economy.depositPlayer(player, 25)).thenAnswer(call -> {
            intent[0] = Files.readAllBytes(ledgerPath());
            Files.delete(ledgerPath());
            Files.createDirectory(ledgerPath());
            return response(true);
        });
        assertThrows(IllegalStateException.class, () -> watchdog.liberar(player));
        assertEquals(25, watchdog.fiscalizados().get(player.getUniqueId()).retenido);
        assertQuarantined(watchdog);
        Files.delete(ledgerPath());
        Files.write(ledgerPath(), intent[0]);
        assertQuarantined(reload());
        verify(economy, times(1)).depositPlayer(player, 25);
    }

    private Path ledgerPath() { return directory.resolve("sii-retenciones.yml"); }

    private YamlConfiguration ledger() throws Exception {
        var y = new YamlConfiguration();
        y.load(ledgerPath().toFile());
        return y;
    }

    private EconomyWatchdog reload() throws Exception {
        var loaded = new EconomyWatchdog(plugin);
        var field = EconomyWatchdog.class.getDeclaredField("economy");
        field.setAccessible(true);
        field.set(loaded, economy);
        loaded.hookSBank(BankApi.class, Bank.class, Modality.class);
        invoke(loaded, "cargarRetenciones", new Class<?>[0]);
        return loaded;
    }

    private void assertQuarantined(EconomyWatchdog loaded) throws Exception {
        assertNotNull(loaded.fiscalizados().get(player.getUniqueId()));
        assertThrows(IllegalStateException.class, () -> loaded.liberar(player));
        assertThrows(IllegalStateException.class, () -> loaded.confiscar(player));
        assertThrows(IllegalStateException.class, () -> loaded.congelar(player, 5, "retry"));
        assertThrows(IllegalStateException.class, () -> invoke(loaded, "retener", new Class<?>[]{Player.class, double.class}, player, 30.0));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            invoke(loaded, "tick", new Class<?>[0]);
        }
    }

    private static final class SimulatedCrash extends Error { }

    private Bank bank(double balance) {
        Bank bank = new Bank(balance);
        BankApi.banks.put("Fixture@survival", bank);
        return bank;
    }

    private static EconomyResponse response(boolean success) {
        return new EconomyResponse(25, 0, success ? EconomyResponse.ResponseType.SUCCESS
                : EconomyResponse.ResponseType.FAILURE, "fixture");
    }

    private double retain(double amount) throws Exception {
        if (!watchdog.fiscalizados().containsKey(player.getUniqueId())) watchdog.congelar(player, 5, "fixture");
        return (double) invoke(watchdog, "retener", new Class<?>[]{Player.class, double.class}, player, amount);
    }

    private void tick() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            invoke(watchdog, "tick", new Class<?>[0]);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Error error) throw error;
            throw (Exception) exception.getCause();
        }
    }

    public static final class Bank {
        private double balance;
        Bank(double balance) { this.balance = balance; }
        public double getBalance() { return balance; }
        public void setBalance(double balance) { this.balance = balance; }
    }

    public static class BankApi {
        static final Map<String, Bank> banks = new HashMap<>();
        static boolean result;
        static boolean throwsOnPersist;
        static CheckedAction beforePersist;
        public static Map<String, Bank> getBanks() { return banks; }
        public static Database getDb() { return new Database(); }
        public static String key(Player player) { return player.getName() + "@" + Modality.of(player.getWorld().getName()); }
        public static boolean persistBank(Bank bank) {
            if (throwsOnPersist) throw new IllegalStateException("fixture database unavailable");
            if (beforePersist != null) {
                try { beforePersist.run(); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    public static final class VoidBankApi {
        public static Map<String, Bank> getBanks() { return BankApi.banks; }
        public static Database getDb() { return new Database(); }
        public static String key(Player player) { return BankApi.key(player); }
        public static void persistBank(Bank bank) { }
    }

    public static final class Database {
        public Bank getBank(String name, String modality) { return BankApi.banks.get(name + "@" + modality); }
    }

    public static final class Modality {
        public static String of(String world) { return world; }
    }
}
