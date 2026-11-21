package org.metamechanists.odysseia.listeners;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossItemListenerKratosDupeTest {

    @Test
    void cancelsSwapOffhandFromAnySlotWhenOffhandHoldsTempKratosBlade() {
        assertTrue(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.SWAP_OFFHAND, 0, -1, false, false, true, false));
    }

    @Test
    void cancelsNumberKeyIntoOffhandSlotWhenOffhandHoldsTempKratosBlade() {
        assertTrue(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.NUMBER_KEY, 40, 2, false, false, true, false));
    }

    @Test
    void cancelsAnyClickTouchingTempKratosItemInCurrentCursorOrHotbar() {
        assertTrue(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.LEFT, 12, -1, true, false, false, false));
        assertTrue(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.LEFT, 12, -1, false, true, false, false));
        assertTrue(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.NUMBER_KEY, 12, 1, false, false, false, true));
    }

    @Test
    void allowsNormalClicksUnrelatedToTempKratosBlade() {
        assertFalse(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.LEFT, 0, -1, false, false, true, false));
        assertFalse(BossItemListener.shouldCancelKratosInventoryClick(
                ClickType.SWAP_OFFHAND, 0, -1, false, false, false, false));
    }
}
