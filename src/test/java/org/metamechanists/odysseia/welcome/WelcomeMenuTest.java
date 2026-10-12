package org.metamechanists.odysseia.welcome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WelcomeMenuTest {

    @Test
    void spanishAndRelatedLanguagesGetTheSpanishMenu() {
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor("es_cl"));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor("es_es"));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor("ES_MX"));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor("pt_br"));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor("ca_es"));
    }

    @Test
    void everythingElseGetsEnglish() {
        assertEquals(WelcomeMenu.ENGLISH, WelcomeMenu.dialogFor("en_us"));
        assertEquals(WelcomeMenu.ENGLISH, WelcomeMenu.dialogFor("en-GB"));
        assertEquals(WelcomeMenu.ENGLISH, WelcomeMenu.dialogFor("de_de"));
        assertEquals(WelcomeMenu.ENGLISH, WelcomeMenu.dialogFor("ru_ru"));
    }

    @Test
    void missingLocaleFallsBackToSpanishBecauseThatIsTheServerLanguage() {
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor(null));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogFor(""));
    }

    @Test
    void commandArgumentForcesALanguageAndRejectsGarbage() {
        assertEquals(WelcomeMenu.ENGLISH, WelcomeMenu.dialogForArgument("EN"));
        assertEquals(WelcomeMenu.SPANISH, WelcomeMenu.dialogForArgument("es"));
        assertNull(WelcomeMenu.dialogForArgument("klingon"));
        assertNull(WelcomeMenu.dialogForArgument(null));
    }
}
