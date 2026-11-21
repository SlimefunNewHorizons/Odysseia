package org.metamechanists.odysseia.listeners;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El limite de coordenadas de Bedrock vive en dos sitios: el guard que cancela teletransportes y
 * el RTP de ServerAutomationListener. Si se desincronizan, el RTP manda al jugador justo a donde
 * el guard le prohibe estar y ningun /rtp vuelve a funcionar para Bedrock.
 */
class BedrockRangeGuardTest {

    private static final YamlConfiguration CONFIG =
            YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

    @Test
    void elDestinoDelRtpCaeDentroDelRangoPermitido() {
        int limite = CONFIG.getInt("bedrock-guard.limite");
        int nether = CONFIG.getInt("bedrock-guard.limite-nether");
        assertTrue(limite > 0, "bedrock-guard.limite ausente en config.yml");
        assertFalse(BedrockRangeGuardListener.fueraDeRango(limite, limite, limite),
                "el radio maximo del RTP quedaria fuera del borde");
        assertFalse(BedrockRangeGuardListener.fueraDeRango(nether, nether, nether));
    }

    @Test
    void elLimiteDelNetherRespetaLaCompresionOchoAUno() {
        int limite = CONFIG.getInt("bedrock-guard.limite");
        int nether = CONFIG.getInt("bedrock-guard.limite-nether");
        assertTrue(nether >= limite / 8 && nether < limite,
                "el limite del nether debe estar dentro del rango comprimido respecto al overworld");
    }

    @Test
    void soloSeVigilanMundosDeExploracionLibre() {
        List<String> mundos = CONFIG.getStringList("bedrock-guard.mundos");
        assertTrue(mundos.contains("world"), "falta el mundo principal");
        assertTrue(mundos.stream().noneMatch(m -> m.startsWith("bskyblock") || m.startsWith("oneblock")),
                "las islas son pequenas y no deben recibir borde propio");
    }

    @Test
    void unaCoordenadaFueraDeRangoBastaParaBloquear() {
        assertTrue(BedrockRangeGuardListener.fueraDeRango(0, 150_001, 150_000));
        assertTrue(BedrockRangeGuardListener.fueraDeRango(-150_001, 0, 150_000));
        assertFalse(BedrockRangeGuardListener.fueraDeRango(1000, -2000, 150_000));
    }

    /** Essentials acepta el comando con namespace, y asi es como lo escriben los clientes. */
    @Test
    void seReconoceElComandoConYSinNamespace() {
        assertTrue(BedrockRangeGuardListener.esPeticionDeViaje(
                BedrockRangeGuardListener.etiquetaDe("/tpa .TimeJesus_95")));
        assertTrue(BedrockRangeGuardListener.esPeticionDeViaje(
                BedrockRangeGuardListener.etiquetaDe("/essentials:tpa .TimeJesus_95")));
        assertTrue(BedrockRangeGuardListener.esPeticionDeViaje(
                BedrockRangeGuardListener.etiquetaDe("/TPAHERE Jack")));
        assertFalse(BedrockRangeGuardListener.esPeticionDeViaje(
                BedrockRangeGuardListener.etiquetaDe("/tpaccept")));
    }
}
