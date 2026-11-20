package org.metamechanists.odysseia.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EconomyLedgerTest {
    @Test
    void leeCamposDeLasLineasDelLibroYDeSbank() {
        String propia = "{\"ts\":\"2026-11-20T10:06:14\",\"uuid\":\"u\",\"nombre\":\"Mol\\\"tz\",\"delta\":-2992.00,\"origen\":\"MultiverseGambling\",\"causa\":\"API\"}";
        assertEquals("Mol\"tz", EconomyLedger.campo(propia, "nombre"));
        assertEquals("-2992.00", EconomyLedger.campo(propia, "delta"));
        assertEquals("MultiverseGambling", EconomyLedger.campo(propia, "origen"));
        String sbank = "{\"timestamp\":\"2026-11-20 10:06:14\",\"movement\":\"CASINO_BET\",\"username\":\"Moltzy\",\"amount\":2992.0,\"note\":\"x\"}";
        assertEquals("CASINO_BET", EconomyLedger.campo(sbank, "movement"));
        assertEquals("2992.0", EconomyLedger.campo(sbank, "amount"));
        assertEquals("0", EconomyLedger.campo(sbank, "inexistente"));
    }
}
