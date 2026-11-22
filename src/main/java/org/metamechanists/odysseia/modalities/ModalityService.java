package org.metamechanists.odysseia.modalities;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resuelve a que modalidad pertenece cada mundo y expone la lista para el menu /modalidades.
 *
 * La modalidad base es Survival: cualquier mundo que no calce con una modalidad declarada
 * cae ahi. Eso mantiene el comportamiento actual del servidor (mundos de galactifun, arenas
 * de jefes, hub, limbo) sin tener que enumerarlos.
 */
public final class ModalityService {

    /** Modalidad a la que caen los mundos no declarados. */
    public static final String FALLBACK_ID = "survival";

    private final JavaPlugin plugin;
    private final List<Modality> modalities = new ArrayList<>();
    private Modality fallback;

    public ModalityService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        modalities.clear();
        fallback = null;
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("modalidades.modos");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection node = root.getConfigurationSection(id);
                if (node == null) continue;
                Modality modality = new Modality(
                        id.toLowerCase(Locale.ROOT),
                        node.getString("nombre", id),
                        node.getString("descripcion", ""),
                        node.getString("icono", "GRASS_BLOCK"),
                        node.getString("comando", ""),
                        node.getStringList("mundos"));
                modalities.add(modality);
                if (modality.id().equals(FALLBACK_ID)) fallback = modality;
            }
        }
        if (fallback == null) {
            // Sin configuracion valida seguimos funcionando: todo es survival.
            fallback = new Modality(FALLBACK_ID, "&aSurvival", "Modalidad principal.",
                    "GRASS_BLOCK", "survival", List.of());
            modalities.add(fallback);
        }
        plugin.getLogger().info("[Modalidades] " + modalities.size() + " modalidades cargadas.");
    }

    /** Modalidades declaradas, en el orden del config. */
    public List<Modality> modalities() {
        return List.copyOf(modalities);
    }

    /** Modalidad del mundo indicado; nunca devuelve null. */
    public Modality resolve(String worldName) {
        for (Modality modality : modalities) {
            if (modality != fallback && modality.matches(worldName)) return modality;
        }
        return fallback;
    }

    /** Modalidad en la que se encuentra el jugador. */
    public Modality resolve(Player player) {
        return resolve(player.getWorld().getName());
    }

    /** True si el jugador esta fuera de la modalidad base (es decir, en una isla). */
    public boolean isIsland(Player player) {
        return !resolve(player).id().equals(FALLBACK_ID);
    }

    /** True si el mundo pertenece a la modalidad Clásico (por prefijo o por configuración). */
    public static boolean isClasico(ModalityService modalityService, World world) {
        if (world == null) return false;
        String name = world.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("clasico")) return true;
        return modalityService != null && "clasico".equalsIgnoreCase(modalityService.resolve(world.getName()).id());
    }
}
