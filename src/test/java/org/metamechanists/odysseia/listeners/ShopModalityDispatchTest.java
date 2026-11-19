package org.metamechanists.odysseia.listeners;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.menus.ShopMenuService;
import org.metamechanists.odysseia.modalities.ModalityService;
import org.metamechanists.odysseia.vaults.ModalityVaultService;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises actual menu clicks and command dispatch with the packaged modality rules. */
class ShopModalityDispatchTest {
    private ServerMock server;
    private PlayerMock player;
    private ShopMenuService shop;
    private final List<String> dispatched = new ArrayList<>();

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        player.setOp(false);
        var plugin = mock(Odysseia.class);
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("shop-modality-test"));
        var guard = new ModalityStorageGuardListener(plugin, new ModalityService(plugin),
                mock(ModalityVaultService.class));
        when(plugin.getModalityStorageGuard()).thenReturn(guard);
        shop = new ShopMenuService(plugin);
        for (String name : List.of("tiendaprot", "sfmercado", "ultimateshop", "ca")) {
            server.getCommandMap().register("smoke", new Command(name) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    assertSame(player, sender);
                    assertTrue(StoreCommandGuardListener.isInternalDispatch(player.getUniqueId()));
                    dispatched.add(name + "@" + player.getWorld().getName());
                    return true;
                }
            });
        }
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @ParameterizedTest(name = "{0}: slot {1} -> {2}, allowed={3}")
    @CsvSource({
            "world, 10, tiendaprot, true", "clasico, 10, tiendaprot, true",
            "clasico_nether, 10, tiendaprot, true", "clasico_the_end, 10, tiendaprot, true",
            "bskyblock_world, 10, tiendaprot, false", "oneblock_world, 10, tiendaprot, false",
            "onechunk_world, 10, tiendaprot, false", "laboratorio, 10, tiendaprot, false",
            "world, 14, sfmercado, true", "clasico, 14, sfmercado, false",
            "clasico_nether, 14, sfmercado, false", "clasico_the_end, 14, sfmercado, false",
            "bskyblock_world, 14, sfmercado, true", "oneblock_world, 14, sfmercado, true",
            "onechunk_world, 14, sfmercado, false", "laboratorio, 14, sfmercado, false",
            "world, 28, ultimateshop, true", "clasico, 28, ultimateshop, true",
            "bskyblock_world, 28, ultimateshop, true", "oneblock_world, 28, ultimateshop, true",
            "onechunk_world, 28, ultimateshop, true", "laboratorio, 28, ultimateshop, false",
            "world, 12, ca, true", "clasico, 12, ca, true",
            "bskyblock_world, 12, ca, true", "oneblock_world, 12, ca, true",
            "onechunk_world, 12, ca, true", "laboratorio, 12, ca, false"
    })
    void menuHonorsModalityBeforeDispatch(String world, int slot, String command, boolean allowed) {
        var target = server.getWorld(world);
        if (target == null) target = server.addSimpleWorld(world);
        player.teleport(target.getSpawnLocation());
        shop.onCommand(player, null, "tienda", new String[0]);
        var click = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);

        shop.onClick(click);

        assertTrue(click.isCancelled(), "Menu items must never be taken into the player inventory");
        assertEquals(allowed ? List.of(command + "@" + world) : List.of(), dispatched);
        assertEquals(world, player.getWorld().getName());
        assertFalse(StoreCommandGuardListener.isInternalDispatch(player.getUniqueId()));
        assertTrue(player.getInventory().isEmpty());
    }
}
