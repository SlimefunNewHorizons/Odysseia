package org.metamechanists.odysseia.listeners;

import java.util.Locale;
import java.util.Set;

/**
 * Politica pura de persistencia de aldeanos y mascotas dentro de regiones protegidas
 * de WorldGuard y ProtectionStones.
 *
 * <p>Regla: SOLO las entidades elegibles (aldeanos con o sin nametag, y mascotas/animales
 * domesticables) ubicadas dentro de una region protegida no-global reciben persistencia
 * garantizada (sin despawn ni limpieza automatica). Fuera de regiones protegidas no se
 * fuerza persistencia, y si una entidad previamente marcada por Odysseia sale de la region
 * sin estar domesticada ni renombrada, se libera la marca para evitar acumulacion en wilderness.
 */
public final class ProtectedEntityPolicy {

    public enum Action {
        PROTECT,
        RELEASE_MANAGED,
        NO_OP
    }

    private static final Set<String> PROTECTED_TYPE_NAMES = Set.of(
            "VILLAGER",
            "WOLF",
            "CAT",
            "PARROT",
            "OCELOT",
            "HORSE",
            "DONKEY",
            "MULE",
            "SKELETON_HORSE",
            "ZOMBIE_HORSE",
            "LLAMA",
            "TRADER_LLAMA",
            "CAMEL",
            "TURTLE",
            "AXOLOTL",
            "FOX",
            "ALLAY",
            "FROG",
            "SNIFFER",
            "ARMADILLO",
            "STRIDER",
            "HAPPY_GHAST"
    );

    private ProtectedEntityPolicy() {
    }

    public static boolean isEligibleTypeName(String entityTypeName) {
        if (entityTypeName == null || entityTypeName.isBlank()) {
            return false;
        }
        return PROTECTED_TYPE_NAMES.contains(entityTypeName.trim().toUpperCase(Locale.ROOT));
    }

    public static Action evaluate(
            boolean eligible,
            boolean inProtectedRegion,
            boolean managedByOdysseia,
            boolean hasCustomName,
            boolean isTamed) {
        if (!eligible) {
            return Action.NO_OP;
        }
        if (inProtectedRegion) {
            return Action.PROTECT;
        }
        if (managedByOdysseia && !hasCustomName && !isTamed) {
            return Action.RELEASE_MANAGED;
        }
        return Action.NO_OP;
    }

    public static boolean hasNonGlobalRegion(Iterable<String> regionIds) {
        if (regionIds == null) {
            return false;
        }
        for (String id : regionIds) {
            if (id != null && !id.isBlank() && !"__global__".equalsIgnoreCase(id.trim())) {
                return true;
            }
        }
        return false;
    }
}
