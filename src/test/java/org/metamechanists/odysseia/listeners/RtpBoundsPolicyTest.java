package org.metamechanists.odysseia.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RtpBoundsPolicyTest {
    @Test void borderOverridesTheFormerHundredThousandBlockRange() {
        assertEquals(9_500, RtpBoundsPolicy.maximum(150_000, 20_000, 0, 0));
    }

    @Test void bedrockLimitsStillApplyToLargeBordersAndNether() {
        assertEquals(150_000, RtpBoundsPolicy.maximum(150_000, 60_000_000, 0, 0));
        assertEquals(18_750, RtpBoundsPolicy.maximum(18_750, 60_000_000, 0, 0));
    }

    @Test void displacedAndFractionalBordersAreRoundedInward() {
        assertEquals(7_249, RtpBoundsPolicy.maximum(150_000, 20_000, -2_250.2, 1_000));
        assertEquals(7_249, RtpBoundsPolicy.maximum(150_000, 20_000, 1_000, 2_250.2));
    }

    @Test void aSmallOrDistantBorderDoesNotForceAnUnsafeMinimum() {
        assertEquals(0, RtpBoundsPolicy.maximum(150_000, 1_000, 0, 0));
        assertEquals(0, RtpBoundsPolicy.maximum(150_000, 20_000, 20_000, 0));
        assertEquals(1, RtpBoundsPolicy.maximum(150_000, 1_002, 0, 0));
    }

    @Test void invalidBoundsFailClosed() {
        assertEquals(0, RtpBoundsPolicy.maximum(-1, 20_000, 0, 0));
        assertEquals(0, RtpBoundsPolicy.maximum(150_000, Double.NaN, 0, 0));
        assertEquals(0, RtpBoundsPolicy.maximum(150_000, 20_000, Double.POSITIVE_INFINITY, 0));
    }

    @Test void onlySurvivalWorldsAllowGuardedRtp() {
        for (String name : new String[]{"world", "world_nether", "clasico", "CLASICO_NETHER"}) {
            assertTrue(RtpBoundsPolicy.supportsWorld(name));
        }
        for (String name : new String[]{"lobby", "world_the_end", "bskyblock_world", "oneblock_world", "laboratorio"}) {
            assertFalse(RtpBoundsPolicy.supportsWorld(name));
        }
    }
}
