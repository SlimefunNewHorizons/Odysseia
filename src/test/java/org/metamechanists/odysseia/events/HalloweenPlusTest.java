package org.metamechanists.odysseia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonParser;

/**
 * El contenido extra existe SOLO en octubre. Estas pruebas fijan: qué días cuenta como octubre, que fuera de octubre todo vuelve
 * a los valores de siempre, y que la skin de Herobrine incluida es la firmada de la cuenta oficial.
 */
class HalloweenPlusTest {

    @AfterEach
    void limpiar() {
        HalloweenPlus.forzar(null);
    }

    @Test
    void octubreEsOctubreYNoviembreNo() {
        assertTrue(HalloweenPlus.enOctubre(LocalDate.of(2026, 10, 1)));
        assertTrue(HalloweenPlus.enOctubre(LocalDate.of(2026, 10, 31)));
        assertFalse(HalloweenPlus.enOctubre(LocalDate.of(2026, 11, 1)), "el 1 de noviembre se desactiva");
        assertFalse(HalloweenPlus.enOctubre(LocalDate.of(2026, 9, 30)));
        assertFalse(HalloweenPlus.enOctubre(LocalDate.of(2027, 7, 14)));
    }

    @Test
    void elInterruptorDelConfigLoApagaAunqueSeaOctubre() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("halloween-plus.enabled", false);
        assertFalse(HalloweenPlus.activo(cfg));
    }

    @Test
    void fueraDeOctubreTodoVuelveAlValorNormal() {
        assertEquals(5, HalloweenPlus.cadaNochesLunaDeSangre(false, 5, 2), "Luna de Sangre cada 5 noches el resto del año");
        assertEquals(2, HalloweenPlus.cadaNochesLunaDeSangre(true, 5, 2), "cada 2 en octubre");
        assertEquals(5, HalloweenPlus.cadaNochesLunaDeSangre(true, 5, 0), "0 en el config = sin cambio");
        assertEquals(15L, HalloweenPlus.intervaloHorrorMinutos(false, 15, 8));
        assertEquals(8L, HalloweenPlus.intervaloHorrorMinutos(true, 15, 8));
        assertEquals(10L, HalloweenPlus.intervaloHorrorMinutos(true, 10, 30), "nunca sube el intervalo");
        assertEquals(5, HalloweenPlus.eventosPorNoche(false, 5, 3));
        assertEquals(8, HalloweenPlus.eventosPorNoche(true, 5, 3));
        assertEquals(7, HalloweenPlus.tiposDeSusto(false), "sin Herobrine real ni sustos nuevos fuera de octubre");
        assertEquals(11, HalloweenPlus.tiposDeSusto(true));
    }

    @Test
    void elForzadoDePruebasGanaAlCalendario() {
        HalloweenPlus.forzar(true);
        assertTrue(HalloweenPlus.activo(new YamlConfiguration()));
        HalloweenPlus.forzar(false);
        assertFalse(HalloweenPlus.activo(new YamlConfiguration()));
    }

    // ---------- truco o trato ----------

    @Test
    void tratoOTrucoSegunLaProbabilidad() {
        assertEquals(TrickOrTreatListener.Resultado.TRATO, TrickOrTreatListener.resolver(0.0, 0.7));
        assertEquals(TrickOrTreatListener.Resultado.TRATO, TrickOrTreatListener.resolver(0.69, 0.7));
        assertEquals(TrickOrTreatListener.Resultado.TRUCO, TrickOrTreatListener.resolver(0.70, 0.7));
        assertEquals(TrickOrTreatListener.Resultado.TRUCO, TrickOrTreatListener.resolver(0.1, 0.0), "0 = siempre truco");
        assertEquals(TrickOrTreatListener.Resultado.TRATO, TrickOrTreatListener.resolver(0.99, 5.0), "se acota a 1");
    }

    @Test
    void elEnfriamientoImpideCosecharCalabazas() {
        assertTrue(TrickOrTreatListener.enfriamientoCumplido(null, 1_000L, 45_000L), "primer uso");
        assertFalse(TrickOrTreatListener.enfriamientoCumplido(1_000L, 30_000L, 45_000L));
        assertTrue(TrickOrTreatListener.enfriamientoCumplido(1_000L, 46_000L, 45_000L));
    }

    @Test
    void elTopeDiarioSeReiniciaAlDiaSiguiente() {
        Map<UUID, int[]> usos = new HashMap<>();
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 12; i++) {
            assertTrue(TrickOrTreatListener.dentroDelTope(usos, p, 296, 12), "uso " + (i + 1));
        }
        assertFalse(TrickOrTreatListener.dentroDelTope(usos, p, 296, 12), "el 13º del mismo día se rechaza");
        assertTrue(TrickOrTreatListener.dentroDelTope(usos, p, 297, 12), "al día siguiente vuelve a haber");
        assertTrue(TrickOrTreatListener.dentroDelTope(usos, UUID.randomUUID(), 296, 12), "es por jugador");
    }

    @Test
    void soloCuentanLasCalabazas() {
        assertTrue(TrickOrTreatListener.esCalabaza(Material.JACK_O_LANTERN));
        assertTrue(TrickOrTreatListener.esCalabaza(Material.CARVED_PUMPKIN));
        assertFalse(TrickOrTreatListener.esCalabaza(Material.PUMPKIN), "la calabaza sin tallar no");
        assertFalse(TrickOrTreatListener.esCalabaza(Material.LANTERN));
    }

    // ---------- Herobrine ----------

    @Test
    void laSkinDeHerobrineEsLaFirmadaDeMojang() {
        ProfileProperty t = HerobrineApparition.textura();
        assertNotNull(t, "el recurso halloween/herobrine-skin.json debe cargarse");
        assertEquals("textures", t.getName());
        assertNotNull(t.getSignature(), "la textura va firmada por Mojang");
        assertTrue(t.getSignature().length() > 500);
        String json = new String(Base64.getDecoder().decode(t.getValue()), StandardCharsets.UTF_8);
        String url = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        assertTrue(url.startsWith("http://textures.minecraft.net/texture/"), url);
        assertTrue(url.endsWith("5d58ac578d8d46b09c61e6508cd91a427606824344234e8e8eb082cac28b5b40"), "hash de la skin de la cuenta Herobrine");
        assertEquals("Herobrine", JsonParser.parseString(json).getAsJsonObject().get("profileName").getAsString());
    }

    @Test
    void miradaFijaSeDetectaYMirarAOtroLadoNo() {
        Vector alFrente = new Vector(0, 0, 1);
        assertTrue(HerobrineApparition.mirandoHacia(alFrente, new Vector(0, 0, 14), 0.985), "justo de frente");
        assertTrue(HerobrineApparition.mirandoHacia(alFrente, new Vector(0.5, 0, 14), 0.985), "a ~2° es mirarlo");
        assertFalse(HerobrineApparition.mirandoHacia(alFrente, new Vector(5, 0, 14), 0.985), "a ~20° no lo está mirando");
        assertFalse(HerobrineApparition.mirandoHacia(alFrente, new Vector(0, 0, -14), 0.985), "de espaldas");
        assertFalse(HerobrineApparition.mirandoHacia(alFrente, new Vector(0, 0, 0), 0.985), "vector nulo no revienta");
        assertFalse(HerobrineApparition.mirandoHacia(new Vector(0, 0, 0), new Vector(0, 0, 3), 0.985));
        assertNull(null);
    }

    @Test
    void constantesDeHerobrineSonRazonables() {
        assertTrue(HerobrineApparition.DISTANCIA_MINIMA < HerobrineApparition.DISTANCIA_APARICION);
        assertTrue(HerobrineApparition.TICKS_MIRADA >= 6 && HerobrineApparition.TICKS_MIRADA <= 40, "esfumarse entre 0,3 s y 2 s de mirada");
        assertTrue(HerobrineApparition.DURACION_MAXIMA_TICKS <= 20 * 30, "como mucho 30 s");
    }
}
