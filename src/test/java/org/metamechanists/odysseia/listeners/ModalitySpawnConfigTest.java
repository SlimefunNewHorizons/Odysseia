package org.metamechanists.odysseia.listeners;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coherencia del enrutado de /spawn, comprobada contra el config.yml que se publica.
 *
 * La invariante que importa: toda modalidad tiene que resolver a algun mundo, sea porque
 * declara los suyos en modalidades.modos o porque se le asigna uno a mano. Una modalidad sin
 * ninguna de las dos cosas no falla de forma ruidosa -- simplemente deja pasar el /spawn a
 * EssentialsX y el jugador acaba en el lobby sin entender por que. Eso es lo que esto vigila.
 */
class ModalitySpawnConfigTest {

    private static YamlConfiguration config() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File("src/main/resources/config.yml"));
        return config;
    }

    @Test
    void todaModalidadResuelveAUnMundo() throws Exception {
        YamlConfiguration config = config();
        ConfigurationSection modos = config.getConfigurationSection("modalidades.modos");
        assertNotNull(modos, "no hay modalidades declaradas");

        for (String id : modos.getKeys(false)) {
            List<String> mundos = config.getStringList("modalidades.modos." + id + ".mundos");
            String asignado = config.getString("modalidades.spawn-por-modalidad.mundos." + id);
            boolean resuelve = !mundos.isEmpty() || (asignado != null && !asignado.isBlank());
            assertTrue(resuelve, "la modalidad '" + id + "' no resuelve a ningun mundo: "
                    + "declara mundos en modalidades.modos o asignale uno en spawn-por-modalidad.mundos");
        }
    }

    @Test
    void survivalTieneMundoAsignadoPorSerLaDeRespaldo() throws Exception {
        YamlConfiguration config = config();
        // survival es la modalidad de respaldo en codigo (ModalityService.FALLBACK_ID): declare o no
        // mundos, todo lo no declarado cae en ella, asi que necesita la asignacion explicita.
        String asignado = config.getString("modalidades.spawn-por-modalidad.mundos.survival");
        assertNotNull(asignado, "survival necesita un mundo de spawn explicito");
        assertFalse(asignado.isBlank(), "el mundo de spawn de survival no puede estar vacio");
    }

    @Test
    void lasModalidadesDeBentoBoxQuedanFueraDelEnrutado() throws Exception {
        YamlConfiguration config = config();
        List<String> excluidas = config.getStringList("modalidades.spawn-por-modalidad.gestionan-su-spawn");

        // SkyBlock y OneBlock no tienen spawn: se entra directo a la isla via BentoBox, y sus
        // mundos son de vacio. Enrutar /spawn o el respawn hacia el punto de aparicion del mundo
        // deja al jugador cayendo. Si alguien quita una de estas dos entradas, esto lo para.
        for (String id : List.of("skyblock", "oneblock")) {
            assertTrue(excluidas.stream().anyMatch(value -> value.equalsIgnoreCase(id)),
                    "la modalidad '" + id + "' la gestiona BentoBox y no puede entrar en el "
                            + "enrutado de spawn: su mundo es de vacio y el spawn del mundo es aire");
        }
    }

    @Test
    void loQueSeExcluyeExisteComoModalidad() throws Exception {
        YamlConfiguration config = config();
        ConfigurationSection modos = config.getConfigurationSection("modalidades.modos");
        for (String id : config.getStringList("modalidades.spawn-por-modalidad.gestionan-su-spawn")) {
            assertTrue(modos.getKeys(false).stream().anyMatch(k -> k.equalsIgnoreCase(id)),
                    "se excluye '" + id + "' del enrutado de spawn pero esa modalidad no existe");
        }
    }

    @Test
    void elLobbyEstaDeclarado() throws Exception {
        YamlConfiguration config = config();
        String lobby = config.getString("modalidades.spawn-por-modalidad.mundo-lobby");
        String comando = config.getString("modalidades.spawn-por-modalidad.comando-lobby");
        assertNotNull(lobby, "sin mundo de lobby no hay forma de volver al hub");
        assertFalse(lobby.isBlank());
        assertNotNull(comando, "sin comando de lobby el jugador queda encerrado en su modalidad");
        assertFalse(comando.isBlank());
    }

    @Test
    void elComandoDeLobbyNoPisaOtroComandoDeModalidad() throws Exception {
        YamlConfiguration config = config();
        String comando = config.getString("modalidades.spawn-por-modalidad.comando-lobby", "lobby");
        ConfigurationSection modos = config.getConfigurationSection("modalidades.modos");
        for (String id : modos.getKeys(false)) {
            if ("lobby".equalsIgnoreCase(id)) continue;
            String suyo = config.getString("modalidades.modos." + id + ".comando");
            assertFalse(comando.equalsIgnoreCase(suyo),
                    "el comando de lobby choca con el de la modalidad '" + id + "'");
        }
    }
}
