package org.metamechanists.odysseia.purchase;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.modalities.ModalityService;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KitDeliveryServiceClasicoTest {
    private ServerMock server;
    private WorldMock survivalWorld;
    private WorldMock clasicoWorld;
    private WorldMock mappedClasicoWorld;
    private PlayerMock player;
    private YamlConfiguration config;
    private Odysseia plugin;
    private KitDeliveryService service;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        survivalWorld = server.addSimpleWorld("world");
        clasicoWorld = server.addSimpleWorld("clasico");
        mappedClasicoWorld = server.addSimpleWorld("retro_realm");
        player = server.addPlayer();

        config = new YamlConfiguration();
        config.set("modalidades.modos.survival.mundos", List.of("world"));
        config.set("modalidades.modos.clasico.mundos", List.of("clasico", "retro_realm"));

        config.set("kits.minero.permission", "odysseia.kit.minero");
        config.set("kits.minero.vanilla-items", List.of(
                Map.of(
                        "material", "DIAMOND_PICKAXE",
                        "name", "&fPico del Minero",
                        "enchantments", Map.of(
                                "efficiency", 6,
                                "unbreaking", 8,
                                "mending", 1,
                                "excellentenchants:telepathy", 1
                        )
                ),
                Map.of("slimefun-item", "INFUSED_MAGNET")
        ));

        config.set("kits.survival_only_over.permission", "odysseia.kit.over");
        config.set("kits.survival_only_over.vanilla-items", List.of(
                Map.of(
                        "material", "DIAMOND_PICKAXE",
                        "enchantments", Map.of(
                                "efficiency", 6,
                                "unbreaking", 8
                        )
                )
        ));

        config.set("kits.con_clasico.permission", "odysseia.kit.conclasico");
        config.set("kits.con_clasico.vanilla-items", List.of(
                Map.of(
                        "material", "DIAMOND_PICKAXE",
                        "enchantments", Map.of("efficiency", 6)
                ),
                Map.of("slimefun-item", "COOLER")
        ));
        config.set("kits.con_clasico.clasico-items", List.of(
                Map.of(
                        "material", "IRON_PICKAXE",
                        "enchantments", Map.of(
                                "efficiency", 7,
                                "unbreaking", 5
                        )
                ),
                Map.of(
                        "material", "COOKED_BEEF",
                        "amount", 16
                )
        ));

        plugin = mock(Odysseia.class);
        when(plugin.getName()).thenReturn("Odysseia");
        when(plugin.namespace()).thenReturn("odysseia");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("kit-clasico-test"));
        ModalityService modalities = new ModalityService(plugin);
        when(plugin.getModalityService()).thenReturn(modalities);

        service = new KitDeliveryService(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void clasicoOmitsSlimefunClampsEnchantmentsAndDropsCustomEnchants() {
        player.teleport(clasicoWorld.getSpawnLocation());
        ActionResult result = service.deliver(player, "minero", "txn-clasico-1", false);

        assertEquals(ActionResult.Status.COMPLETED, result.status());
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<ItemStack> delivered = java.util.Arrays.stream(contents)
                .filter(item -> item != null && !item.getType().isAir())
                .toList();
        assertEquals(1, delivered.size(), "El item de Slimefun debe omitirse en Clasico");

        ItemStack pickaxe = delivered.getFirst();
        assertEquals(Material.DIAMOND_PICKAXE, pickaxe.getType());
        assertEquals(5, pickaxe.getEnchantmentLevel(Enchantment.EFFICIENCY));
        assertEquals(3, pickaxe.getEnchantmentLevel(Enchantment.UNBREAKING));
        assertEquals(1, pickaxe.getEnchantmentLevel(Enchantment.MENDING));
        assertTrue(pickaxe.getEnchantments().keySet().stream()
                .allMatch(ench -> NamespacedKey.MINECRAFT.equalsIgnoreCase(ench.getKey().getNamespace())));

        String notice = player.nextMessage();
        assertNotNull(notice, "Debe avisar al jugador que los items de Slimefun fueron omitidos");
        assertTrue(notice.toLowerCase().contains("slimefun"));
    }

    @Test
    void clasicoUsesExplicitClasicoItemsListWhenConfigured() {
        player.teleport(clasicoWorld.getSpawnLocation());
        ActionResult result = service.deliver(player, "con_clasico", "txn-clasico-2", false);

        assertEquals(ActionResult.Status.COMPLETED, result.status());
        ItemStack pickaxe = player.getInventory().getItem(0);
        ItemStack beef = player.getInventory().getItem(1);
        assertNotNull(pickaxe);
        assertNotNull(beef);
        assertEquals(Material.IRON_PICKAXE, pickaxe.getType());
        assertEquals(5, pickaxe.getEnchantmentLevel(Enchantment.EFFICIENCY));
        assertEquals(3, pickaxe.getEnchantmentLevel(Enchantment.UNBREAKING));
        assertEquals(Material.COOKED_BEEF, beef.getType());
        assertEquals(16, beef.getAmount());
    }

    @Test
    void clasicoResolvedViaModalityServiceStillSanitizesWhileSurvivalPreservesLevels() {
        player.teleport(mappedClasicoWorld.getSpawnLocation());
        ActionResult clasicoResult = service.deliver(player, "minero", "txn-mapped-1", false);
        assertEquals(ActionResult.Status.COMPLETED, clasicoResult.status());
        ItemStack clasicoPick = player.getInventory().getItem(0);
        assertNotNull(clasicoPick);
        assertEquals(5, clasicoPick.getEnchantmentLevel(Enchantment.EFFICIENCY));
        assertEquals(3, clasicoPick.getEnchantmentLevel(Enchantment.UNBREAKING));

        player.getInventory().clear();
        player.teleport(survivalWorld.getSpawnLocation());
        ActionResult survivalResult = service.deliver(player, "survival_only_over", "txn-survival-1", false);
        assertEquals(ActionResult.Status.COMPLETED, survivalResult.status());
        ItemStack survivalPick = player.getInventory().getItem(0);
        assertNotNull(survivalPick);
        assertEquals(6, survivalPick.getEnchantmentLevel(Enchantment.EFFICIENCY));
        assertEquals(8, survivalPick.getEnchantmentLevel(Enchantment.UNBREAKING));
        assertNotNull(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency")));
    }

    @Test
    void validateConfigurationRejectsSlimefunInsideClasicoItems() {
        config.set("kits.bad_clasico.permission", "odysseia.kit.bad");
        config.set("kits.bad_clasico.vanilla-items", List.of(Map.of("material", "STONE")));
        config.set("kits.bad_clasico.clasico-items", List.of(Map.of("slimefun-item", "COOLER")));

        List<String> errors = service.validateConfiguration();
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(err -> err.contains("clasico-items")));
    }

    @Test
    void clasicoDoesNotAbortKitWhenOnlySlimefunItemsExistAndRespectsFullInventory() {
        config.set("kits.only_sf.permission", "odysseia.kit.onlysf");
        config.set("kits.only_sf.vanilla-items", List.of(Map.of("slimefun-item", "COOLER")));
        player.teleport(clasicoWorld.getSpawnLocation());

        ActionResult onlySfResult = service.deliver(player, "only_sf", "txn-onlysf", false);
        assertEquals(ActionResult.Status.COMPLETED, onlySfResult.status());
        String notice = player.nextMessage();
        assertNotNull(notice);
        assertTrue(notice.toLowerCase().contains("slimefun"));

        // Inventario lleno debe devolver WAITING sin enviar aviso prematuro.
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 1));
        }
        ActionResult fullResult = service.deliver(player, "minero", "txn-full", false);
        assertEquals(ActionResult.Status.WAITING_FOR_PLAYER, fullResult.status());
        assertEquals(null, player.nextMessage());
    }
}
