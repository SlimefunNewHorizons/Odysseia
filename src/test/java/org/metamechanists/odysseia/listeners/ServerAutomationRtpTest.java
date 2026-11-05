package org.metamechanists.odysseia.listeners;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metamechanists.odysseia.Odysseia;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServerAutomationRtpTest {
    @TempDir Path data;
    ServerAutomationListener listener;
    YamlConfiguration config;
    Player player;
    World world;
    WorldBorder border;

    @BeforeEach void setup() {
        MockBukkit.mock();
        config = new YamlConfiguration();
        Odysseia plugin = mock(Odysseia.class);
        when(plugin.getName()).thenReturn("Odysseia");
        when(plugin.namespace()).thenReturn("odysseia");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getConfig()).thenReturn(config);
        listener = new ServerAutomationListener(plugin);
        player = mock(Player.class);
        when(player.getServer()).thenReturn(Bukkit.getServer());
        world = mock(World.class);
        border = mock(WorldBorder.class);
        when(player.getName()).thenReturn(".RtpFixture");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("clasico");
        when(world.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(world, 0, 0, 0));
        when(border.getSize()).thenReturn(20_000.0);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private List<String> commands(String input, boolean cancelled) {
        var output = new ArrayList<String>();
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.dispatchCommand(any(CommandSender.class), anyString()))
                    .thenAnswer(call -> { output.add(call.getArgument(1)); return true; });
            bukkit.when(() -> Bukkit.getPlayerExact(".RtpFixture")).thenReturn(player);
            var event = new PlayerCommandPreprocessEvent(player, input);
            listener.onBedrockRtp(event);
            assertEquals(cancelled, event.isCancelled());
        }
        return output;
    }

    @Test void clasicoBedrockAliasesStayInClasicoAndInsideItsBorder() {
        for (String label : List.of("rtp", "wild", "wildrtp:rtp", "wildrtp:wild", "wildrtp:wildrtp")) {
            assertEquals(List.of("customrtp .RtpFixture clasico 4750 9500 0 0"), commands("/" + label, true));
        }
    }

    @Test void clasicoNetherKeepsItsWorldAndCoordinateLimit() {
        when(world.getName()).thenReturn("clasico_nether");
        when(border.getSize()).thenReturn(60_000_000.0);
        assertEquals(List.of("customrtp .RtpFixture clasico_nether 5000 18750 0 0"), commands("/rtp", true));
    }

    @Test void shiftedBorderReducesBothBounds() {
        when(border.getCenter()).thenReturn(new Location(world, -2_250.2, 0, 1_000));
        assertEquals(List.of("customrtp .RtpFixture clasico 3624 7249 0 0"), commands("/rtp", true));
    }

    @Test void unsupportedWorldAndUnsafeBorderNeverDispatch() {
        when(world.getName()).thenReturn("bskyblock_world");
        assertTrue(commands("/rtp", true).isEmpty());
        when(world.getName()).thenReturn("clasico");
        when(border.getSize()).thenReturn(1_000.0);
        assertTrue(commands("/rtp", true).isEmpty());
    }

    @Test void switchDisablesGuardedRtpWithoutDispatching() {
        config.set("bedrock-guard.rtp-enabled", false);
        assertTrue(commands("/rtp", true).isEmpty());
    }

    @Test void javaAndUnrelatedCommandsKeepTheirNormalHandlers() {
        when(player.getName()).thenReturn("JavaFixture");
        assertTrue(commands("/rtp", false).isEmpty());
        assertTrue(commands("/spawn", false).isEmpty());
    }

    @Test void staffRtpUsesTheSameSafeRangeForASmallMainWorld() {
        when(player.hasPermission("odysseia.rtp.others")).thenReturn(true);
        when(world.getName()).thenReturn("world");
        when(border.getSize()).thenReturn(2_000.0);
        assertEquals(List.of("customrtp .RtpFixture world 250 500 0 0"), commands("/rtp .RtpFixture", true));
    }

    @Test void unauthorizedStaffSyntaxDoesNotDispatchOrChangePermissions() {
        assertTrue(commands("/rtp .RtpFixture", false).isEmpty());
    }
}
