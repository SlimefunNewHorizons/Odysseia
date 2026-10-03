package org.metamechanists.odysseia.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.Odysseia;
import org.metamechanists.odysseia.util.SlimefunClasses;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Luna de Sangre.
 *
 * <p>Cae cada N noches (5 por defecto) y convierte la noche en un asedio. El
 * desafio es la <b>cantidad</b> de enemigos, no la fuerza de cada uno: todos
 * pegan lo mismo, un corazon por golpe, y el peligro nace de que son cientos.
 *
 * <p>Tres reglas que no se negocian:
 *
 * <ol>
 *   <li><b>Las protecciones se respetan siempre.</b> No se genera nada dentro de
 *       una zona protegida, no se rompe un bloque protegido, y cualquier miembro
 *       de la horda que consiga entrar —un enderman que se teletransporta, un vex
 *       que atraviesa una pared— se retira solo. La consulta falla cerrada: si no
 *       se puede saber si algo esta protegido, se asume que si.</li>
 *   <li><b>Quien se queda encerrado no ve nada.</b> La horda solo se rellena
 *       alrededor de jugadores que estan fuera de proteccion. Es lo que hace que
 *       salir tenga sentido y quedarse dentro sea aburrido, no invulnerable.</li>
 *   <li><b>Al amanecer no queda ni uno.</b> Todo lo etiquetado muere de golpe y
 *       se anuncia quien mato mas.</li>
 * </ol>
 *
 * <p>Los mobs llevan el tag {@code ODYSSEIA_BLOODMOON}, que LevelledMobs excluye
 * en su regla por defecto: si los nivelara, dejarian de pegar un corazon.
 */
public final class BloodMoonManager implements Listener {

    /** Tag de scoreboard. LevelledMobs lo excluye; sirve tambien para depurar en vivo. */
    public static final String HORDE_TAG = "ODYSSEIA_BLOODMOON";

    private static final long NOCHE_TICKS = 24000L;
    private static final long ANOCHECER = 13000L;
    private static final long AMANECER = 23000L;

    private final Odysseia plugin;
    private final NamespacedKey hordeZombieKey;
    private final NamespacedKey hordeKey;
    private final Map<UUID, MoonState> activeMoons = new HashMap<>();
    private final Set<Long> evaluatedNights = new HashSet<>();
    private final Method regionFromLocation;
    private final Method worldGuardQuery;
    private final Method slimefunGetById;
    private final Method slimefunGetItem;
    private BukkitTask monitorTask;
    private BukkitTask hordeTask;
    private BukkitTask blockTask;
    private BukkitTask patrolTask;
    private boolean protectionWarningLogged;
    private boolean slimefunWarningLogged;

    public BloodMoonManager(Odysseia plugin) {
        this.plugin = plugin;
        this.hordeZombieKey = new NamespacedKey(plugin, "blood_moon_zombie");
        this.hordeKey = new NamespacedKey(plugin, "blood_moon_horde");
        this.regionFromLocation = findProtectionLookup();
        this.worldGuardQuery = findWorldGuardLookup();

        Method getById = null;
        Method getItem = null;
        try {
            Class<?> type = SlimefunClasses.slimefunItem();
            getById = type.getMethod("getById", String.class);
            getItem = type.getMethod("getItem");
        } catch (ReflectiveOperationException ignored) {
            // Slimefun es opcional. Sin el, el loot simplemente no aparece.
        }
        this.slimefunGetById = getById;
        this.slimefunGetItem = getItem;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("blood-moon.enabled", true)) {
            return;
        }
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkForNewMoon, 100L, 100L);

        long relleno = Math.max(2L, plugin.getConfig().getLong("blood-moon.refill-seconds", 8L)) * 20L;
        hordeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refillHordes, relleno, relleno);

        blockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::breakSoftBlocks, 40L, 40L);
        // Ronda que expulsa de las protecciones a lo que se haya colado.
        patrolTask = Bukkit.getScheduler().runTaskTimer(plugin, this::patrolProtections, 60L, 60L);
    }

    public boolean forceStart(World world) {
        if (world == null || activeMoons.containsKey(world.getUID())) {
            return false;
        }
        beginMoon(world);
        return true;
    }

    public boolean stop(World world) {
        MoonState state = activeMoons.remove(world.getUID());
        if (state == null) {
            return false;
        }
        int retirados = removeHorde(state);
        state.world().setStorm(state.wasStorming());
        state.world().setThundering(state.wasThundering());
        announce(world, "&8&l[LUNA DE SANGRE] &7El sol disuelve la horda. &8(" + retirados + " criaturas)");
        announceTopKills(state);
        return true;
    }

    public boolean isActive(World world) {
        return world != null && activeMoons.containsKey(world.getUID());
    }

    public void shutdown() {
        if (monitorTask != null) monitorTask.cancel();
        if (hordeTask != null) hordeTask.cancel();
        if (blockTask != null) blockTask.cancel();
        if (patrolTask != null) patrolTask.cancel();
        activeMoons.values().forEach(this::removeHorde);
        activeMoons.clear();
    }

    // ------------------------------------------------------------------ disparo

    /**
     * Decide si esta noche toca Luna de Sangre.
     *
     * <p>Por defecto es <b>deterministica</b>: una cada {@code every-nights}
     * noches, para que la comunidad pueda contarlas y prepararse. Poniendo
     * {@code every-nights: 0} se vuelve al sorteo por {@code chance}.
     */
    private void checkForNewMoon() {
        for (World world : Bukkit.getWorlds()) {
            if (!isAllowedWorld(world)) continue;

            if (activeMoons.containsKey(world.getUID())) {
                if (world.getTime() >= AMANECER || world.getTime() < 12000L) {
                    stop(world);
                }
                continue;
            }

            if (world.getTime() < ANOCHECER || world.getTime() > 13500L) continue;

            long noche = world.getFullTime() / NOCHE_TICKS;
            if (!evaluatedNights.add(world.getUID().getMostSignificantBits() ^ noche)) continue;

            if (tocaEstaNoche(noche)) {
                beginMoon(world);
            }
            if (evaluatedNights.size() > 128) evaluatedNights.clear();
        }
    }

    private boolean tocaEstaNoche(long noche) {
        int cada = plugin.getConfig().getInt("blood-moon.every-nights", 5);
        if (cada > 0) {
            return noche % cada == 0L;
        }
        return ThreadLocalRandom.current().nextDouble() <= chance("blood-moon.chance", 0.18D);
    }

    private void beginMoon(World world) {
        activeMoons.put(world.getUID(), new MoonState(world));
        world.setStorm(true);
        world.setThundering(true);

        announce(world, "&4&l[LUNA DE SANGRE] &cLa luna sangra. Algo viene por ustedes.");
        announce(world, "&7Dentro de tu proteccion estas a salvo, y no veras nada.");
        announce(world, "&c&lSi quieres ver que trae esta horda, sal de tu zona de confort.");
        announce(world, "&8Al amanecer se disuelven todos. Se anunciara quien mato mas.");
    }

    // ------------------------------------------------------------------ oleadas

    /**
     * Rellena la horda alrededor de cada jugador que este a la intemperie.
     *
     * <p>No hay oleadas fijas: se mantiene una poblacion viva y se repone segun
     * caen. El techo global se reparte entre los conectados, asi que con mucha
     * gente cada uno recibe menos y el total no se dispara.
     */
    private void refillHordes() {
        for (MoonState state : new ArrayList<>(activeMoons.values())) {
            World world = state.world();
            if (world.getTime() >= AMANECER || world.getTime() < 12000L) {
                stop(world);
                continue;
            }

            state.purgeDead();

            List<Player> candidatos = new ArrayList<>();
            for (Player player : world.getPlayers()) {
                if (player.isDead()) continue;
                if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                        || player.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                // La regla del evento: encerrado no ves nada.
                if (isProtected(player.getLocation())) continue;
                candidatos.add(player);
            }
            if (candidatos.isEmpty()) continue;

            int techoGlobal = Math.max(1, plugin.getConfig().getInt("blood-moon.max-alive-global", 300));
            int porJugador = Math.max(1, plugin.getConfig().getInt("blood-moon.per-player-share", 60));
            // Con mucha gente el reparto manda; con poca, el techo por jugador.
            int cupo = Math.min(porJugador, Math.max(4, techoGlobal / candidatos.size()));

            int libre = techoGlobal - state.mobs().size();
            if (libre <= 0) continue;

            for (Player player : candidatos) {
                int cerca = contarHordaCerca(player, 40.0D);
                for (int i = cerca; i < cupo && libre > 0; i++) {
                    if (spawnHordeMob(state, player)) libre--;
                }
            }
        }
    }

    private int contarHordaCerca(Player player, double radio) {
        int n = 0;
        for (Entity e : player.getNearbyEntities(radio, radio / 2.0D, radio)) {
            if (e.getScoreboardTags().contains(HORDE_TAG)) n++;
        }
        return n;
    }

    /** Genera un miembro de la horda cerca del jugador. Devuelve si lo consiguio. */
    private boolean spawnHordeMob(MoonState state, Player player) {
        Location location = findSpawn(player);
        if (location == null || isProtected(location)) return false;

        EntityType tipo = elegirTipo(state);
        Entity entity = state.world().spawnEntity(location, tipo);
        if (!(entity instanceof LivingEntity mob)) {
            entity.remove();
            return false;
        }

        marcarComoHorda(mob);
        aplicarAtributos(mob);

        if (mob instanceof org.bukkit.entity.Mob agresivo) {
            agresivo.setTarget(player);
        }
        if (mob instanceof Zombie zombie) {
            zombie.setCanPickupItems(false);
            state.zombies().add(zombie.getUniqueId());
        }

        state.mobs().add(mob.getUniqueId());
        return true;
    }

    /** Marca una entidad como parte de la horda, para tag, limpieza y LevelledMobs. */
    private void marcarComoHorda(LivingEntity mob) {
        mob.addScoreboardTag(HORDE_TAG);
        mob.getPersistentDataContainer().set(hordeKey, PersistentDataType.BYTE, (byte) 1);
        if (mob instanceof Zombie) {
            mob.getPersistentDataContainer().set(hordeZombieKey, PersistentDataType.BYTE, (byte) 1);
        }
        // Son criaturas de una noche: no deben sobrevivir a nada.
        mob.setRemoveWhenFarAway(true);
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
    }

    /**
     * Iguala a toda la horda: un corazon por golpe y vida modesta.
     *
     * <p>El tope real lo impone {@link #onHordeDamage}, porque hay danos que no
     * pasan por el atributo: flechas, pociones de bruja y el grito del warden.
     */
    private void aplicarAtributos(LivingEntity mob) {
        double dano = plugin.getConfig().getDouble("blood-moon.damage-cap", 2.0D);
        double vida = plugin.getConfig().getDouble("blood-moon.health", 20.0D);

        var atkAttr = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atkAttr != null) atkAttr.setBaseValue(dano);

        var hpAttr = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hpAttr != null) {
            // El warden conserva su vida: es el evento raro de la noche.
            if (mob.getType() != EntityType.WARDEN) {
                hpAttr.setBaseValue(vida);
                mob.setHealth(Math.min(vida, hpAttr.getValue()));
            }
        }

        var spdAttr = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spdAttr != null) {
            double factor = plugin.getConfig().getDouble("blood-moon.speed-multiplier", 1.15D);
            spdAttr.setBaseValue(Math.min(0.36D, spdAttr.getBaseValue() * factor));
        }
    }

    /**
     * Elige que criatura toca, por pesos configurables.
     *
     * <p>El warden va aparte: es un evento raro, limitado por luna, y se anuncia.
     */
    private EntityType elegirTipo(MoonState state) {
        if (state.puedeWarden(plugin) && ThreadLocalRandom.current().nextDouble() < 0.004D) {
            state.registrarWarden();
            announce(state.world(), "&0&l[LUNA DE SANGRE] &8Algo mucho mas viejo ha despertado. &7Corran.");
            return EntityType.WARDEN;
        }

        Map<EntityType, Integer> pesos = composicion();
        int total = pesos.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return EntityType.ZOMBIE;

        int tirada = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<EntityType, Integer> e : pesos.entrySet()) {
            tirada -= e.getValue();
            if (tirada < 0) return e.getKey();
        }
        return EntityType.ZOMBIE;
    }

    /** Composicion de la horda. Los shulkers quedan fuera a proposito: son estaticos. */
    private Map<EntityType, Integer> composicion() {
        Map<EntityType, Integer> pesos = new LinkedHashMap<>();
        var seccion = plugin.getConfig().getConfigurationSection("blood-moon.composition");
        if (seccion != null) {
            for (String clave : seccion.getKeys(false)) {
                try {
                    EntityType tipo = EntityType.valueOf(clave.toUpperCase(java.util.Locale.ROOT));
                    if (tipo == EntityType.SHULKER) continue;
                    int peso = seccion.getInt(clave, 0);
                    if (peso > 0) pesos.put(tipo, peso);
                } catch (IllegalArgumentException ignored) {
                    // Una entidad mal escrita en config no debe tumbar el evento.
                }
            }
        }
        if (pesos.isEmpty()) {
            pesos.put(EntityType.ZOMBIE, 55);
            pesos.put(EntityType.SKELETON, 15);
            pesos.put(EntityType.SPIDER, 10);
            pesos.put(EntityType.ENDERMAN, 8);
            pesos.put(EntityType.WITCH, 5);
            pesos.put(EntityType.VINDICATOR, 4);
            pesos.put(EntityType.EVOKER, 2);
        }
        return pesos;
    }

    // ------------------------------------------------- proteccion y colados

    /**
     * Retira a los miembros de la horda que hayan acabado dentro de una zona
     * protegida.
     *
     * <p>Hace falta porque no todo entra caminando: los endermans se teletransportan
     * y los vexes de los evokers atraviesan las paredes. Sin esta ronda, invitar a
     * evokers a la fiesta seria abrir un agujero en todas las protecciones.
     */
    private void patrolProtections() {
        for (MoonState state : activeMoons.values()) {
            state.mobs().removeIf(uuid -> {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid()) return true;
                if (isProtected(entity.getLocation())) {
                    entity.remove();
                    return true;
                }
                return false;
            });
        }
    }

    /** Los vexes nacen del evoker, no de nosotros: hay que adoptarlos al vuelo. */
    @EventHandler(ignoreCancelled = true)
    public void onVexSpawn(EntitySpawnEvent event) {
        if (event.getEntityType() != EntityType.VEX) return;
        if (!(event.getEntity() instanceof LivingEntity vex)) return;

        MoonState state = activeMoons.get(vex.getWorld().getUID());
        if (state == null) return;

        // Solo si lo invoco un evoker de la horda que tenga cerca.
        boolean deLaHorda = vex.getNearbyEntities(16, 16, 16).stream()
                .anyMatch(e -> e.getType() == EntityType.EVOKER
                        && e.getScoreboardTags().contains(HORDE_TAG));
        if (!deLaHorda) return;

        if (isProtected(vex.getLocation())) {
            event.setCancelled(true);
            return;
        }
        marcarComoHorda(vex);
        aplicarAtributos(vex);
        state.mobs().add(vex.getUniqueId());
    }

    // ------------------------------------------------------------------ dano

    /**
     * Iguala el dano de toda la horda a un corazon.
     *
     * <p>El atributo {@code ATTACK_DAMAGE} solo cubre el golpe cuerpo a cuerpo.
     * Esto ademas alcanza a las flechas del esqueleto, a las pociones de la bruja
     * y al grito del warden, que ignora armadura y atraviesa bloques.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHordeDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Entity fuente = event.getDamager();
        if (fuente instanceof Projectile projectile) {
            ProjectileSource tirador = projectile.getShooter();
            if (!(tirador instanceof Entity origen)) return;
            fuente = origen;
        }
        if (!fuente.getScoreboardTags().contains(HORDE_TAG)) return;

        double tope = plugin.getConfig().getDouble("blood-moon.damage-cap", 2.0D);
        if (event.getDamage() > tope) {
            event.setDamage(tope);
        }
    }

    // ------------------------------------------------------- bajas y recuento

    @EventHandler(ignoreCancelled = true)
    public void onHordeDeath(EntityDeathEvent event) {
        LivingEntity muerto = event.getEntity();
        if (!muerto.getScoreboardTags().contains(HORDE_TAG)) return;

        MoonState state = activeMoons.get(muerto.getWorld().getUID());
        if (state != null) {
            state.mobs().remove(muerto.getUniqueId());
            Player asesino = muerto.getKiller();
            if (asesino != null) state.registrarBaja(asesino.getUniqueId());
        }

        if (!(muerto instanceof Zombie)) return;
        if (ThreadLocalRandom.current().nextDouble() > chance("blood-moon.slimefun-loot.chance", 0.10D)) return;
        ItemStack loot = randomSlimefunLoot();
        if (loot != null) event.getDrops().add(loot);
    }

    /** Anuncia el podio de la noche. Sin bajas no se dice nada. */
    private void announceTopKills(MoonState state) {
        if (!plugin.getConfig().getBoolean("blood-moon.top-kills.enabled", true)) return;
        if (state.bajas().isEmpty()) return;

        int cuantos = Math.max(1, plugin.getConfig().getInt("blood-moon.top-kills.show", 3));
        List<Map.Entry<UUID, Integer>> podio = new ArrayList<>(state.bajas().entrySet());
        podio.sort(Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue).reversed());

        announce(state.world(), "&4&l[LUNA DE SANGRE] &fLos que mas aguantaron:");
        String[] medallas = {"&e&l1º", "&7&l2º", "&6&l3º"};
        for (int i = 0; i < Math.min(cuantos, podio.size()); i++) {
            Map.Entry<UUID, Integer> fila = podio.get(i);
            String nombre = Bukkit.getOfflinePlayer(fila.getKey()).getName();
            if (nombre == null) nombre = "?";
            String medalla = i < medallas.length ? medallas[i] : "&8" + (i + 1) + "º";
            announce(state.world(), "  " + medalla + " &f" + nombre + " &8— &c" + fila.getValue() + " &7bajas");
        }
    }

    // --------------------------------------------------------------- terreno

    private Location findSpawn(Player player) {
        World world = player.getWorld();
        double min = plugin.getConfig().getDouble("blood-moon.spawn-radius-min", 20.0D);
        double max = Math.max(min + 1.0D, plugin.getConfig().getDouble("blood-moon.spawn-radius-max", 34.0D));

        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double distance = ThreadLocalRandom.current().nextDouble(min, max);
            int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z);
            Location location = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
            if (location.getBlock().isPassable() && location.clone().add(0, 1, 0).getBlock().isPassable()) {
                return location;
            }
        }
        return null;
    }

    private void breakSoftBlocks() {
        if (!plugin.getConfig().getBoolean("blood-moon.zombie-block-breaking.enabled", true)) return;
        for (MoonState state : activeMoons.values()) {
            state.zombies().removeIf(uuid -> {
                Entity entity = Bukkit.getEntity(uuid);
                return !(entity instanceof Zombie zombie) || zombie.isDead() || !zombie.isValid();
            });
            for (UUID uuid : state.zombies()) {
                Entity entity = Bukkit.getEntity(uuid);
                if (!(entity instanceof Zombie zombie)) continue;
                Block objetivo = zombie.getLocation().getBlock();
                if (canBreak(objetivo)) objetivo.breakNaturally();
            }
        }
    }

    private boolean canBreak(Block block) {
        if (block.getType().isAir() || !allowedBreakMaterials().contains(block.getType())) return false;
        return !isProtected(block.getLocation());
    }

    private Set<Material> allowedBreakMaterials() {
        Set<Material> result = new HashSet<>();
        for (String entry : plugin.getConfig().getStringList("blood-moon.zombie-block-breaking.allowed-materials")) {
            Material material = Material.matchMaterial(entry);
            if (material != null) result.add(material);
        }
        return result;
    }

    // ---------------------------------------------------------- protecciones

    private Method findProtectionLookup() {
        try {
            return Class.forName("dev.espi.protectionstones.PSRegion").getMethod("fromLocation", Location.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Localiza la consulta de WorldGuard.
     *
     * <p>ProtectionStones cubre las parcelas de jugador, pero <b>no</b> las regiones
     * de WorldGuard creadas a mano: los spawns de cada modalidad y el lobby son de
     * esas. Sin esta segunda consulta la horda podia aparecer y romper dentro del
     * spawn, que es justo donde no debe.
     */
    private Method findWorldGuardLookup() {
        try {
            Class<?> contenedor = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer");
            return contenedor.getMethod("createQuery");
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** Si no se puede determinar, se considera protegido. Nunca al reves. */
    private boolean isProtected(Location location) {
        if (location == null) return true;

        if (regionFromLocation == null && worldGuardQuery == null) {
            if (!protectionWarningLogged) {
                protectionWarningLogged = true;
                plugin.getLogger().warning("[BloodMoon] Sin ProtectionStones ni WorldGuard: "
                        + "se asume todo protegido y la horda no actuara.");
            }
            return true;
        }

        try {
            if (regionFromLocation != null && regionFromLocation.invoke(null, location) != null) {
                return true;
            }
        } catch (ReflectiveOperationException exception) {
            avisarFalloProteccion(exception);
            return true;
        }

        return enRegionWorldGuard(location);
    }

    /**
     * Consulta WorldGuard por reflexion, para no atarnos a su API en compilacion.
     * Cualquier tropiezo se resuelve como "protegido".
     */
    private boolean enRegionWorldGuard(Location location) {
        if (worldGuardQuery == null) return false;
        try {
            Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instancia = wg.getMethod("getInstance").invoke(null);
            Object plataforma = instancia.getClass().getMethod("getPlatform").invoke(instancia);
            Object contenedor = plataforma.getClass().getMethod("getRegionContainer").invoke(plataforma);
            Object consulta = contenedor.getClass().getMethod("createQuery").invoke(contenedor);

            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weLocation = bukkitAdapter.getMethod("adapt", Location.class).invoke(null, location);

            Class<?> weLocationClass = Class.forName("com.sk89q.worldedit.util.Location");
            Object regiones = consulta.getClass()
                    .getMethod("getApplicableRegions", weLocationClass)
                    .invoke(consulta, weLocation);

            int tamano = (int) regiones.getClass().getMethod("size").invoke(regiones);
            return tamano > 0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            avisarFalloProteccion(exception);
            return true;
        }
    }

    private void avisarFalloProteccion(Throwable exception) {
        if (protectionWarningLogged) return;
        protectionWarningLogged = true;
        plugin.getLogger().warning("[BloodMoon] No se pudo consultar la proteccion ("
                + exception.getClass().getSimpleName() + "): se asume protegido.");
    }

    // ------------------------------------------------------------------ loot

    private boolean spawnDrakesBoss(String id, Location location) {
        org.bukkit.plugin.Plugin bosses = Bukkit.getPluginManager().getPlugin("DrakesBosses");
        if (bosses == null || !bosses.isEnabled()) return false;
        try {
            Method spawn = bosses.getClass().getMethod("spawnBoss", String.class, Location.class);
            Object resultado = spawn.invoke(bosses, id, location);
            return !(resultado instanceof Boolean valor) || valor;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private ItemStack randomSlimefunLoot() {
        if (slimefunGetById == null || slimefunGetItem == null) return null;
        List<String> ids = plugin.getConfig().getStringList("blood-moon.slimefun-loot.item-ids");
        if (ids.isEmpty()) return null;
        String id = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        try {
            Object slimefunItem = slimefunGetById.invoke(null, id);
            if (slimefunItem == null) return null;
            ItemStack item = (ItemStack) slimefunGetItem.invoke(slimefunItem);
            return item == null ? null : item.clone();
        } catch (ReflectiveOperationException exception) {
            if (!slimefunWarningLogged) {
                slimefunWarningLogged = true;
                plugin.getLogger().warning("[BloodMoon] No se pudo generar loot Slimefun: " + exception.getMessage());
            }
            return null;
        }
    }

    // --------------------------------------------------------------- soporte

    private boolean isAllowedWorld(World world) {
        List<String> worlds = plugin.getConfig().getStringList("blood-moon.worlds");
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    private double chance(String path, double fallback) {
        return Math.clamp(plugin.getConfig().getDouble(path, fallback), 0.0D, 1.0D);
    }

    private int removeHorde(MoonState state) {
        int retirados = 0;
        for (UUID uuid : new ArrayList<>(state.mobs())) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
                retirados++;
            }
        }
        state.mobs().clear();
        state.zombies().clear();
        return retirados;
    }

    private void announce(World world, String message) {
        String formatted = ChatColor.translateAlternateColorCodes('&', message);
        for (Player player : world.getPlayers()) player.sendMessage(formatted);
    }

    private static final class MoonState {
        private final World world;
        private final Set<UUID> mobs = new HashSet<>();
        private final Set<UUID> zombies = new HashSet<>();
        private final Map<UUID, Integer> bajas = new HashMap<>();
        private final boolean wasStorming;
        private final boolean wasThundering;
        private int wardens;

        private MoonState(World world) {
            this.world = world;
            this.wasStorming = world.hasStorm();
            this.wasThundering = world.isThundering();
        }

        private World world() {
            return world;
        }

        private Set<UUID> mobs() {
            return mobs;
        }

        private Set<UUID> zombies() {
            return zombies;
        }

        private Map<UUID, Integer> bajas() {
            return bajas;
        }

        private void registrarBaja(UUID jugador) {
            bajas.merge(jugador, 1, Integer::sum);
        }

        private boolean puedeWarden(Odysseia plugin) {
            if (!plugin.getConfig().getBoolean("blood-moon.warden.enabled", true)) return false;
            return wardens < Math.max(0, plugin.getConfig().getInt("blood-moon.warden.max-per-moon", 1));
        }

        private void registrarWarden() {
            wardens++;
        }

        /** Suelta de la cuenta lo que ya murio o dejo de existir. */
        private void purgeDead() {
            mobs.removeIf(uuid -> {
                Entity entity = Bukkit.getEntity(uuid);
                return entity == null || !entity.isValid();
            });
        }

        private boolean wasStorming() {
            return wasStorming;
        }

        private boolean wasThundering() {
            return wasThundering;
        }
    }
}
