package org.metamechanists.odysseia.listeners;

import java.util.Locale;
import java.util.Set;

/** Bounds for customrtp's origin-centered search, without loading any chunks. */
final class RtpBoundsPolicy {
    private static final Set<String> WORLDS = Set.of("world", "world_nether", "clasico", "clasico_nether");
    private static final double BORDER_MARGIN = 500.0;

    private RtpBoundsPolicy() { }

    static boolean supportsWorld(String name) {
        return WORLDS.contains(name.toLowerCase(Locale.ROOT));
    }

    static int maximum(int coordinateLimit, double borderSize, double centerX, double centerZ) {
        if (coordinateLimit <= 0 || !Double.isFinite(borderSize) || borderSize <= 0
                || !Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            return 0;
        }
        // Every point in [-maximum, maximum]^2 must fit both coordinate limits
        // and the actual border. A displaced border can exclude the origin.
        double available = borderSize / 2.0 - Math.max(Math.abs(centerX), Math.abs(centerZ)) - BORDER_MARGIN;
        return (int) Math.max(0.0, Math.min(coordinateLimit, Math.floor(available)));
    }
}
