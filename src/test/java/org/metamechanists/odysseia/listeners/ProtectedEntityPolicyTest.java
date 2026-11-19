package org.metamechanists.odysseia.listeners;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedEntityPolicyTest {

    @Test
    void villagersAndTamablePetsAreEligible() {
        for (String type : List.of(
                "VILLAGER", "WOLF", "CAT", "PARROT", "OCELOT",
                "HORSE", "DONKEY", "MULE", "SKELETON_HORSE", "ZOMBIE_HORSE",
                "LLAMA", "TRADER_LLAMA", "CAMEL", "TURTLE", "AXOLOTL",
                "FOX", "ALLAY", "FROG", "SNIFFER", "ARMADILLO", "STRIDER")) {
            assertTrue(ProtectedEntityPolicy.isEligibleTypeName(type), "Expected eligible: " + type);
        }
    }

    @Test
    void hostileAndTransientMobsAreNotEligible() {
        for (String type : List.of(
                "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN",
                "WANDERING_TRADER", "ITEM", "ARMOR_STAND", "BAT")) {
            assertFalse(ProtectedEntityPolicy.isEligibleTypeName(type), "Expected ineligible: " + type);
        }
        assertFalse(ProtectedEntityPolicy.isEligibleTypeName(null));
        assertFalse(ProtectedEntityPolicy.isEligibleTypeName("   "));
    }

    @Test
    void protectsVillagerInsideRegionWithOrWithoutNametag() {
        assertEquals(ProtectedEntityPolicy.Action.PROTECT,
                ProtectedEntityPolicy.evaluate(true, true, false, false, false));
        assertEquals(ProtectedEntityPolicy.Action.PROTECT,
                ProtectedEntityPolicy.evaluate(true, true, false, true, false));
    }

    @Test
    void doesNotProtectOutsideRegionAndReleasesPreviouslyManagedOnlyWhenUntamedAndUnnamed() {
        assertEquals(ProtectedEntityPolicy.Action.NO_OP,
                ProtectedEntityPolicy.evaluate(true, false, false, false, false));
        assertEquals(ProtectedEntityPolicy.Action.RELEASE_MANAGED,
                ProtectedEntityPolicy.evaluate(true, false, true, false, false));
        assertEquals(ProtectedEntityPolicy.Action.NO_OP,
                ProtectedEntityPolicy.evaluate(true, false, true, true, false));
        assertEquals(ProtectedEntityPolicy.Action.NO_OP,
                ProtectedEntityPolicy.evaluate(true, false, true, false, true));
    }

    @Test
    void ineligibleEntitiesAreAlwaysNoOp() {
        assertEquals(ProtectedEntityPolicy.Action.NO_OP,
                ProtectedEntityPolicy.evaluate(false, true, false, false, false));
        assertEquals(ProtectedEntityPolicy.Action.NO_OP,
                ProtectedEntityPolicy.evaluate(false, false, true, false, false));
    }

    @Test
    void ignoresGlobalRegionAndRecognizesProtectedRegions() {
        assertFalse(ProtectedEntityPolicy.hasNonGlobalRegion(null));
        assertFalse(ProtectedEntityPolicy.hasNonGlobalRegion(List.of()));
        assertFalse(ProtectedEntityPolicy.hasNonGlobalRegion(List.of("__global__")));
        assertFalse(ProtectedEntityPolicy.hasNonGlobalRegion(List.of("__GLOBAL__")));
        assertTrue(ProtectedEntityPolicy.hasNonGlobalRegion(List.of("__global__", "ps123x64y-45z")));
        assertTrue(ProtectedEntityPolicy.hasNonGlobalRegion(List.of("spawn_survival")));
    }
}
