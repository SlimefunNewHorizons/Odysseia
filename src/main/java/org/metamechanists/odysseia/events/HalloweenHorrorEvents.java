package org.metamechanists.odysseia.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Sustos nuevos de octubre. Ninguno hace daño ni toca bloques: son sonido, partículas, mensajes y criaturas inofensivas de
 * vida corta. Se sortean desde {@link HorrorNightScheduler} solo si {@link HalloweenPlus#activo} es verdadero.
 */
public final class HalloweenHorrorEvents {

    private static final Random AZAR = new Random();

    private HalloweenHorrorEvents() {
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Lluvia de sangre: ~8 s de gotas rojas alrededor del jugador con un sonido húmedo. Sin efecto en el juego. */
    public static void lluviaDeSangre(Plugin plugin, Player jugador) {
        jugador.sendMessage(color("&4&l✦ &cEl cielo se tiñe de rojo... &4&ogotea sangre."));
        jugador.playSound(jugador.getLocation(), Sound.AMBIENT_CAVE, 1.6f, 0.5f);
        Particle.DustOptions polvo = new Particle.DustOptions(Color.fromRGB(150, 0, 10), 1.6f);
        new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                if (!jugador.isOnline() || jugador.isDead() || (ticks += 2) > 160) {
                    cancel();
                    return;
                }
                Location c = jugador.getLocation();
                for (int i = 0; i < 6; i++) {
                    Location gota = c.clone().add((AZAR.nextDouble() - 0.5) * 12, 3.5 + AZAR.nextDouble() * 3, (AZAR.nextDouble() - 0.5) * 12);
                    jugador.spawnParticle(Particle.DUST, gota, 1, 0, 0, 0, 0, polvo);
                }
                if (ticks % 20 == 0) {
                    jugador.playSound(c, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_LAVA, 0.8f, 0.6f);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * Ojos en la oscuridad: dos pares de ojos blancos a ~13 bloques que parpadean y desaparecen si te acercas a menos de 7 o a
     * los 12 s.
     */
    public static void ojosEnLaOscuridad(Plugin plugin, Player jugador) {
        jugador.sendMessage(color("&8[&f👁&8] &7Algo te observa desde la oscuridad..."));
        Vector adelante = jugador.getLocation().getDirection().setY(0);
        if (adelante.lengthSquared() < 1.0E-6) {
            adelante = new Vector(0, 0, 1);
        }
        adelante.normalize();
        List<Location> pares = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Vector v = adelante.clone().rotateAroundY(Math.toRadians(-70 + AZAR.nextInt(140))).multiply(13);
            pares.add(jugador.getLocation().clone().add(v.getX(), 1.6, v.getZ()));
        }
        Particle.DustOptions polvo = new Particle.DustOptions(Color.WHITE, 1.1f);
        new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                if (!jugador.isOnline() || jugador.isDead() || (ticks += 4) > 240) {
                    cancel();
                    return;
                }
                boolean cerca = false;
                for (Location p : pares) {
                    if (p.getWorld() != jugador.getWorld() || p.distanceSquared(jugador.getLocation()) < 49) {
                        cerca = true;
                    }
                }
                if (cerca) {
                    jugador.playSound(jugador.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.5f);
                    cancel();
                    return;
                }
                if (AZAR.nextInt(5) == 0) {
                    return; // parpadeo
                }
                for (Location p : pares) {
                    Vector lado = jugador.getLocation().toVector().subtract(p.toVector()).setY(0).normalize().rotateAroundY(Math.PI / 2).multiply(0.16);
                    jugador.spawnParticle(Particle.DUST, p.clone().add(lado), 1, 0, 0, 0, 0, polvo);
                    jugador.spawnParticle(Particle.DUST, p.clone().subtract(lado), 1, 0, 0, 0, 0, polvo);
                }
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    /** Enjambre de murciélagos inofensivos: 10 alrededor del jugador, se retiran a los 6 s. */
    public static void enjambreDeMurcielagos(Plugin plugin, Player jugador) {
        jugador.sendMessage(color("&8[&6🦇&8] &7Un aleteo helado sube desde la tierra..."));
        jugador.playSound(jugador.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1.4f, 0.7f);
        List<Bat> bichos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Location l = jugador.getLocation().clone().add((AZAR.nextDouble() - 0.5) * 6, 1 + AZAR.nextDouble() * 2, (AZAR.nextDouble() - 0.5) * 6);
            Bat b = l.getWorld().spawn(l, Bat.class, bat -> {
                bat.setPersistent(false);
                bat.setRemoveWhenFarAway(true);
                bat.setSilent(false);
            });
            bichos.add(b);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> bichos.forEach(b -> {
            if (b.isValid()) {
                b.getWorld().spawnParticle(Particle.SMOKE, b.getLocation(), 4, 0.1, 0.1, 0.1, 0.01);
                b.remove();
            }
        }), 120L);
    }

    /** Susurro con el nombre del jugador en el chat, solo para él. */
    public static void susurroDeHerobrine(Player jugador) {
        String[] frases = {
                "&8&o&k|||&r &fHerobrine &8&o&k|||&r &7\"Te vi construir, %s... y sigo aquí.\"",
                "&8&o&k|||&r &fHerobrine &8&o&k|||&r &7\"No estás solo en este mundo, %s.\"",
                "&8&o&k|||&r &fHerobrine &8&o&k|||&r &7\"Mira atrás, %s. No, no mires.\"",
        };
        jugador.sendMessage(color(String.format(frases[AZAR.nextInt(frases.length)], jugador.getName())));
        jugador.playSound(jugador.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.6f);
    }
}
