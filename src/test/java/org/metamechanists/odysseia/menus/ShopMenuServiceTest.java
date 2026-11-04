package org.metamechanists.odysseia.menus;

import org.bukkit.command.CommandException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopMenuServiceTest {

    @Test
    void dispatchSafelyCatchesCommandExceptionAndLogsWarning() {
        List<String> warnings = new ArrayList<>();

        boolean ok = ShopMenuService.dispatchSafely(
                () -> {
                    throw new CommandException("Unhandled exception executing 'pw'");
                },
                warnings::add,
                "JackStar",
                "pw"
        );

        assertFalse(ok);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("pw"));
        assertTrue(warnings.getFirst().contains("JackStar"));
    }

    @Test
    void dispatchSafelyCatchesIllegalArgumentExceptionFromDownstreamCommand() {
        List<String> warnings = new ArrayList<>();

        boolean ok = ShopMenuService.dispatchSafely(
                () -> {
                    throw new IllegalArgumentException("WITHER_SKELETON_WALL_SKULL isn't an item");
                },
                warnings::add,
                "JackStar",
                "pwarps"
        );

        assertFalse(ok);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("WITHER_SKELETON_WALL_SKULL"));
    }

    @Test
    void dispatchSafelyReturnsTrueWhenCommandSucceeds() {
        List<String> warnings = new ArrayList<>();

        boolean ok = ShopMenuService.dispatchSafely(
                () -> {
                },
                warnings::add,
                "JackStar",
                "tienda"
        );

        assertTrue(ok);
        assertTrue(warnings.isEmpty());
    }
}
