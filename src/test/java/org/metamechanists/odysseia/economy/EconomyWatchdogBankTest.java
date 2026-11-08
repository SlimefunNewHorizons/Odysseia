package org.metamechanists.odysseia.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyWatchdogBankTest {
    private EconomyWatchdog watchdog;
    private Player player;
    private World world;

    @BeforeEach
    void setup() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File("target/test-bank-hook"));
        watchdog = new EconomyWatchdog(plugin);
        Economy economy = mock(Economy.class);
        Field field = EconomyWatchdog.class.getDeclaredField("economy");
        field.setAccessible(true);
        field.set(watchdog, economy);
        player = mock(Player.class);
        world = mock(World.class);
        when(player.getName()).thenReturn("TestAccount");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("survival");
        when(economy.getBalance(player)).thenReturn(10.0);
        Legacy.banks.clear();
        Legacy.database.accounts.clear();
        Modern.banks.clear();
        Modern.database.accounts.clear();
    }

    @Test
    void legacyCachedAccountStillWorks() throws Exception {
        Legacy.banks.put("TestAccount", new Bank(30));
        Legacy.database.accounts.put("TestAccount", new Bank(900));
        watchdog.hookSBank(Legacy.class, Bank.class, null);
        assertEquals(40, watchdog.patrimonio(player));
    }

    @Test
    void legacyDatabaseFallbackStillWorks() throws Exception {
        Legacy.database.accounts.put("TestAccount", new Bank(50));
        watchdog.hookSBank(Legacy.class, Bank.class, null);
        assertEquals(60, watchdog.patrimonio(player));
        assertTrue(Legacy.banks.isEmpty());
    }

    @Test
    void cachedAccountFollowsModalityWithoutUsingGlobalBalance() throws Exception {
        Modern.banks.put("TestAccount", new Bank(999_999));
        Modern.banks.put("TestAccount@survival", new Bank(20));
        Modern.banks.put("TestAccount@skyblock", new Bank(80));
        watchdog.hookSBank(Modern.class, Bank.class, Modality.class);
        assertEquals(30, watchdog.patrimonio(player));
        when(world.getName()).thenReturn("skyblock");
        assertEquals(90, watchdog.patrimonio(player));
    }

    @Test
    void databaseFallbackUsesExactModalityWithoutCreatingAccounts() throws Exception {
        Modern.database.accounts.put("TestAccount@survival", new Bank(40));
        Modern.database.accounts.put("TestAccount@skyblock", new Bank(70));
        watchdog.hookSBank(Modern.class, Bank.class, Modality.class);
        assertEquals(50, watchdog.patrimonio(player));
        when(world.getName()).thenReturn("skyblock");
        assertEquals(80, watchdog.patrimonio(player));
        when(world.getName()).thenReturn("onechunk");
        assertEquals(10, watchdog.patrimonio(player));
        assertEquals(2, Modern.database.accounts.size());
        assertTrue(Modern.banks.isEmpty());
    }

    @Test
    void missingModernKeyDoesNotFallBackToGlobalAccount() {
        assertThrows(NoSuchMethodException.class,
                () -> watchdog.hookSBank(MissingKey.class, Bank.class, Modality.class));
        Legacy.banks.put("TestAccount", new Bank(999_999));
        assertEquals(10, watchdog.patrimonio(player));
    }

    @Test
    void failedRebindDisablesPreviousHook() throws Exception {
        Legacy.banks.put("TestAccount", new Bank(30));
        watchdog.hookSBank(Legacy.class, Bank.class, null);
        assertThrows(NoSuchMethodException.class,
                () -> watchdog.hookSBank(MissingKey.class, Bank.class, Modality.class));
        assertEquals(10, watchdog.patrimonio(player));
    }

    public static final class Bank {
        private double balance;
        Bank(double balance) { this.balance = balance; }
        public double getBalance() { return balance; }
        public void setBalance(double balance) { fail("Reading must not change bank balances"); }
    }

    public static class Legacy {
        static final Map<String, Bank> banks = new HashMap<>();
        static final LegacyDatabase database = new LegacyDatabase();
        public static Map<String, Bank> getBanks() { return banks; }
        public static LegacyDatabase getDb() { return database; }
        public static boolean persistBank(Bank bank) { fail("Reading must not persist accounts"); return false; }
    }

    public static final class LegacyDatabase {
        final Map<String, Bank> accounts = new HashMap<>();
        public Bank getBank(String name) { return accounts.get(name); }
    }

    public static class Modern {
        static final Map<String, Bank> banks = new HashMap<>();
        static final ModernDatabase database = new ModernDatabase();
        public static Map<String, Bank> getBanks() { return banks; }
        public static ModernDatabase getDb() { return database; }
        public static String key(Player player) {
            return player.getName() + "@" + Modality.of(player.getWorld().getName());
        }
        public static boolean persistBank(Bank bank) { fail("Reading must not persist accounts"); return false; }
    }

    public static final class ModernDatabase {
        final Map<String, Bank> accounts = new HashMap<>();
        public Bank getBank(String name, String modality) { return accounts.get(name + "@" + modality); }
        public Bank getBank(String name) { fail("Modern API must not read global accounts"); return null; }
    }

    public static final class Modality {
        public static String of(String world) { return world; }
    }

    public static final class MissingKey {
        public static Map<String, Bank> getBanks() { return Legacy.banks; }
        public static boolean persistBank(Bank bank) { fail("Reading must not persist accounts"); return false; }
        public static ModernDatabase getDb() { return Modern.database; }
    }
}
