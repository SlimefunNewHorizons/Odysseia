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
    void failedPersistenceRestoresCachedBankAndReportsNoRetention() throws Exception {
        Bank bank = bank(100);
        BankApi.result = false;
        assertEquals(0, retain(30));
        assertEquals(100, bank.getBalance());
    }

    @Test
    void thrownPersistenceRestoresCachedBankAndReportsNoRetention() throws Exception {
        Bank bank = bank(100);
        BankApi.throwsOnPersist = true;
        assertEquals(0, retain(30));
        assertEquals(100, bank.getBalance());
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
        assertEquals(10, retain(30));
        assertEquals(100, bank.getBalance());
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
        public static Map<String, Bank> getBanks() { return banks; }
        public static Database getDb() { return new Database(); }
        public static String key(Player player) { return player.getName() + "@" + Modality.of(player.getWorld().getName()); }
        public static boolean persistBank(Bank bank) {
            if (throwsOnPersist) throw new IllegalStateException("fixture database unavailable");
            return result;
        }
    }

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
