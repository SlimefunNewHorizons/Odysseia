package org.metamechanists.odysseia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class TrickOrTreatListenerTest {

    private ServerMock server;
    private WorldMock mundo;
    private YamlConfiguration config;
    private TrickOrTreatListener listener;

    @BeforeEach
    void preparar() {
        server = MockBukkit.mock();
        mundo = server.addSimpleWorld("world");
        config = new YamlConfiguration();
        config.set("halloween-plus.trick-or-treat-treat-chance", 1.0D); // siempre trato: resultado determinista
        config.set("halloween-plus.trick-or-treat-cooldown-seconds", 45);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        listener = new TrickOrTreatListener(plugin);
        HalloweenPlus.forzar(true);
    }

    @AfterEach
    void limpiar() {
        HalloweenPlus.forzar(null);
        MockBukkit.unmock();
    }

    private PlayerInteractEvent clic(PlayerMock p, Material bloque, ItemStack enMano) {
        Block b = mundo.getBlockAt(5, 64, 5);
        b.setType(bloque);
        return new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, enMano, b, BlockFace.UP, EquipmentSlot.HAND);
    }

    private int golosinas(PlayerMock p) {
        int n = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null && i.getType() != Material.AIR) {
                n += i.getAmount();
            }
        }
        return n;
    }

    @Test
    void conLaManoVaciaSobreUnFarolDeCalabazaDaUnaGolosina() {
        PlayerMock p = server.addPlayer();
        listener.alInteractuar(clic(p, Material.JACK_O_LANTERN, null));
        assertTrue(golosinas(p) > 0, "el trato entrega una golosina vanilla");
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null) {
                assertFalse(i.hasItemMeta() && i.getItemMeta().hasDisplayName(),
                        "nunca el Dulce de Halloween (+2.500 dracmas): solo golosinas vanilla");
            }
        }
    }

    @Test
    void elSegundoClicDentroDelEnfriamientoNoDaNada() {
        PlayerMock p = server.addPlayer();
        listener.alInteractuar(clic(p, Material.CARVED_PUMPKIN, null));
        int tras1 = golosinas(p);
        listener.alInteractuar(clic(p, Material.CARVED_PUMPKIN, null));
        assertEquals(tras1, golosinas(p), "no se puede cosechar la calabaza");
    }

    @Test
    void conUnBloqueEnLaManoNoSeInterpone() {
        PlayerMock p = server.addPlayer();
        listener.alInteractuar(clic(p, Material.JACK_O_LANTERN, new ItemStack(Material.STONE)));
        assertEquals(0, golosinas(p), "colocar un bloque contra la calabaza no activa el truco o trato");
    }

    @Test
    void agachadoNoSeActiva() {
        PlayerMock p = server.addPlayer();
        p.setSneaking(true);
        listener.alInteractuar(clic(p, Material.JACK_O_LANTERN, null));
        assertEquals(0, golosinas(p));
    }

    @Test
    void otroBloqueNoCuenta() {
        PlayerMock p = server.addPlayer();
        listener.alInteractuar(clic(p, Material.PUMPKIN, null));
        assertEquals(0, golosinas(p));
    }

    @Test
    void fueraDeOctubreNoHaceNada() {
        HalloweenPlus.forzar(false);
        PlayerMock p = server.addPlayer();
        listener.alInteractuar(clic(p, Material.JACK_O_LANTERN, null));
        assertEquals(0, golosinas(p), "el 1 de noviembre las calabazas vuelven a ser solo decoración");
    }
}
