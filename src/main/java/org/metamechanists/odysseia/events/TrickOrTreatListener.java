package org.metamechanists.odysseia.events;

import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Truco o trato en octubre: clic derecho con la mano VACÍA sobre una calabaza tallada o un farol de calabaza.
 *
 * <p>Los tratos son golosinas vanilla (nunca el «Dulce de Halloween», que da +2.500 dracmas: inflaría la economía) y XP
 * pequeña. Para que no se pueda cosechar: enfriamiento por jugador y tope diario de usos. Solo funciona en octubre.
 */
public final class TrickOrTreatListener implements Listener {

    public enum Resultado { TRATO, TRUCO }

    private final Plugin plugin;
    private final Random azar = new Random();
    private final Map<UUID, Long> ultimoUso = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> usosDelDia = new ConcurrentHashMap<>(); // {díaDelAño, usos}

    public TrickOrTreatListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Decide trato o truco: con probabilidad {@code probTrato} (0..1) es trato. Pura, para pruebas. */
    static Resultado resolver(double tirada, double probTrato) {
        return tirada < Math.clamp(probTrato, 0.0D, 1.0D) ? Resultado.TRATO : Resultado.TRUCO;
    }

    /** ¿Ha pasado el enfriamiento? Pura, para pruebas. */
    static boolean enfriamientoCumplido(Long ultimo, long ahoraMs, long cooldownMs) {
        return ultimo == null || ahoraMs - ultimo >= cooldownMs;
    }

    /** Registra un uso y dice si aún está dentro del tope diario. Pura respecto al reloj (recibe el día). */
    static boolean dentroDelTope(Map<UUID, int[]> usos, UUID quien, int diaDelAnio, int tope) {
        int[] r = usos.computeIfAbsent(quien, k -> new int[] { diaDelAnio, 0 });
        if (r[0] != diaDelAnio) {
            r[0] = diaDelAnio;
            r[1] = 0;
        }
        if (r[1] >= tope) {
            return false;
        }
        r[1]++;
        return true;
    }

    static boolean esCalabaza(Material m) {
        return m == Material.JACK_O_LANTERN || m == Material.CARVED_PUMPKIN;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void alInteractuar(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) {
            return;
        }
        if (!esCalabaza(e.getClickedBlock().getType()) || !HalloweenPlus.activo(plugin.getConfig())) {
            return;
        }
        Player p = e.getPlayer();
        // Mano vacía y sin agacharse: no estorba a quien coloca bloques o interactúa de otra forma.
        if (p.isSneaking() || e.getItem() != null && e.getItem().getType() != Material.AIR) {
            return;
        }
        long cooldown = Math.max(5L, plugin.getConfig().getLong("halloween-plus.trick-or-treat-cooldown-seconds", 45L)) * 1000L;
        long ahora = System.currentTimeMillis();
        if (!enfriamientoCumplido(ultimoUso.get(p.getUniqueId()), ahora, cooldown)) {
            p.sendMessage(ChatColor.GRAY + "La calabaza aún está dormida... vuelve en un rato.");
            return;
        }
        int tope = Math.max(1, plugin.getConfig().getInt("halloween-plus.trick-or-treat-daily-cap", 12));
        if (!dentroDelTope(usosDelDia, p.getUniqueId(), LocalDate.now(HalloweenPlus.zona(plugin.getConfig())).getDayOfYear(), tope)) {
            p.sendMessage(ChatColor.GRAY + "Las calabazas ya te dieron todo lo que tenían por hoy. Vuelve mañana.");
            return;
        }
        ultimoUso.put(p.getUniqueId(), ahora);
        Resultado r = resolver(azar.nextDouble(), plugin.getConfig().getDouble("halloween-plus.trick-or-treat-treat-chance", 0.7D));
        if (r == Resultado.TRATO) {
            trato(p, e.getClickedBlock().getLocation().add(0.5, 1.1, 0.5));
        } else {
            truco(p);
        }
    }

    private void trato(Player p, Location sobreLaCalabaza) {
        ItemStack premio = switch (azar.nextInt(4)) {
            case 0 -> new ItemStack(Material.PUMPKIN_PIE, 1 + azar.nextInt(2));
            case 1 -> new ItemStack(Material.COOKIE, 2 + azar.nextInt(4));
            case 2 -> new ItemStack(Material.SWEET_BERRIES, 3 + azar.nextInt(4));
            default -> new ItemStack(Material.GLOW_BERRIES, 2 + azar.nextInt(3));
        };
        p.getInventory().addItem(premio).values().forEach(resto -> p.getWorld().dropItemNaturally(p.getLocation(), resto));
        p.giveExp(4 + azar.nextInt(5));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
        p.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, sobreLaCalabaza, 8, 0.3, 0.3, 0.3, 0.0);
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6🎃 &e¡Trato! &7La calabaza te regala una golosina."));
    }

    private void truco(Player p) {
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c🎃 &4¡Truco! &7La calabaza te sonríe con demasiados dientes..."));
        if (azar.nextBoolean()) {
            p.playSound(p.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.2f, 0.7f);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 5, 0, false, false));
        } else {
            HalloweenHorrorEvents.enjambreDeMurcielagos(plugin, p);
        }
    }

    @EventHandler
    public void alSalir(PlayerQuitEvent e) {
        ultimoUso.remove(e.getPlayer().getUniqueId());
    }
}
