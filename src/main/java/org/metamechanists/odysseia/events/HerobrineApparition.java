package org.metamechanists.odysseia.events;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;

/**
 * Aparición de Herobrine con SU skin real (la de la cuenta oficial «Herobrine», firmada por Mojang y guardada en
 * {@code halloween/herobrine-skin.json}: no depende de consultar a Mojang en directo).
 *
 * <p>Es un maniquí inmóvil, invulnerable, silencioso y NO persistente (no se guarda con el chunk). Te mira desde lejos y
 * <b>se esfuma si lo miras fijamente unos instantes, si te acercas, o a los segundos</b>. No hace daño.
 */
public final class HerobrineApparition {

    static final String RECURSO = "halloween/herobrine-skin.json";
    /** Marca PDC para limpiar maniquíes huérfanos tras un cierre brusco. */
    private static final String MARCA = "herobrine_apparition";

    /** Ticks de mirada fija antes de esfumarse (10 ticks = 0,5 s). */
    static final int TICKS_MIRADA = 12;
    static final double DISTANCIA_MINIMA = 5.0D;
    static final double DISTANCIA_APARICION = 14.0D;
    static final int DURACION_MAXIMA_TICKS = 20 * 22;

    private static volatile ProfileProperty textura;
    private static final Map<UUID, BukkitTask> ACTIVAS = new ConcurrentHashMap<>();

    private HerobrineApparition() {
    }

    /** Carga (una vez) la textura firmada desde el recurso. Devuelve null si falta o está corrupta. */
    static ProfileProperty textura() {
        ProfileProperty t = textura;
        if (t != null) {
            return t;
        }
        try (InputStream in = HerobrineApparition.class.getClassLoader().getResourceAsStream(RECURSO)) {
            if (in == null) {
                return null;
            }
            JsonObject json = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            t = new ProfileProperty("textures", json.get("value").getAsString(), json.get("signature").getAsString());
            textura = t;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ¿Está el jugador mirando (con el cursor) hacia ese punto? {@code umbralCoseno} 0.985 ≈ 10° de margen.
     * Función pura para poder probarla.
     */
    static boolean mirandoHacia(Vector direccionMirada, Vector desdeOjosHastaObjetivo, double umbralCoseno) {
        if (desdeOjosHastaObjetivo.lengthSquared() < 1.0E-6 || direccionMirada.lengthSquared() < 1.0E-6) {
            return false;
        }
        return direccionMirada.clone().normalize().dot(desdeOjosHastaObjetivo.clone().normalize()) >= umbralCoseno;
    }

    /** Punto de aparición: ~14 bloques en un ángulo aleatorio del semicírculo frontal, sobre el suelo. Null si no es seguro. */
    static Location puntoDeAparicion(Player jugador, double anguloGrados) {
        World mundo = jugador.getWorld();
        Vector frente = jugador.getLocation().getDirection().setY(0);
        if (frente.lengthSquared() < 1.0E-6) {
            frente = new Vector(0, 0, 1);
        }
        frente.normalize().rotateAroundY(Math.toRadians(anguloGrados)).multiply(DISTANCIA_APARICION);
        int x = jugador.getLocation().getBlockX() + (int) Math.round(frente.getX());
        int z = jugador.getLocation().getBlockZ() + (int) Math.round(frente.getZ());
        if (!mundo.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        int y = mundo.getHighestBlockYAt(x, z);
        var suelo = mundo.getBlockAt(x, y, z);
        if (suelo.isLiquid() || Math.abs(y - jugador.getLocation().getBlockY()) > 10) {
            return null;
        }
        return new Location(mundo, x + 0.5, y + 1.0, z + 0.5);
    }

    /** Hace aparecer a Herobrine para este jugador. Devuelve false si no hay sitio seguro o falta la textura. */
    public static boolean aparecer(Plugin plugin, Player jugador, double anguloGrados) {
        ProfileProperty prop = textura();
        if (prop == null || ACTIVAS.containsKey(jugador.getUniqueId())) {
            return false;
        }
        Location lugar = puntoDeAparicion(jugador, anguloGrados);
        if (lugar == null) {
            return false;
        }
        Mannequin herobrine = lugar.getWorld().spawn(lugar, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile().addProperty(prop).build());
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(true);
            m.setCustomNameVisible(false);
            m.setDescription(Component.empty());
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, MARCA), PersistentDataType.BYTE, (byte) 1);
        });
        orientar(herobrine, jugador);
        lugar.getWorld().spawnParticle(Particle.REVERSE_PORTAL, lugar.clone().add(0, 1, 0), 30, 0.4, 0.9, 0.4, 0.04);
        jugador.playSound(jugador.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1.6f, 0.5f);

        int[] mirada = {0};
        int[] edad = {0};
        BukkitTask tarea = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            edad[0] += 2;
            if (!herobrine.isValid() || !jugador.isOnline() || jugador.getWorld() != herobrine.getWorld()) {
                retirar(jugador.getUniqueId(), herobrine, false);
                return;
            }
            orientar(herobrine, jugador);
            Location ojos = jugador.getEyeLocation();
            Vector haciaEl = herobrine.getEyeLocation().toVector().subtract(ojos.toVector());
            double distancia = haciaEl.length();
            if (mirandoHacia(ojos.getDirection(), haciaEl, 0.985D) && jugador.hasLineOfSight(herobrine)) {
                mirada[0] += 2;
            } else {
                mirada[0] = Math.max(0, mirada[0] - 1);
            }
            if (mirada[0] >= TICKS_MIRADA || distancia <= DISTANCIA_MINIMA || edad[0] >= DURACION_MAXIMA_TICKS) {
                retirar(jugador.getUniqueId(), herobrine, true);
            }
        }, 2L, 2L);
        ACTIVAS.put(jugador.getUniqueId(), tarea);
        return true;
    }

    private static void orientar(Mannequin herobrine, Player jugador) {
        Location l = herobrine.getLocation();
        l.setDirection(jugador.getLocation().toVector().subtract(l.toVector()));
        herobrine.setRotation(l.getYaw(), l.getPitch());
    }

    private static void retirar(UUID quien, Mannequin herobrine, boolean conEfecto) {
        BukkitTask t = ACTIVAS.remove(quien);
        if (t != null) {
            t.cancel();
        }
        if (herobrine.isValid()) {
            if (conEfecto) {
                Location l = herobrine.getLocation().add(0, 1, 0);
                l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 30, 0.3, 0.8, 0.3, 0.03);
                l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.4f);
            }
            herobrine.remove();
        }
    }

    /** Al apagar el plugin o el 1 de noviembre: quita todas las apariciones y las tareas. */
    public static void limpiar() {
        for (Map.Entry<UUID, BukkitTask> e : Set.copyOf(ACTIVAS.entrySet())) {
            e.getValue().cancel();
        }
        ACTIVAS.clear();
    }

    /** Elimina maniquíes de Herobrine que hubieran quedado en el mundo (cierre brusco). Devuelve cuántos. */
    public static int barrerHuerfanos(Plugin plugin) {
        NamespacedKey clave = new NamespacedKey(plugin, MARCA);
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (m.getPersistentDataContainer().has(clave, PersistentDataType.BYTE)) {
                    m.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
