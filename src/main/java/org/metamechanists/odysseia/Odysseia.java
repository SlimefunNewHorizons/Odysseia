package org.metamechanists.odysseia;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.metamechanists.odysseia.commands.LenadorCommand;
import org.metamechanists.odysseia.commands.PapaDeMarCommand;
import org.metamechanists.odysseia.commands.ReloadCommand;
import org.metamechanists.odysseia.commands.UltraGodCommand;
import org.metamechanists.odysseia.commands.VanishCommand;
import org.metamechanists.odysseia.kits.KitClaimService;
import org.metamechanists.odysseia.listeners.ArmorEffectsListener;
import org.metamechanists.odysseia.listeners.ItemConsumeListener;
import org.metamechanists.odysseia.listeners.ModerationListener;
import org.metamechanists.odysseia.listeners.PresenceEventListener;
import org.metamechanists.odysseia.listeners.PolisBaselineListener;
import org.metamechanists.odysseia.utils.OdysseiaPlaceholderExpansion;
import org.metamechanists.odysseia.utils.WebhookSender;
import org.metamechanists.odysseia.purchase.PurchaseEngine;
import org.metamechanists.odysseia.integrations.StarTelemetryPublisher;
import org.metamechanists.odysseia.events.BloodMoonManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class Odysseia extends JavaPlugin {

    @Getter
    private static Odysseia instance;

    private VanishCommand vanishCommand;
    private UltraGodCommand ultraGodCommand;
    private KitClaimService kitClaimService;
    private boolean ownerFlip = false;
    private String instanceId = "";
    private org.metamechanists.odysseia.chatgames.SeasonalChatGamesService seasonalChatGames;
    private PurchaseEngine purchaseEngine;
    private StarTelemetryPublisher starTelemetry;
    private PolisBaselineListener polisBaseline;
    private BloodMoonManager bloodMoonManager;
    private org.metamechanists.odysseia.events.HorrorNightScheduler horrorNightScheduler;
    private org.metamechanists.odysseia.dragon.DragonMountService dragonMountService;
    private org.metamechanists.odysseia.listeners.AutomationGuardListener automationGuard;
    private org.metamechanists.odysseia.listeners.SFMasterWatcherListener sfMasterWatcher;
    private org.metamechanists.odysseia.listeners.MaintenanceGuardListener maintenanceGuard;
    private org.metamechanists.odysseia.economy.EconomyWatchdog economyWatchdog;
    private org.metamechanists.odysseia.listeners.ResourcePackListener resourcePack;
    private org.metamechanists.odysseia.services.VipExpiryAlertService vipExpiryAlertService;
    private org.metamechanists.odysseia.services.ServerChangelogService changelogService;
    @Getter
    private org.metamechanists.odysseia.services.GlobalPlaytimeService globalPlaytime;
    private org.metamechanists.odysseia.services.ThreadBudgetMonitor threadBudgetMonitor;
    private org.metamechanists.odysseia.integrations.DiscordTranslationBridgeService discordTranslationBridge;
    @Getter
    private org.metamechanists.odysseia.modalities.ModalityService modalityService;
    private org.metamechanists.odysseia.welcome.WelcomeMenuService welcomeMenuService;
    private org.metamechanists.odysseia.listeners.ModalityStorageGuardListener modalityStorageGuard;
    private org.metamechanists.odysseia.laboratorio.SandboxStashRepository sandboxStash;
    @Getter
    private org.metamechanists.odysseia.listeners.ModalitySpawnListener modalitySpawn;
    @Getter
    private org.metamechanists.odysseia.vaults.ModalityVaultService modalityVaults;
    private org.metamechanists.odysseia.listeners.DeathMessageListener deathMessages;
    private org.metamechanists.odysseia.papa.PapaDeMarService papaService;
    private org.metamechanists.odysseia.cheques.ChequeService chequeService;
    private org.metamechanists.odysseia.saori.SaoriFreezeManager saoriFreezeManager;
        @Getter
    private org.metamechanists.odysseia.events.OdysseiaEventManager eventManager;
    private final List<BukkitTask> runtimeTasks = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;

        // Save and backup configuration files
        backupConfigurationFiles();
        saveDefaultConfig();
        mergeDefaultConfig();

        // Load or create instance ID
        this.instanceId = loadOrCreateInstanceId();
        this.kitClaimService = new KitClaimService(this);

        // Register commands
        this.vanishCommand = new VanishCommand(this);
        getCommand("vani").setExecutor(vanishCommand);
        getCommand("vani").setTabCompleter(vanishCommand);
        this.ultraGodCommand = new UltraGodCommand(this);
        getCommand("ultragod").setExecutor(ultraGodCommand);
        getCommand("ultragod").setTabCompleter(ultraGodCommand);

        org.metamechanists.odysseia.commands.TrollCommand trollCmd = new org.metamechanists.odysseia.commands.TrollCommand(this);
        getCommand("troll").setExecutor(trollCmd);
        getCommand("troll").setTabCompleter(trollCmd);
        getCommand("papademar").setExecutor(new PapaDeMarCommand(this));
        getCommand("lenador").setExecutor(new LenadorCommand(this));
        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("odysseia").setExecutor(reloadCommand);
        getCommand("odysseia").setTabCompleter(reloadCommand);
        getCommand("odysseiaannounce").setExecutor(new org.metamechanists.odysseia.commands.StoreAnnounceCommand(this));
        this.purchaseEngine = new PurchaseEngine(this);
        this.starTelemetry = new StarTelemetryPublisher(this);
        this.purchaseEngine.setTelemetry(telemetry -> starTelemetry.publishPurchase(purchaseEngine, telemetry));
        getCommand("odysseiapurchase").setExecutor(new org.metamechanists.odysseia.commands.PurchaseCommand(purchaseEngine));
        this.welcomeMenuService = new org.metamechanists.odysseia.welcome.WelcomeMenuService(this);
        org.metamechanists.odysseia.listeners.ServerAutomationListener automation = new org.metamechanists.odysseia.listeners.ServerAutomationListener(this);
        org.metamechanists.odysseia.purchase.PendingKitService pendingKits = new org.metamechanists.odysseia.purchase.PendingKitService(this);
        org.metamechanists.odysseia.listeners.ChatFilterListener chatFilter = new org.metamechanists.odysseia.listeners.ChatFilterListener(this);
        getCommand("daily").setExecutor(new org.metamechanists.odysseia.commands.DailyCommand(automation));
        registerDynamicCommand("restart30", new org.metamechanists.odysseia.commands.SafeRestartCommand(this));
        getCommand("sellinv").setExecutor(new org.metamechanists.odysseia.commands.SellInventoryCommand(this));
        try {
            this.chequeService = new org.metamechanists.odysseia.cheques.ChequeService(this);
            var chequeCommand = new org.metamechanists.odysseia.commands.ChequeCommand(chequeService);
            getCommand("cheque").setExecutor(chequeCommand);
            getCommand("cheque").setTabCompleter(chequeCommand);
            Bukkit.getPluginManager().registerEvents(chequeService, this);
            getLogger().info("[SUCCESS] Cheques firmados y libro contable disponibles.");
        } catch (IOException | java.sql.SQLException error) {
            getLogger().log(Level.SEVERE, "[Cheques] Sistema deshabilitado para proteger la economía", error);
        }
        org.metamechanists.odysseia.commands.OwnerAuraCommand ownerAura = new org.metamechanists.odysseia.commands.OwnerAuraCommand(this);
        getCommand("auradueno").setExecutor(ownerAura);
        getCommand("auradueno").setTabCompleter(ownerAura);
        getCommand("odysseiapendingkit").setExecutor(pendingKits);
        getCommand("drakeswarn").setExecutor(chatFilter);
        org.metamechanists.odysseia.commands.KitGiveCommand kitGive = new org.metamechanists.odysseia.commands.KitGiveCommand(this);
        getCommand("kitgive").setExecutor(kitGive);
        getCommand("kitgive").setTabCompleter(kitGive);
        org.metamechanists.odysseia.commands.KitCommand kitCommand = new org.metamechanists.odysseia.commands.KitCommand(this);
        getCommand("kit").setExecutor(kitCommand);
        getCommand("kit").setTabCompleter(kitCommand);
        org.metamechanists.odysseia.commands.EsteUsuarioEsViejoCommand esteViejo = new org.metamechanists.odysseia.commands.EsteUsuarioEsViejoCommand(this);
        getCommand("esteusuarioesviejo").setExecutor(esteViejo);
        getCommand("esteusuarioesviejo").setTabCompleter(esteViejo);
        org.metamechanists.odysseia.menus.ShopMenuService shopMenu = new org.metamechanists.odysseia.menus.ShopMenuService(this);
        getCommand("drakestienda").setExecutor(shopMenu);
        org.metamechanists.odysseia.cosmetics.CosmeticService cosmeticService = new org.metamechanists.odysseia.cosmetics.CosmeticService(this);
        org.metamechanists.odysseia.commands.CosmeticsCommand cosmeticsCmd = new org.metamechanists.odysseia.commands.CosmeticsCommand(cosmeticService);
        getCommand("cosmeticos").setExecutor(cosmeticsCmd);
        getCommand("cosmeticos").setTabCompleter(cosmeticsCmd);
        java.util.List<String> kitErrors = kitGive.validateConfiguration();
        if (kitErrors.isEmpty()) {
            getLogger().info("[SUCCESS] Configuración de kits validada.");
        } else {
            // En staging pueden faltar proveedores opcionales de encantamientos. Mantener
            // el diagnóstico sin convertir un único problema de dependencia en cientos de
            // líneas ERROR que ocultan el resto del arranque.
            getLogger().severe("[Kits] " + kitErrors.size()
                    + " entradas no son resolubles; esos kits se rechazarán completos al entregarse.");
            kitErrors.stream().limit(10)
                    .forEach(error -> getLogger().warning("[Kits] " + error));
            if (kitErrors.size() > 10) {
                getLogger().warning("[Kits] Se omitieron " + (kitErrors.size() - 10)
                        + " diagnósticos repetitivos; instala los proveedores de contenido antes de habilitar esos kits.");
            }
        }
        Bukkit.getPluginManager().registerEvents(purchaseEngine, this);
        Bukkit.getPluginManager().registerEvents(pendingKits, this);
        Bukkit.getPluginManager().registerEvents(chatFilter, this);
        Bukkit.getPluginManager().registerEvents(shopMenu, this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.StoreCommandGuardListener(), this);
        this.resourcePack = new org.metamechanists.odysseia.listeners.ResourcePackListener(this);
        Bukkit.getPluginManager().registerEvents(resourcePack, this);
        Bukkit.getPluginManager().registerEvents(
                new org.metamechanists.odysseia.listeners.CorruptItemGuardListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.CommerceExploitGuardListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.TebexForcecheckListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.VaultBackpackGuardListener(this), this);
        this.deathMessages = new org.metamechanists.odysseia.listeners.DeathMessageListener(this);
        Bukkit.getPluginManager().registerEvents(deathMessages, this);
        this.papaService = new org.metamechanists.odysseia.papa.PapaDeMarService(this);
        var papaMenu = new org.metamechanists.odysseia.papa.PapaTraderMenu(this, papaService);
        registerDynamicCommand("papatrueque", papaMenu);
        registerDynamicCommand("papaequipo", new org.metamechanists.odysseia.papa.PapaEquipoCommand(this));
        registerDynamicCommand("papasf", new org.metamechanists.odysseia.papa.PapaSlimefunCommand(this));
        Bukkit.getPluginManager().registerEvents(papaMenu, this);

        // Modalidades y bovedas separadas. Si las bovedas fallan al abrir la base, el resto del
        // plugin sigue funcionando: solo se pierde la separacion de /pv dentro de las islas.
        this.modalityService = new org.metamechanists.odysseia.modalities.ModalityService(this);
        Bukkit.getPluginManager().registerEvents(
                new org.metamechanists.odysseia.listeners.ModalityAdvancementListener(modalityService), this);
        org.metamechanists.odysseia.commands.ModalidadesCommand modalidadesCmd =
                new org.metamechanists.odysseia.commands.ModalidadesCommand(modalityService);
        getCommand("modalidades").setExecutor(modalidadesCmd);
        getCommand("bienvenida").setExecutor(
                new org.metamechanists.odysseia.commands.WelcomeCommand(welcomeMenuService));
        // Un solo ejecutor para las tres modalidades propias. Resuelve cual es por el
        // nombre del comando, asi que anadir otra solo pide declararla y registrarla.
        var viajeModalidad = new org.metamechanists.odysseia.commands.ModalityTravelCommand(this);
        getCommand("survival").setExecutor(viajeModalidad);
        getCommand("clasico").setExecutor(viajeModalidad);
        getCommand("laboratorio").setExecutor(viajeModalidad);
        getCommand("buy").setExecutor(
                new org.metamechanists.odysseia.commands.BuyCommand(this));
        Bukkit.getPluginManager().registerEvents(modalidadesCmd, this);
        this.modalitySpawn = new org.metamechanists.odysseia.listeners.ModalitySpawnListener(this);
        Bukkit.getPluginManager().registerEvents(modalitySpawn, this);
        getCommand("lobby").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                modalitySpawn.irAlLobby(player);
            } else {
                sender.sendMessage("Solo un jugador puede ir al lobby.");
            }
            return true;
        });
        try {
            this.modalityVaults = new org.metamechanists.odysseia.vaults.ModalityVaultService(this, modalityService);
            Bukkit.getPluginManager().registerEvents(modalityVaults, this);
            this.modalityStorageGuard =
                    new org.metamechanists.odysseia.listeners.ModalityStorageGuardListener(this, modalityService, modalityVaults);
            Bukkit.getPluginManager().registerEvents(modalityStorageGuard, this);
        } catch (java.sql.SQLException error) {
            getLogger().log(Level.SEVERE, "[Bovedas] No se pudo iniciar el almacen de bovedas por modalidad", error);
        }

        // Blindaje de comercios de aldeanos y jugabilidad en modalidad Clásico contra filtraciones de Slimefun / Cultivation
        Bukkit.getPluginManager().registerEvents(
                new org.metamechanists.odysseia.listeners.ClasicoVillagerTradeGuardListener(this, modalityService), this);
        Bukkit.getPluginManager().registerEvents(
                new org.metamechanists.odysseia.listeners.ClasicoSlimefunGuardListener(this, modalityService), this);
        Bukkit.getPluginManager().registerEvents(
                new org.metamechanists.odysseia.listeners.ModalityBackGuardListener(this, modalityService), this);

        // Frontera del laboratorio. UniversalModalitySentinel fue retirado permanentemente:
        // su estrategia destructiva purgaba slots ante falsos positivos y llego a borrar
        // inventarios completos. El aislamiento pertenece a InvSwitcher y a la denegacion de
        // comandos; Odysseia no vuelve a borrar objetos como medida anti-fuga.
        // El aislamiento de inventarios y Ender Chests lo gestiona de forma nativa e independiente InvSwitcher.
        java.util.Set<String> mundosLaboratorio = new java.util.HashSet<>();
        for (String mundo : getConfig().getStringList("modalidades.modos.laboratorio.mundos")) {
            mundosLaboratorio.add(mundo.toLowerCase(java.util.Locale.ROOT));
        }
        if (!mundosLaboratorio.isEmpty()) {
            Bukkit.getPluginManager().registerEvents(
                    new org.metamechanists.odysseia.laboratorio.SandboxChunkLimitListener(
                            this, mundosLaboratorio), this);
            Bukkit.getPluginManager().registerEvents(
                    new org.metamechanists.odysseia.laboratorio.SandboxSafetyListener(
                            this, mundosLaboratorio), this);
            Bukkit.getPluginManager().registerEvents(
                    new org.metamechanists.odysseia.laboratorio.SandboxAdvancementGuardListener(
                            mundosLaboratorio), this);
            getLogger().info("[Modalidades] Aislamiento, avances, limites de maquinas y seguridad no destructiva de laboratorio activos.");
        }

        this.eventManager = new org.metamechanists.odysseia.events.OdysseiaEventManager(this);
        var eventoCmd = new org.metamechanists.odysseia.commands.EventoCommand(eventManager);
        getCommand("evento").setExecutor(eventoCmd);
        getCommand("evento").setTabCompleter(eventoCmd);
        getLogger().info("[Eventos] Suite de eventos (Rush, Drops, Boosts, Boxes, Bosses, PvP) activa.");

        // Las arenas y el ciclo de vida de jefes pertenecen a DrakesBosses.
        this.bloodMoonManager = new BloodMoonManager(this);
        getCommand("bloodmoon").setExecutor(new org.metamechanists.odysseia.commands.BloodMoonCommand(bloodMoonManager));
        org.metamechanists.odysseia.commands.MeteorCommand meteorCommand = new org.metamechanists.odysseia.commands.MeteorCommand(this);
        getCommand("meteorito").setExecutor(meteorCommand);
        getCommand("meteorito").setTabCompleter(meteorCommand);
        this.dragonMountService = new org.metamechanists.odysseia.dragon.DragonMountService(this);
        new org.metamechanists.odysseia.listeners.RankWalkerAbilitiesListener(this);
        new org.metamechanists.odysseia.weapons.DivineWeaponService(this);
        new org.metamechanists.odysseia.listeners.DivineEnchantmentListener(this);
        getCommand("mountdragon").setExecutor(dragonMountService);
        getCommand("mountdragon").setTabCompleter(dragonMountService);
        Bukkit.getPluginManager().registerEvents(dragonMountService, this);

        // Register listeners
        // SAORI Security Suite (/jack, /saorifreeze, anti-injection, dupe deterrence, Kali-freeze)
        this.saoriFreezeManager = new org.metamechanists.odysseia.saori.SaoriFreezeManager(this);
        Bukkit.getPluginManager().registerEvents(saoriFreezeManager, this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.saori.SaoriChatInterceptor(this, saoriFreezeManager), this);
        var jackCmd = new org.metamechanists.odysseia.saori.JackReportCommand(this);
        if (getCommand("jack") != null) {
            getCommand("jack").setExecutor(jackCmd);
            getCommand("jack").setTabCompleter(jackCmd);
        }
        if (getCommand("saorifreeze") != null) {
            getCommand("saorifreeze").setExecutor(saoriFreezeManager);
            getCommand("saorifreeze").setTabCompleter(saoriFreezeManager);
        }
        getLogger().info("[SAORI] Suite de seguridad, reportes /jack y contención de exploits activa.");

        // Sistema de Reencarnacion y Prestigio
        var reencarnacionManager = new org.metamechanists.odysseia.reencarnacion.ReencarnacionManager(this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.reencarnacion.ReencarnacionListener(reencarnacionManager), this);
        var reencarnarCmd = new org.metamechanists.odysseia.commands.ReencarnarCommand(reencarnacionManager);
        if (getCommand("reencarnar") != null) {
            getCommand("reencarnar").setExecutor(reencarnarCmd);
            getCommand("reencarnar").setTabCompleter(reencarnarCmd);
        }
        getLogger().info("[Reencarnacion] Sistema de Prestigio y Cápsula de Recuerdos activo.");

        Bukkit.getPluginManager().registerEvents(vanishCommand, this);
        Bukkit.getPluginManager().registerEvents(ultraGodCommand, this);
        vanishCommand.startReminder();
        Bukkit.getPluginManager().registerEvents(bloodMoonManager, this);
        Bukkit.getPluginManager().registerEvents(new ArmorEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ItemConsumeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ModerationListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.WorldChangeSafetyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.VoidLoopAndSafetyListener(this), this);
        // BossItemListener vive por duplicado: cuando se separo DrakesBosses se llevo una copia
        // identica de esta clase -- misma logica y la misma NamespacedKey 'odysseia:odyssey_item_type',
        // asi que reconoce exactamente los mismos objetos.
        //
        // Registrar los dos hacia que cada efecto de arma de jefe se aplicara dos veces, y ademas
        // encadenaba: el daño sintetico de uno reentraba por el manejador del otro, porque el guardia
        // de reentrada es una lista propia de cada instancia y no se ven entre ellas. En los logs del
        // 16 salieron once niveles alternando los dos plugins dentro de un StackOverflowError.
        //
        // DrakesBosses es el sitio donde vive esto ahora. Odysseia solo lo registra si no esta.
        if (Bukkit.getPluginManager().getPlugin("DrakesBosses") != null) {
            getLogger().info("DrakesBosses esta instalado: los efectos de las armas de jefe los lleva el.");
        } else {
            Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.BossItemListener(this), this);
        }
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.BossCaptureGuardListener(this), this);
        this.sfMasterWatcher = new org.metamechanists.odysseia.listeners.SFMasterWatcherListener(this);
        Bukkit.getPluginManager().registerEvents(this.sfMasterWatcher, this);
        Bukkit.getScheduler().runTask(this, this.sfMasterWatcher::deliverGuidesToOnlinePassHolders);
        this.sfMasterWatcher.startGuideCleanup();
        this.maintenanceGuard = new org.metamechanists.odysseia.listeners.MaintenanceGuardListener(this);
        // SII: vigilante de economia (INC-065). Arranca un tick despues para que Vault y sBank ya esten registrados.
        this.economyWatchdog = new org.metamechanists.odysseia.economy.EconomyWatchdog(this);
        Bukkit.getScheduler().runTaskLater(this, economyWatchdog::start, 60L);
        Bukkit.getPluginManager().registerEvents(maintenanceGuard, this);

        // Servicio de auditoría y changelog automático a Discord
        this.changelogService = new org.metamechanists.odysseia.services.ServerChangelogService(this);
        this.changelogService.scheduleBootAudit();
        registerDynamicCommand("serverchangelog", new org.metamechanists.odysseia.commands.ChangelogCommand(this, changelogService));
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.FastMachinesProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(automation, this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.BedrockRangeGuardListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.SlimefunSpawnerLimitListener(this), this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.SunlightBurnListener(this), this);
        this.legacyCombat = new org.metamechanists.odysseia.listeners.LegacyCombatListener(this);
        Bukkit.getPluginManager().registerEvents(legacyCombat, this);
        // Quien ya estaba dentro cuando se recarga el plugin no dispara PlayerJoinEvent.
        legacyCombat.aplicarATodos();
        this.automationGuard = new org.metamechanists.odysseia.listeners.AutomationGuardListener(this);
        Bukkit.getPluginManager().registerEvents(automationGuard, this);
        Bukkit.getPluginManager().registerEvents(new org.metamechanists.odysseia.listeners.AntiAltListener(this), this);
        this.polisBaseline = new PolisBaselineListener(this);
        Bukkit.getPluginManager().registerEvents(polisBaseline, this);
        this.horrorNightScheduler = new org.metamechanists.odysseia.events.HorrorNightScheduler(this);
        Bukkit.getPluginManager().registerEvents(horrorNightScheduler, this);
        this.vipExpiryAlertService = new org.metamechanists.odysseia.services.VipExpiryAlertService(this);
        vipExpiryAlertService.startScheduler();
        this.discordTranslationBridge = new org.metamechanists.odysseia.integrations.DiscordTranslationBridgeService(this);
        this.seasonalChatGames = new org.metamechanists.odysseia.chatgames.SeasonalChatGamesService(this);
        Bukkit.getPluginManager().registerEvents(seasonalChatGames, this);
        horrorNightScheduler.start();
        bloodMoonManager.start();

        this.globalPlaytime = new org.metamechanists.odysseia.services.GlobalPlaytimeService(this);
        globalPlaytime.start();

        this.threadBudgetMonitor = new org.metamechanists.odysseia.services.ThreadBudgetMonitor(this);
        threadBudgetMonitor.start();

        // Register PlaceholderAPI expansion if present
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new OdysseiaPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered successfully.");
        }

        // Start Tasks
        startSchedulers();
        startStarTelemetry();

        // Send startup webhook
        sendStartupWebhook();
        avisarClavesMuertas();

        getLogger().info("Odysseia v" + getPluginMeta().getVersion() + " habilitado correctamente.");
    }

    public KitClaimService getKitClaimService() {
        return kitClaimService;
    }

    /** Puede ser null si el plugin aun no termino de habilitarse. */
    public org.metamechanists.odysseia.welcome.WelcomeMenuService getWelcomeMenuService() {
        return welcomeMenuService;
    }

    public org.metamechanists.odysseia.modalities.ModalityService getModalityService() {
        return modalityService;
    }

    /** Guard shared by typed commands and native-menu actions. */
    public org.metamechanists.odysseia.listeners.ModalityStorageGuardListener getModalityStorageGuard() {
        return modalityStorageGuard;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Avisa al arrancar si el config desplegado conserva claves que el codigo ya no lee.
     *
     * Sin esto son invisibles: Bukkit no se queja de una clave que nadie consulta, asi que quien la
     * configure se queda esperando un efecto que no llega.
     */
    private void avisarClavesMuertas() {
        var avisos = org.metamechanists.odysseia.util.ConfigLegacyKeys.avisos(
                getConfig().getKeys(true));
        for (String aviso : avisos) {
            getLogger().warning("[Config] " + aviso);
        }

        /*
         * Y ademas se borran. Solo avisar no servia de nada: mergeMissingConfig() repone cualquier
         * clave que este en los valores por defecto del jar y falte en el archivo, asi que quitarlas
         * a mano las resucitaba en el arranque siguiente. Se quitaron tambien del recurso del jar,
         * pero eso no toca los archivos ya desplegados.
         *
         * Se anuncia lo que se borra en vez de hacerlo callando: el archivo es de Jack, no mio.
         */
        var muertas = org.metamechanists.odysseia.util.ConfigLegacyKeys.aBorrar(
                getConfig().getKeys(true));
        if (!muertas.isEmpty()) {
            for (String clave : muertas) {
                getConfig().set(clave, null);
            }
            saveConfig();
            getLogger().info("[Config] Borradas " + muertas.size()
                    + " clave(s) muerta(s) del config: " + String.join(", ", muertas));
        }
    }

    /**
     * Vuelca a disco las bovedas de modalidad que sigan abiertas.
     *
     * Viven en su propia base SQLite, asi que {@code save-all} no las toca: sin esto, un reinicio
     * con alguien mirando su boveda le pierde lo que hubiera movido.
     */
    public void flushModalityVaults() {
        if (modalityVaults != null) modalityVaults.flushOpen();
    }

    public int getPurchaseEngineProductCount() {
        return purchaseEngine == null ? 0 : purchaseEngine.catalogProductCount();
    }

    public PurchaseEngine getPurchaseEngine() {
        return purchaseEngine;
    }

    public StarTelemetryPublisher getStarTelemetry() {
        return starTelemetry;
    }

    public List<String> reloadRuntime() {
        List<String> errors = new ArrayList<>();
        reloadConfig();
        mergeDefaultConfig();
        cancelRuntimeTasks();
        if (sfMasterWatcher != null) {
            sfMasterWatcher.reloadConfiguration();
        }

        if (purchaseEngine != null) {
            HandlerList.unregisterAll(purchaseEngine);
            purchaseEngine.close();
        }
        this.purchaseEngine = new PurchaseEngine(this);
        if (starTelemetry == null) {
            this.starTelemetry = new StarTelemetryPublisher(this);
        }
        this.purchaseEngine.setTelemetry(telemetry -> starTelemetry.publishPurchase(purchaseEngine, telemetry));
        getCommand("odysseiapurchase").setExecutor(new org.metamechanists.odysseia.commands.PurchaseCommand(purchaseEngine));
        Bukkit.getPluginManager().registerEvents(purchaseEngine, this);
        if (!purchaseEngine.isReady()) {
            errors.add("purchase-engine: " + purchaseEngine.startupError());
        }

        org.metamechanists.odysseia.commands.KitGiveCommand validator = new org.metamechanists.odysseia.commands.KitGiveCommand(this);
        errors.addAll(validator.validateConfiguration().stream().map(error -> "kits: " + error).toList());

        // Servicios que leen su configuracion una sola vez al arrancar. Sin esto un
        // /odysseia reload decia "recarga completa sin errores" y dejaba el guard con la lista
        // vieja: la separacion entre modalidades siguio abierta despues de un reinicio porque
        // nadie volvia a leer modalidades.guard.
        if (modalityService != null) {
            modalityService.reload();
        }
        if (modalityStorageGuard != null) {
            modalityStorageGuard.reload();
        } else {
            errors.add("modalidades: el guard de almacenamiento no esta activo");
        }

        startSchedulers();
        startStarTelemetry();
        if (discordTranslationBridge != null) discordTranslationBridge.reload();
        if (deathMessages != null) deathMessages.reload();
        if (papaService != null) papaService.reload();
        if (legacyCombat != null) legacyCombat.reload();
        // Si acaban de configurar el webhook, el aviso debe poder volver a salir.
        org.metamechanists.odysseia.utils.StoreManager.resetDiscordWarning();
        getLogger().info("[Reload] Runtime recargado: config.yml, purchases.yml, modalidades, schedulers y purchase engine.");
        return errors;
    }

    /** Crea una copia de respaldo con fecha y hora de los archivos de configuración para no perder ajustes. */
    private void backupConfigurationFiles() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            File backupsDir = new File(dataFolder, "backups");
            if (!backupsDir.exists()) {
                backupsDir.mkdirs();
            }

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = sdf.format(new java.util.Date());

            for (String fileName : List.of("config.yml", "purchases.yml")) {
                File target = new File(dataFolder, fileName);
                if (target.exists() && target.length() > 0) {
                    File backupFile = new File(backupsDir, fileName.replace(".yml", "") + "_backup_" + timestamp + ".yml");
                    java.nio.file.Files.copy(target.toPath(), backupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            getLogger().warning("[Backup] No se pudo crear backup preventivo de configuración: " + e.getMessage());
        }
    }

    /** Persists newly introduced defaults while keeping every configured production value. */
    private void mergeDefaultConfig() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("El JAR no contiene config.yml");
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            boolean changed = mergeMissingConfig(getConfig(), defaults);
            changed |= adoptEmptyListDefaults(getConfig(), defaults);
            changed |= ensureRequiredModalityDefaults(getConfig(), defaults);
            changed |= migrateUnsafeLegacyDefaults(getConfig());
            if (changed) {
                saveConfig();
            }
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "No se pudo ampliar config.yml con los valores por defecto", error);
            throw new IllegalStateException("No se pudo migrar config.yml", error);
        }
    }

    /**
     * Rutas de lista donde una lista vacia en produccion significa "todavia sin configurar" y hay
     * que adoptar el valor del JAR.
     *
     * El merge normal solo agrega claves ausentes, asi que una lista que ya existia vacia nunca se
     * llenaba. Paso en produccion: el JAR traia los diez comandos que cierran el paso de items
     * entre modalidades y el servidor arranco con la lista vacia de la version anterior, dejando
     * la fuga abierta sin ningun aviso.
     */
    private static final List<String> ADOPT_IF_EMPTY = List.of(
            "modalidades.guard.comandos-bloqueados",
            "modalidades.guard.comandos-bloqueados-siempre",
            "modalidades.guard.comandos-boveda",
            "modalidades.guard.mundos-boveda-global",
            "modalidades.guard.modalidades-aisladas");

    static boolean adoptEmptyListDefaults(ConfigurationSection current, ConfigurationSection defaults) {
        boolean changed = false;
        for (String path : ADOPT_IF_EMPTY) {
            List<?> porDefecto = defaults.getList(path);
            if (porDefecto == null || porDefecto.isEmpty()) {
                continue;
            }
            List<?> actual = current.getList(path);
            if (actual != null && !actual.isEmpty()) {
                continue;
            }
            current.set(path, porDefecto);
            changed = true;
        }
        return changed;
    }

    /** Adds newly isolated modalities without replacing any production-only entries. */
    static boolean ensureRequiredModalityDefaults(ConfigurationSection current, ConfigurationSection defaults) {
        String path = "modalidades.guard.modalidades-aisladas";
        List<String> configured = new ArrayList<>(current.getStringList(path));
        boolean changed = false;
        for (String required : defaults.getStringList(path)) {
            if (configured.stream().noneMatch(value -> value.equalsIgnoreCase(required))) {
                configured.add(required);
                changed = true;
            }
        }
        if (changed) {
            current.set(path, configured);
        }
        return changed;
    }

    static boolean mergeMissingConfig(ConfigurationSection current, ConfigurationSection defaults) {
        boolean changed = false;
        Map<String, Object> currentValues = current.getValues(false);

        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);
            if (defaultValue instanceof ConfigurationSection defaultSection) {
                ConfigurationSection currentSection = current.getConfigurationSection(key);
                if (currentSection == null) {
                    currentSection = current.createSection(key);
                    changed = true;
                }
                changed |= mergeMissingConfig(currentSection, defaultSection);
            } else if (!currentValues.containsKey(key)) {
                current.set(key, defaultValue);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Removes superseded duplicate systems and upgrades only known unsafe
     * defaults, preserving deliberate production tuning everywhere else.
     */
    static boolean migrateUnsafeLegacyDefaults(ConfigurationSection config) {
        boolean changed = false;
        if (config.contains("starter-kit.items") || config.contains("starter-kit.commands")) {
            config.set("starter-kit.items", null);
            config.set("starter-kit.commands", null);
            changed = true;
        }
        if (config.getLong("starter-kit.delay-ticks", 400L) <= 100L) {
            config.set("starter-kit.delay-ticks", 400L);
            changed = true;
        }
        if (config.getLong("translation.join-delay-ticks", 400L) <= 60L) {
            config.set("translation.join-delay-ticks", 400L);
            changed = true;
        }
        if (config.getInt("automation-guard.redstone.fast-pulse-limit", 40) == 12) {
            config.set("automation-guard.redstone.fast-pulse-limit", 40);
            changed = true;
        }
        if (config.getInt("automation-guard.redstone.long-pulse-limit", 180) == 8) {
            config.set("automation-guard.redstone.long-window-seconds", 120);
            config.set("automation-guard.redstone.long-pulse-limit", 180);
            changed = true;
        }
        return changed;
    }

    /** Combate 1.8: se guarda para poder retirar el modificador al apagar el plugin. */
    private org.metamechanists.odysseia.listeners.LegacyCombatListener legacyCombat;

    @Override
    public void onDisable() {
        if (economyWatchdog != null) {
            economyWatchdog.stop();
        }
        if (legacyCombat != null) {
            legacyCombat.retirarDeTodos();
        }
        if (discordTranslationBridge != null) {
            discordTranslationBridge.unregisterDiscordSRV();
        }
        if (ultraGodCommand != null) {
            ultraGodCommand.disableAll();
        }
        if (vanishCommand != null) {
            vanishCommand.revealAll();
        }

        // Send shutdown webhook synchronously
        sendShutdownWebhookSync();

        if (purchaseEngine != null) purchaseEngine.close();
        if (globalPlaytime != null) globalPlaytime.close();
        if (chequeService != null) {
            try {
                chequeService.close();
            } catch (java.sql.SQLException error) {
                getLogger().log(Level.WARNING, "[Cheques] No se pudo cerrar el libro contable", error);
            }
        }

        // Guarda las bovedas que sigan abiertas antes de cerrar la base.
        if (modalityVaults != null) modalityVaults.close();
        if (sandboxStash != null) {
            try {
                sandboxStash.close();
            } catch (java.sql.SQLException error) {
                getLogger().log(Level.WARNING, "[Laboratorio] No se pudo cerrar la consigna", error);
            }
        }

        if (bloodMoonManager != null) {
            bloodMoonManager.shutdown();
        }
        if (horrorNightScheduler != null) {
            horrorNightScheduler.shutdown();
        }
        if (dragonMountService != null) {
            dragonMountService.shutdown();
        }
        if (automationGuard != null) {
            automationGuard.shutdown();
        }
        if (saoriFreezeManager != null) {
            saoriFreezeManager.cleanup();
        }
        if (eventManager != null) {
            eventManager.shutdown();
        }


        getLogger().info("Odysseia v" + getPluginMeta().getVersion() + " deshabilitado correctamente.");
    }

    public boolean isVanished(Player player) {
        return vanishCommand != null && vanishCommand.isVanished(player);
    }

    // Owner Cycle Prefix Support
    public String getCurrentOdiseoPrefix() {
        if (!getConfig().getBoolean("owner-cycle.enabled", true)) {
            return getConfig().getString("owner-cycle.odiseo.prefix-a", "&8✠ &6&lᴏᴅɪsᴇᴏ &8✦ &6&o");
        }
        return ownerFlip
                ? getConfig().getString("owner-cycle.odiseo.prefix-b", "&8✠ &4&lᴅᴜᴇñᴏ &8✦ &4")
                : getConfig().getString("owner-cycle.odiseo.prefix-a", "&8✠ &6&lᴏᴅɪsᴇᴏ &8✦ &6&o");
    }

    public String getCurrentPenelopePrefix() {
        if (!getConfig().getBoolean("owner-cycle.enabled", true)) {
            return getConfig().getString("owner-cycle.penelope.prefix-a", "&d✦ &d&lᴘᴇɴᴇʟᴏᴘᴇ &8✦ &d");
        }
        return ownerFlip
                ? getConfig().getString("owner-cycle.penelope.prefix-b", "&d✦ &d&lᴅᴜᴇñᴀ &8✦ &d")
                : getConfig().getString("owner-cycle.penelope.prefix-a", "&d✦ &d&lᴘᴇɴᴇʟᴏᴘᴇ &8✦ &d");
    }

    /**
     * Escapa una cadena para incrustarla en un literal JSON.
     *
     * Escapar solo la barra y las comillas no basta: el mensaje de baneo de vanilla lleva un salto
     * de linea literal ("You are banned from this server.\nReason: ..."), y un caracter de control
     * sin escapar dentro de una cadena JSON es sintaxis invalida, asi que Discord respondia 400 y
     * el aviso de moderacion se perdia. Cualquier texto que venga del juego puede traerlos.
     */
    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Tope de Discord para el valor de un campo de embed. */
    public static final int LIMITE_CAMPO_DISCORD = 1024;

    /**
     * Escapa un texto del juego para un campo de embed sin pasarse del tope de Discord.
     *
     * Escapar los caracteres de control arregla la sintaxis, pero no la longitud: un motivo de
     * sancion largo o el mensaje de kick detallado de otro plugin pasa de 1024 caracteres, Discord
     * vuelve a responder 400 y el aviso de moderacion se pierde dejando solo un WARN. Se recorta
     * antes de escapar, asi el corte nunca parte una secuencia de escape a la mitad.
     */
    public static String escapeJsonCampo(String s) {
        return escapeJson(recortar(s, LIMITE_CAMPO_DISCORD));
    }

    /**
     * Recorta a {@code maximo} caracteres marcando el corte con puntos suspensivos.
     *
     * Discord cuenta el texto ya decodificado, no el JSON escapado, por eso se mide sobre el
     * original. Si el corte cae entre los dos char de un par suplente se retrocede uno: dejar un
     * suplente suelto produciria una cadena invalida.
     */
    public static String recortar(String s, int maximo) {
        if (s == null || s.length() <= maximo) {
            return s;
        }
        int corte = maximo - 1;
        if (Character.isHighSurrogate(s.charAt(corte - 1))) {
            corte--;
        }
        return s.substring(0, corte) + "\u2026";
    }

    private String loadOrCreateInstanceId() {
        java.io.File file = new java.io.File(getDataFolder(), "data.yml");
        org.bukkit.configuration.file.YamlConfiguration data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        String id = data.getString("instance-id");
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString();
            data.set("instance-id", id);
            try {
                data.save(file);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error al guardar data.yml", e);
            }
        }
        return id;
    }

    private void startSchedulers() {
        startOwnerCycleScheduler();
        startChatGamesScheduler();
        startLenadorLocoScheduler();
        startPapaDeMarDeliveryScheduler();
        startHeartbeatScheduler();
        startPolisBaselineScheduler();
    }

    private void startPolisBaselineScheduler() {
        if (polisBaseline == null || !getConfig().getBoolean("polis-baseline.enabled", true)) {
            return;
        }
        long interval = Math.max(30L, getConfig().getLong("polis-baseline.reconcile-interval-seconds", 60));
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, polisBaseline::reconcileOnlinePlayers, 20L, interval * 20L));
    }

    private void trackRuntimeTask(BukkitTask task) {
        runtimeTasks.add(task);
    }

    private void cancelRuntimeTasks() {
        for (BukkitTask task : runtimeTasks) {
            if (task != null) {
                task.cancel();
            }
        }
        runtimeTasks.clear();
    }

    private void startStarTelemetry() {
        if (starTelemetry == null || !getConfig().getBoolean("star-monitor.enabled", false)) {
            return;
        }
        int intervalSeconds = Math.max(30, getConfig().getInt("star-monitor.heartbeat-interval-seconds", 60));
        trackRuntimeTask(Bukkit.getScheduler().runTaskLater(this, () -> starTelemetry.publishStartup(purchaseEngine), 40L));
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, () -> starTelemetry.publishHeartbeat(purchaseEngine), intervalSeconds * 20L, intervalSeconds * 20L));
    }

    private void startOwnerCycleScheduler() {
        if (!getConfig().getBoolean("owner-cycle.enabled", true)) {
            return;
        }
        int intervalMinutes = getConfig().getInt("owner-cycle.interval-minutes", 30);
        long periodTicks = intervalMinutes * 60L * 20L;
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (isOwnerOnline()) {
                ownerFlip = !ownerFlip;
            }
        }, periodTicks, periodTicks));
    }

    private boolean isOwnerOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("group.odiseo") || p.hasPermission("group.penelope")) {
                return true;
            }
            String name = p.getName().toLowerCase();
            if (name.equals("odiseo") || name.equals("penelope")) {
                return true;
            }
        }
        return false;
    }

    private void startChatGamesScheduler() {
        if (seasonalChatGames == null || !getConfig().getBoolean("chatgames.enabled", true)) {
            getLogger().info("[ChatGames] Motor semanal deshabilitado (chatgames.enabled: false).");
            return;
        }
        seasonalChatGames.resetSchedule();
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, seasonalChatGames::tick, 20L, 20L));
    }

    private void startLenadorLocoScheduler() {
        if (!getConfig().getBoolean("lenador-loco.enabled", true)) {
            return;
        }
        long intervalTicks = Math.max(1, getConfig().getLong("lenador-loco.check-interval-seconds", 5)) * 20L;
        String targetName = getConfig().getString("lenador-loco.target-name", "Leñador Loco");
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (org.bukkit.entity.LivingEntity entity : world.getLivingEntities()) {
                    String name = entity.getCustomName();
                    if (name != null) {
                        String stripped = ChatColor.stripColor(name);
                        if (stripped.equalsIgnoreCase(targetName)) {
                            applyConfiguredLenadorEffects(entity);
                        }
                    }
                }
            }
        }, intervalTicks, intervalTicks));
    }

    private void applyConfiguredLenadorEffects(org.bukkit.entity.LivingEntity entity) {
        List<java.util.Map<?, ?>> effects = getConfig().getMapList("lenador-loco.effects");
        if (effects.isEmpty()) {
            effects = List.of(
                    java.util.Map.of("type", "RESISTANCE", "duration-seconds", 12, "amplifier", 1),
                    java.util.Map.of("type", "REGENERATION", "duration-seconds", 12, "amplifier", 1),
                    java.util.Map.of("type", "STRENGTH", "duration-seconds", 12, "amplifier", 0),
                    java.util.Map.of("type", "SPEED", "duration-seconds", 12, "amplifier", 0)
            );
        }
        for (java.util.Map<?, ?> raw : effects) {
            String typeName = text(raw.get("type"), "").toUpperCase(Locale.ROOT);
            org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(typeName);
            if (type == null) {
                getLogger().warning("[Lenador] Efecto inválido en config: " + typeName);
                continue;
            }
            int duration = Math.max(1, parseInt(raw.get("duration-seconds"), 12)) * 20;
            int amplifier = Math.max(0, parseInt(raw.get("amplifier"), 0));
            entity.addPotionEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier, true, false, false));
        }
    }

    private int parseInt(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private void startPapaDeMarDeliveryScheduler() {
        if (!getConfig().getBoolean("papa-de-mar.delivery.enabled", true)) {
            return;
        }
        int deliveryMinutes = getConfig().getInt("papa-de-mar.delivery.interval-minutes",
                getConfig().getInt("papa-de-mar.delivery-interval-minutes", 15));
        long periodTicks = deliveryMinutes * 60L * 20L;
        trackRuntimeTask(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                return;
            }
            org.bukkit.inventory.ItemStack potato = createPapaDeMarItem();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getInventory().addItem(potato);
                p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        getConfig().getString("papa-de-mar.delivery.message", "&eRecibiste la &6&l✦ Papa de mar ✦&e.")));
            }
        }, periodTicks, periodTicks));
    }

    public org.bukkit.inventory.ItemStack createPapaDeMarItem() {
        String materialName = getConfig().getString("papa-de-mar.item.material", "BAKED_POTATO");
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName == null ? "BAKED_POTATO" : materialName);
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material == null ? org.bukkit.Material.BAKED_POTATO : material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("papa-de-mar.item.name", "&6&l✦ Papa de mar ✦")));
            List<String> lore = getConfig().getStringList("papa-de-mar.item.lore").stream()
                    .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                    .toList();
            if (lore.isEmpty()) {
                lore = List.of(
                        ChatColor.translateAlternateColorCodes('&', "&7Relicario legendario de Drakes."),
                        ChatColor.translateAlternateColorCodes('&', "&7Ha cruzado temporadas, guerras y treguas."),
                        ChatColor.translateAlternateColorCodes('&', "&7A veces se canjeó por llaves ante aldeanos."),
                        ChatColor.translateAlternateColorCodes('&', "&7A veces fue comida, spam y pura leyenda."),
                        ChatColor.translateAlternateColorCodes('&', "&8Dicen que con esta papa terminó una guerra."),
                        ChatColor.translateAlternateColorCodes('&', "&8Y que quien la porta carga historia en las manos.")
                );
            }
            meta.setLore(lore);
            for (String raw : getConfig().getStringList("papa-de-mar.item.enchantments")) {
                String[] parts = raw.split(":", 2);
                org.bukkit.enchantments.Enchantment enchantment = org.bukkit.enchantments.Enchantment.getByName(parts[0].toUpperCase(Locale.ROOT));
                if (enchantment == null) {
                    getLogger().warning("[PapaDeMar] Encantamiento inválido en config: " + raw);
                    continue;
                }
                int level = parts.length > 1 ? parseInt(parts[1], 1) : 1;
                meta.addEnchant(enchantment, level, true);
            }
            item.setItemMeta(meta);
        }
        // La marca interna va aqui, en el unico sitio por donde salen todas las papas del
        // servidor: el reparto automatico y /papademar. Sin ella el trueque no la acepta, y con
        // ella no se puede falsificar con un yunque.
        return org.metamechanists.odysseia.papa.PapaDeMarItem.marcar(this, item);
    }

    private void startHeartbeatScheduler() {
        if (!getConfig().getBoolean("presence.enabled", true)
                || !getConfig().getBoolean("presence.heartbeat.enabled", false)) {
            return;
        }
        String url = getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank() || url.equals("REPLACE_ME") || !WebhookSender.isAllowedHttpsUrl(url)) {
            return;
        }
        int minutes = Math.max(15, getConfig().getInt("presence.heartbeat.interval-minutes", 15));
        long periodTicks = minutes * 60L * 20L;
        long delayTicks = Math.max(20L, getConfig().getInt("presence.startup-delay-seconds", 60) * 20L);

        trackRuntimeTask(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                String serverLabel = getConfig().getString("presence.server-label", "");
                if (serverLabel == null || serverLabel.isBlank()) {
                    serverLabel = Bukkit.getServer().getName();
                }
                boolean sf = Bukkit.getPluginManager().getPlugin("Slimefun") != null;
                String sfVer = "";
                if (sf) {
                    var pl = Bukkit.getPluginManager().getPlugin("Slimefun");
                    if (pl != null) {
                        sfVer = pl.getPluginMeta().getVersion();
                    }
                }
                String paperMc = Bukkit.getMinecraftVersion();
                String ts = java.time.Instant.now().toString();
                
                String desc = "**instanceId:** `" + instanceId + "` | **MC:** " + paperMc
                        + " | **Slimefun:** " + (sf ? "sí (`" + sfVer + "`)" : "no")
                        + " | **Plugin:** `Odysseia v" + getPluginMeta().getVersion() + "` | **UTC:** " + ts;
                
                String json = "{\"username\":\"Odysseia Heartbeat\",\"embeds\":[{\"title\":\"Drakes lab heartbeat\",\"description\":\""
                        + escapeJson(desc) + "\",\"color\":5814783,\"fields\":[{\"name\":\"Servidor\",\"value\":\""
                        + escapeJsonCampo(serverLabel) + "\",\"inline\":false}]}]}";

                WebhookSender.sendAsync(this, url, json);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error al enviar latido a Discord", e);
            }
        }, delayTicks, periodTicks));
    }

    private void sendStartupWebhook() {
        if (!getConfig().getBoolean("presence.enabled", true)
                || !getConfig().getBoolean("presence.events.server-startup", true)) {
            return;
        }
        String url = getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank() || url.equals("REPLACE_ME") || !WebhookSender.isAllowedHttpsUrl(url)) {
            return;
        }
        long delayTicks = Math.max(20L, getConfig().getInt("presence.startup-delay-seconds", 60) * 20L);
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            String serverLabel = getConfig().getString("presence.server-label", "");
            if (serverLabel == null || serverLabel.isBlank()) {
                serverLabel = Bukkit.getServer().getName();
            }
            String mc = Bukkit.getMinecraftVersion();
            boolean sf = Bukkit.getPluginManager().getPlugin("Slimefun") != null;
            String desc = "Servidor **" + serverLabel + "** en línea · MC `" + mc + "` · Slimefun: " + (sf ? "sí" : "no");
            
            String json = "{\"username\":\"Odysseia\",\"embeds\":[{\"title\":\"Arranque\",\"description\":\""
                    + escapeJson(desc) + "\",\"color\":3066993,\"footer\":{\"text\":\""
                    + escapeJson(instanceId + " · " + serverLabel) + "\"}}]}";
            
            WebhookSender.sendAsync(this, url, json);
        }, delayTicks);
    }

    private void sendShutdownWebhookSync() {
        if (!getConfig().getBoolean("presence.enabled", true)
                || !getConfig().getBoolean("presence.events.server-shutdown", true)) {
            return;
        }
        String url = getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank() || url.equals("REPLACE_ME") || !WebhookSender.isAllowedHttpsUrl(url)) {
            return;
        }
        String serverLabel = getConfig().getString("presence.server-label", "");
        if (serverLabel == null || serverLabel.isBlank()) {
            serverLabel = Bukkit.getServer().getName();
        }
        String desc = "Apagado o recarga de **" + serverLabel + "**.";
        
        String json = "{\"username\":\"Odysseia\",\"embeds\":[{\"title\":\"Apagado\",\"description\":\""
                + escapeJson(desc) + "\",\"color\":15105570,\"footer\":{\"text\":\""
                + escapeJson(instanceId + " · " + serverLabel) + "\"}}]}";
        
        WebhookSender.sendSyncBestEffort(this, url, json);
    }

    public BloodMoonManager getBloodMoonManager() {
        return this.bloodMoonManager;
    }

    public org.metamechanists.odysseia.services.VipExpiryAlertService getVipExpiryAlertService() {
        return vipExpiryAlertService;
    }

    public org.metamechanists.odysseia.listeners.SFMasterWatcherListener getSfMasterWatcher() {
        return sfMasterWatcher;
    }

    public org.metamechanists.odysseia.listeners.ResourcePackListener getResourcePack() {
        return resourcePack;
    }

    public org.metamechanists.odysseia.economy.EconomyWatchdog getEconomyWatchdog() {
        return economyWatchdog;
    }

    public org.metamechanists.odysseia.listeners.MaintenanceGuardListener getMaintenanceGuard() {
        return maintenanceGuard;
    }

    public org.metamechanists.odysseia.services.ServerChangelogService getChangelogService() {
        return changelogService;
    }

    public void registerDynamicCommand(String name, org.bukkit.command.CommandExecutor executor) {
        try {
            org.bukkit.command.PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(executor);
            } else {
                java.lang.reflect.Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap) commandMapField.get(Bukkit.getServer());
                org.bukkit.command.defaults.BukkitCommand bukkitCmd = new org.bukkit.command.defaults.BukkitCommand(name) {
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                        return executor.onCommand(sender, this, commandLabel, args);
                    }
                };
                commandMap.register(getName(), bukkitCmd);
                getLogger().info("[CommandMap] Comando /" + name + " registrado dinámicamente en tiempo de ejecución.");
            }
        } catch (Throwable e) {
            getLogger().warning("No se pudo registrar dinámicamente el comando " + name + ": " + e.getMessage());
        }
    }
}
