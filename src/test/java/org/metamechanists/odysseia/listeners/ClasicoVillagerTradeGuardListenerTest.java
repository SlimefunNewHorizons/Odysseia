package org.metamechanists.odysseia.listeners;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.modalities.ModalityService;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasicoVillagerTradeGuardListenerTest {
    private ServerMock server;
    private WorldMock clasicoWorld;
    private WorldMock survivalWorld;
    private WorldMock mappedClasicoWorld;
    private ClasicoVillagerTradeGuardListener listener;
    private Enchantment customEnchant;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        clasicoWorld = server.addSimpleWorld("clasico");
        survivalWorld = server.addSimpleWorld("world");
        mappedClasicoWorld = server.addSimpleWorld("retro_realm");

        YamlConfiguration config = new YamlConfiguration();
        config.set("modalidades.modos.survival.mundos", List.of("world"));
        config.set("modalidades.modos.clasico.mundos", List.of("clasico", "retro_realm"));

        Odysseia plugin = mock(Odysseia.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("clasico-villager-test"));
        when(plugin.isEnabled()).thenReturn(true);
        ModalityService modalities = new ModalityService(plugin);

        listener = new ClasicoVillagerTradeGuardListener(plugin, modalities);

        customEnchant = mock(Enchantment.class);
        when(customEnchant.getKey()).thenReturn(NamespacedKey.fromString("excellentenchants:telepathy"));
        when(customEnchant.getMaxLevel()).thenReturn(3);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MerchantRecipe recipeWith(ItemStack result) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, 12, true, 5, 0.2f);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 12));
        return recipe;
    }

    @Test
    void sanitizeRecipesClampsOverLeveledVanillaEnchantmentsOnToolsAndBooks() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta pickMeta = pickaxe.getItemMeta();
        pickMeta.addEnchant(Enchantment.EFFICIENCY, 6, true);
        pickMeta.addEnchant(Enchantment.UNBREAKING, 8, true);
        pickaxe.setItemMeta(pickMeta);

        ItemStack overLeveledBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) overLeveledBook.getItemMeta();
        bookMeta.addStoredEnchant(Enchantment.EFFICIENCY, 6, true);
        bookMeta.addStoredEnchant(Enchantment.MENDING, 1, true);
        overLeveledBook.setItemMeta(bookMeta);

        ItemStack bread = new ItemStack(Material.BREAD, 6);

        Merchant villager = clasicoWorld.spawn(clasicoWorld.getSpawnLocation(), Villager.class);
        villager.setRecipes(List.of(recipeWith(pickaxe), recipeWith(overLeveledBook), recipeWith(bread)));

        assertTrue(listener.sanitizeRecipes(villager));
        List<MerchantRecipe> sanitized = villager.getRecipes();
        assertEquals(3, sanitized.size());

        ItemStack cleanPick = sanitized.get(0).getResult();
        assertEquals(5, cleanPick.getEnchantmentLevel(Enchantment.EFFICIENCY));
        assertEquals(3, cleanPick.getEnchantmentLevel(Enchantment.UNBREAKING));

        ItemStack cleanBook = sanitized.get(1).getResult();
        EnchantmentStorageMeta cleanBookMeta = (EnchantmentStorageMeta) cleanBook.getItemMeta();
        assertNotNull(cleanBookMeta);
        assertEquals(5, cleanBookMeta.getStoredEnchantLevel(Enchantment.EFFICIENCY));
        assertEquals(1, cleanBookMeta.getStoredEnchantLevel(Enchantment.MENDING));

        assertEquals(Material.BREAD, sanitized.get(2).getResult().getType());
        assertFalse(listener.hasIllegalEnchantments(cleanPick));
        assertFalse(listener.hasIllegalEnchantments(cleanBook));
    }

    @Test
    void sanitizeRecipesRemovesPureNonVanillaBookSlimefunAndStripsMixedCustomEnchants() {
        ItemStack customOnlyBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta customBookMeta = (EnchantmentStorageMeta) customOnlyBook.getItemMeta();
        customBookMeta.addStoredEnchant(customEnchant, 1, true);
        customOnlyBook.setItemMeta(customBookMeta);

        ItemStack mixedSword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta swordMeta = mixedSword.getItemMeta();
        swordMeta.addEnchant(Enchantment.SHARPNESS, 5, true);
        swordMeta.addEnchant(customEnchant, 2, true);
        mixedSword.setItemMeta(swordMeta);

        ItemStack slimefunItem = new ItemStack(Material.EMERALD);
        ItemMeta sfMeta = slimefunItem.getItemMeta();
        sfMeta.getPersistentDataContainer().set(
                NamespacedKey.fromString("slimefun:slimefun_item"),
                PersistentDataType.STRING,
                "MAGIC_SUGAR"
        );
        slimefunItem.setItemMeta(sfMeta);

        ItemStack validBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta validBookMeta = (EnchantmentStorageMeta) validBook.getItemMeta();
        validBookMeta.addStoredEnchant(Enchantment.MENDING, 1, true);
        validBook.setItemMeta(validBookMeta);

        Merchant villager = clasicoWorld.spawn(clasicoWorld.getSpawnLocation(), Villager.class);
        villager.setRecipes(List.of(
                recipeWith(customOnlyBook),
                recipeWith(mixedSword),
                recipeWith(slimefunItem),
                recipeWith(validBook)
        ));

        assertTrue(listener.sanitizeRecipes(villager));
        List<MerchantRecipe> clean = villager.getRecipes();
        assertEquals(2, clean.size(), "El libro solo no-vanilla y el trade de Slimefun deben eliminarse");

        ItemStack sanitizedSword = clean.get(0).getResult();
        assertEquals(Material.DIAMOND_SWORD, sanitizedSword.getType());
        assertEquals(5, sanitizedSword.getEnchantmentLevel(Enchantment.SHARPNESS));
        assertFalse(sanitizedSword.containsEnchantment(customEnchant));

        ItemStack keptBook = clean.get(1).getResult();
        EnchantmentStorageMeta keptMeta = (EnchantmentStorageMeta) keptBook.getItemMeta();
        assertEquals(1, keptMeta.getStoredEnchantLevel(Enchantment.MENDING));
    }

    @Test
    void onVillagerAcquireTradeClampsAndCancelsInClasicoOnly() {
        VillagerMock clasicoVillager = (VillagerMock) clasicoWorld.spawn(clasicoWorld.getSpawnLocation(), Villager.class);

        ItemStack overBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta overMeta = (EnchantmentStorageMeta) overBook.getItemMeta();
        overMeta.addStoredEnchant(Enchantment.EFFICIENCY, 7, true);
        overBook.setItemMeta(overMeta);

        VillagerAcquireTradeEvent clampEvent = new VillagerAcquireTradeEvent(clasicoVillager, recipeWith(overBook));
        listener.onVillagerAcquireTrade(clampEvent);
        assertFalse(clampEvent.isCancelled());
        EnchantmentStorageMeta acquiredMeta = (EnchantmentStorageMeta) clampEvent.getRecipe().getResult().getItemMeta();
        assertEquals(5, acquiredMeta.getStoredEnchantLevel(Enchantment.EFFICIENCY));

        ItemStack customBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta customMeta = (EnchantmentStorageMeta) customBook.getItemMeta();
        customMeta.addStoredEnchant(customEnchant, 1, true);
        customBook.setItemMeta(customMeta);

        VillagerAcquireTradeEvent cancelEvent = new VillagerAcquireTradeEvent(clasicoVillager, recipeWith(customBook));
        listener.onVillagerAcquireTrade(cancelEvent);
        assertTrue(cancelEvent.isCancelled());

        VillagerMock survivalVillager = (VillagerMock) survivalWorld.spawn(survivalWorld.getSpawnLocation(), Villager.class);
        VillagerAcquireTradeEvent survivalEvent = new VillagerAcquireTradeEvent(survivalVillager, recipeWith(overBook));
        listener.onVillagerAcquireTrade(survivalEvent);
        assertFalse(survivalEvent.isCancelled());
        EnchantmentStorageMeta survivalMeta = (EnchantmentStorageMeta) survivalEvent.getRecipe().getResult().getItemMeta();
        assertEquals(7, survivalMeta.getStoredEnchantLevel(Enchantment.EFFICIENCY));
    }

    @Test
    void recognizesClasicoWorldsAndMappedModalities() {
        assertTrue(listener.isClasico(clasicoWorld));
        assertTrue(listener.isClasico(mappedClasicoWorld));
        assertFalse(listener.isClasico(survivalWorld));
        assertFalse(listener.isClasico(null));
    }
}
