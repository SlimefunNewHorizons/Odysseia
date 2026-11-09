package org.metamechanists.odysseia.purchase;

import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PurchaseServiceTest {
    private File directory;
    private PurchaseRepository repository;
    private FakeRuntime runtime;
    private PurchaseService service;

    @BeforeEach void setUp() throws Exception {
        directory = Files.createTempDirectory("odysseia-purchase-test").toFile();
        repository = new PurchaseRepository(new File(directory, "purchases.db"));
        runtime = new FakeRuntime();
        ProductDefinition product = new ProductDefinition("dragmas_saco", 1, "Saco", "economy", "test", "test", 1,
                VerificationState.VERIFIED_PRODUCTION, List.of(), List.of(
                new ProductAction("money", ActionType.ECONOMY, Map.of("amount", "50000"), false, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("kit", ActionType.KIT, Map.of("kit", "test"), true, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("announce", ActionType.ANNOUNCEMENT, Map.of(), false, RefundPolicy.NOT_REVOCABLE, true)));
        service = new PurchaseService(new ProductCatalog(List.of(product)), repository, runtime);
    }

    @AfterEach void tearDown() throws Exception { repository.close(); delete(directory); }

    @Test void sameTransactionIsIdempotentAndAnnouncementIsUnique() throws Exception {
        runtime.online = true;
        assertTrue(service.deliver("txn-1", "TestPlayer", "dragmas_saco", false, "test").success());
        assertTrue(service.deliver("txn-1", "TestPlayer", "dragmas_saco", false, "test").success());
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
        assertEquals(PurchaseState.COMPLETED, service.status("txn-1").getFirst().state());
    }

    @Test void tebexDeliveryExecutesRewardsAndQueuesItsAnnouncement() throws Exception {
        runtime.online = true;

        assertTrue(service.deliver("test-tebex-flow", "TestPlayer", "dragmas_saco", false, "CONSOLE_TEST").success());

        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
        assertEquals(PurchaseState.COMPLETED, service.status("test-tebex-flow").getFirst().state());
    }

    @Test void differentTransactionsCanPurchaseSameProduct() throws Exception {
        runtime.online = true;
        service.deliver("txn-1", "TestPlayer", "dragmas_saco", false, "test");
        service.deliver("txn-2", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(2, runtime.calls(ActionType.ECONOMY));
        assertEquals(2, service.status("txn-1").size() + service.status("txn-2").size());
    }

    @Test void offlineActionResumesOnlyWhatWasPending() throws Exception {
        runtime.online = false;
        service.deliver("txn-offline", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(0, runtime.calls(ActionType.KIT));
        assertEquals(PurchaseState.WAITING_FOR_PLAYER, service.status("txn-offline").getFirst().state());
        runtime.online = true;
        service.resumePlayer("TestPlayer", "join");
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
    }

    @Test void retryExecutesOnlyFailedAction() throws Exception {
        runtime.online = true;
        runtime.failKitOnce = true;
        service.deliver("txn-retry", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(PurchaseState.FAILED_RETRYABLE, service.status("txn-retry").getFirst().state());
        service.retry("txn-retry", "admin");
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(2, runtime.calls(ActionType.KIT));
        assertEquals(PurchaseState.COMPLETED, service.status("txn-retry").getFirst().state());
    }

    @Test void failedAnnouncementDoesNotBlockDeliveredRewards() throws Exception {
        runtime.online = true;
        runtime.failAnnouncementOnce = true;
        service.deliver("txn-announce-retry", "TestPlayer", "dragmas_saco", false, "test");

        long deliveryId = service.status("txn-announce-retry").getFirst().id();
        assertEquals(PurchaseState.COMPLETED, service.status("txn-announce-retry").getFirst().state());
        assertEquals(ActionState.FAILED_RETRYABLE, repository.actions(deliveryId).stream()
                .filter(action -> action.type() == ActionType.ANNOUNCEMENT).findFirst().orElseThrow().state());
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
    }

    @Test void concurrentDuplicateEventsStillDeliverOnce() throws Exception {
        runtime.online = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> service.deliver("txn-race", "TestPlayer", "dragmas_saco", false, "test"));
        Future<?> second = executor.submit(() -> service.deliver("txn-race", "TestPlayer", "dragmas_saco", false, "test"));
        first.get(); second.get(); executor.shutdown();
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
    }

    @Test void financialEventIsIdempotentAndMarksManualActionsForReview() throws Exception {
        runtime.online = true;
        service.deliver("txn-refund", "TestPlayer", "dragmas_saco", false, "test");
        assertTrue(service.financialEvent("txn-refund", "dragmas_saco", false, "admin").success());
        assertTrue(service.financialEvent("txn-refund", "dragmas_saco", false, "admin").success());
        assertEquals(PurchaseState.REFUNDED, service.status("txn-refund").getFirst().state());
        assertTrue(repository.actions(service.status("txn-refund").getFirst().id()).stream().anyMatch(action -> action.state() == ActionState.FAILED_MANUAL_REVIEW));
    }

    @Test void chargebackUsesTheSameFinancialIdempotencyGuard() throws Exception {
        runtime.online = true;
        service.deliver("txn-chargeback", "TestPlayer", "dragmas_saco", false, "test");
        assertTrue(service.financialEvent("txn-chargeback", "dragmas_saco", true, "admin").success());
        assertTrue(service.financialEvent("txn-chargeback", "dragmas_saco", true, "admin").success());
        assertEquals(PurchaseState.CHARGEBACK, service.status("txn-chargeback").getFirst().state());
    }

    @Test void restartResumesOnlyPendingActions() throws Exception {
        runtime.online = false;
        service.deliver("txn-restart", "TestPlayer", "dragmas_saco", false, "test");
        repository.close();
        repository = new PurchaseRepository(new File(directory, "purchases.db"));
        runtime.online = true;
        ProductDefinition product = service.catalog().get("dragmas_saco");
        service = new PurchaseService(new ProductCatalog(List.of(product)), repository, runtime);
        service.resumePlayer("TestPlayer", "restart");
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(PurchaseState.COMPLETED, service.status("txn-restart").getFirst().state());
    }

    @Test void retryActionDoesNotRepeatCompletedActions() throws Exception {
        runtime.online = true;
        runtime.failKitOnce = true;
        service.deliver("txn-action", "TestPlayer", "dragmas_saco", false, "test");
        long actionId = repository.actions(service.status("txn-action").getFirst().id()).stream()
                .filter(action -> action.type() == ActionType.KIT).findFirst().orElseThrow().id();
        service.retryAction("txn-action", actionId, "admin");
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(2, runtime.calls(ActionType.KIT));
    }

    @Test void unavailableDependencyRemainsRetryable() throws Exception {
        runtime.online = true;
        runtime.failEconomy = true;
        service.deliver("txn-dependency", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(PurchaseState.FAILED_RETRYABLE, service.status("txn-dependency").getFirst().state());
        assertEquals(0, runtime.calls(ActionType.ANNOUNCEMENT));
    }

    @Test void optionalOfflineNotificationDoesNotBlockCompletion() throws Exception {
        ProductDefinition product = new ProductDefinition("notify_optional", 2, "Optional Notify", "economy", "test", "test", 1,
                VerificationState.VERIFIED_PRODUCTION, List.of(), List.of(
                new ProductAction("money", ActionType.ECONOMY, Map.of("amount", "1"), false, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("notify", ActionType.NOTIFICATION, Map.of("message", "ok"), true, RefundPolicy.NOT_REVOCABLE, false),
                new ProductAction("announce", ActionType.ANNOUNCEMENT, Map.of(), false, RefundPolicy.NOT_REVOCABLE, true)));
        service = new PurchaseService(new ProductCatalog(List.of(product)), repository, runtime);
        runtime.online = false;
        service.deliver("txn-optional-notify", "TestPlayer", "notify_optional", false, "test");
        assertEquals(PurchaseState.COMPLETED, service.status("txn-optional-notify").getFirst().state());
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
    }

    @Test void invalidInputAndUnknownProductAreRejected() {
        assertFalse(service.deliver("x", "bad name", "missing", false, "test").success());
        assertFalse(service.deliver("txn", "TestPlayer", "missing", false, "test").success());
    }

    @Test void packagedCatalogHasAllAuditedProductsAndNoDuplicateTebexIds() {
        ProductCatalog catalog = new ProductCatalog(new File("src/main/resources/purchases.yml"));
        assertEquals(52, catalog.all().size());
        assertTrue(catalog.validate().isEmpty());
        assertEquals(VerificationState.VERIFIED_PRODUCTION, catalog.get("protection_481").verification());
        assertEquals(ActionType.PROTECTION_STONE, catalog.get("protection_481").actions().getFirst().type());
        assertTrue(catalog.get("vip_hermes").actions().stream().noneMatch(action -> action.id().equals("base-rank")));
        assertEquals("viphermes", action(catalog, "vip_hermes", "claim").parameters().get("alias"));
        assertEquals("vipzeus", action(catalog, "vip_zeus", "claim").parameters().get("alias"));
        assertEquals("viphefesto", catalog.get("protection_177").actions().getFirst().parameters().get("alias"));
        assertEquals("vipzeus", catalog.get("protection_481").actions().getFirst().parameters().get("alias"));
        assertEquals(VerificationState.VERIFIED_PRODUCTION, catalog.get("protection_atlas").verification());
        assertEquals("atlas", catalog.get("protection_atlas").actions().getFirst().parameters().get("alias"));
        assertEquals("nethercolossus", catalog.get("protection_nether_colossus").actions().getFirst().parameters().get("alias"));
    }

    @Test void refundedPurchaseIsNotReprocessedAndKeepsItsState() throws Exception {
        runtime.online = true;
        service.deliver("txn-refund-replay", "TestPlayer", "dragmas_saco", false, "test");
        assertTrue(service.financialEvent("txn-refund-replay", "dragmas_saco", false, "admin").success());

        // Tebex puede reenviar el comando de entrega, y un admin puede lanzar un retry a ciegas.
        // Ninguno de los dos debe borrar el REFUNDED ni volver a ejecutar acciones.
        assertFalse(service.deliver("txn-refund-replay", "TestPlayer", "dragmas_saco", false, "test").success());
        service.retry("txn-refund-replay", "admin");

        assertEquals(PurchaseState.REFUNDED, service.status("txn-refund-replay").getFirst().state());
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
    }

    @Test void chargebackDoesNotDeliverActionsThatWerePendingWhenItArrived() throws Exception {
        // El caso caro: el jugador nunca entro, asi que el kit quedo WAITING_FOR_PLAYER y el
        // contracargo no lo pudo revocar --solo revoca lo que estaba COMPLETED--. Si despues
        // alguien reprocesa la transaccion, ese kit se entregaria sin cobro detras.
        runtime.online = false;
        service.deliver("txn-chargeback-pending", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(0, runtime.calls(ActionType.KIT));
        assertTrue(service.financialEvent("txn-chargeback-pending", "dragmas_saco", true, "admin").success());

        runtime.online = true;
        service.retry("txn-chargeback-pending", "admin");
        service.resumePlayer("TestPlayer", "join");

        assertEquals(0, runtime.calls(ActionType.KIT));
        assertEquals(PurchaseState.CHARGEBACK, service.status("txn-chargeback-pending").getFirst().state());
    }

    @Test void cancelledPurchaseStaysCancelledAfterAnAdminRetry() throws Exception {
        runtime.online = false;
        service.deliver("txn-cancelled", "TestPlayer", "dragmas_saco", false, "test");
        assertTrue(service.adminState("txn-cancelled", "cancel", "admin").success());

        runtime.online = true;
        service.retry("txn-cancelled", "admin");

        assertEquals(PurchaseState.CANCELLED, service.status("txn-cancelled").getFirst().state());
        assertEquals(0, runtime.calls(ActionType.KIT));
    }

    @Test void onlineCheckUsesTheCanonicalNameNotTheNameTebexSent() throws Exception {
        // Tebex manda el nick que el comprador escribio; el canonico de una cuenta Bedrock lleva
        // un punto delante. La entrega nace sin identidad, se resuelve al reprocesarla, y hasta
        // ahora la comprobacion de "esta conectado" seguia preguntando por el nick de Tebex:
        // el jugador estaba en linea como ".TestPlayer" y su kit se quedaba esperando para siempre.
        PurchaseService resolving = new PurchaseService(service.catalog(), repository, runtime,
                new PlayerIdentityResolver(repository));
        runtime.online = true;

        resolving.deliver("txn-bedrock", "TestPlayer", "dragmas_saco", false, "test");
        assertEquals(PurchaseState.FAILED_MANUAL_REVIEW, resolving.status("txn-bedrock").getFirst().state());
        assertNull(resolving.status("txn-bedrock").getFirst().uuid());

        UUID uuid = UUID.nameUUIDFromBytes("bedrock".getBytes());
        repository.observeIdentity(uuid, ".TestPlayer", "BEDROCK", "PLAYER_JOIN", "HIGH");
        repository.observeAlias(uuid, ".TestPlayer", "CANONICAL", "HIGH");
        repository.observeAlias(uuid, "TestPlayer", "BEDROCK_NORMALIZED", "HIGH");
        runtime.onlyOnlineFor = ".TestPlayer";

        resolving.recover("test");

        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(PurchaseState.COMPLETED, resolving.status("txn-bedrock").getFirst().state());
    }

    private PurchaseService subscriptionService() {
        ProductDefinition sub = new ProductDefinition("sub_rank", 3, "Sub", "ranks", "t", "t", 1, VerificationState.VERIFIED_PRODUCTION, List.of(), List.of(
                new ProductAction("rank", ActionType.LUCKPERMS_TEMPORARY, Map.of("group", "g", "duration", "30d"), false, RefundPolicy.AUTO_REVOKE, true),
                new ProductAction("money", ActionType.ECONOMY, Map.of("amount", "1"), false, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("kit", ActionType.KIT, Map.of("kit", "k"), true, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("claim", ActionType.PROTECTION_STONE, Map.of("alias", "a"), true, RefundPolicy.MANUAL_REVIEW, true),
                new ProductAction("announce", ActionType.ANNOUNCEMENT, Map.of(), false, RefundPolicy.NOT_REVOCABLE, true)));
        return new PurchaseService(new ProductCatalog(List.of(sub)), repository, runtime);
    }

    @Test void renewalExtendsOnlyTimedActionsAndIsIdempotentPerRef() throws Exception {
        runtime.online = true; service = subscriptionService();
        service.deliver("sub-1", "TestPlayer", "sub_rank", false, "tebex");
        assertTrue(service.renew("sub-1", "TestPlayer", "sub_rank", "r1", "tebex").success());
        assertTrue(service.renew("sub-1", "TestPlayer", "sub_rank", "r1", "tebex").success());
        assertEquals(2, runtime.calls(ActionType.LUCKPERMS_TEMPORARY));
        assertEquals(1, runtime.calls(ActionType.ECONOMY));
        assertEquals(1, runtime.calls(ActionType.KIT));
        assertEquals(1, runtime.calls(ActionType.PROTECTION_STONE));
        assertEquals(1, runtime.calls(ActionType.ANNOUNCEMENT));
        assertTrue(service.renew("sub-1", "TestPlayer", "sub_rank", "r2", "tebex").success());
        assertEquals(3, runtime.calls(ActionType.LUCKPERMS_TEMPORARY));
        String result = repository.actions(service.status("sub-1").getFirst().id()).stream()
                .filter(a -> a.type() == ActionType.LUCKPERMS_TEMPORARY).findFirst().orElseThrow().result();
        assertEquals("group=g;previous=0;new=3000", result);
    }

    @Test void renewalWithUnknownTransactionFallsBackToPlayersLastCompletedPurchase() throws Exception {
        runtime.online = true; service = subscriptionService();
        service.deliver("sub-2", "TestPlayer", "sub_rank", false, "tebex");
        assertTrue(service.renew("otro-id", "TestPlayer", "sub_rank", "r1", "tebex").success());
        assertEquals(2, runtime.calls(ActionType.LUCKPERMS_TEMPORARY));
        assertFalse(service.renew("otro-id", "Nadie", "sub_rank", "r1", "tebex").success());
    }

    @Test void renewalIsRejectedAfterRefundChargebackOrWithoutInitialDelivery() throws Exception {
        runtime.online = true; service = subscriptionService();
        assertFalse(service.renew("sub-x", "TestPlayer", "sub_rank", "r1", "tebex").success());
        service.deliver("sub-3", "TestPlayer", "sub_rank", false, "tebex");
        service.financialEvent("sub-3", "sub_rank", false, "admin");
        assertFalse(service.renew("sub-3", "TestPlayer", "sub_rank", "r1", "tebex").success());
        assertEquals(1, runtime.calls(ActionType.LUCKPERMS_TEMPORARY));
    }

    @Test void mergeKeepsOriginalPreviousAndLatestExpiry() {
        assertEquals("group=g;previous=5;new=90", PurchaseService.mergeRenewalResult("group=g;previous=5;new=60", "group=g;previous=60;new=90"));
        assertEquals("group=g;permanent=true", PurchaseService.mergeRenewalResult("group=g;permanent=true", "group=g;previous=1;new=2"));
    }

    private ProductAction action(ProductCatalog catalog, String productId, String actionId) {
        return catalog.get(productId).actions().stream()
                .filter(action -> action.id().equals(actionId))
                .findFirst()
                .orElseThrow();
    }

    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete();
    }

    private static final class FakeRuntime implements PurchaseActionRuntime {
        private final Map<ActionType, Integer> counts = new EnumMap<>(ActionType.class);
        private boolean online;
        private boolean failKitOnce;
        private boolean failEconomy;
        private boolean failAnnouncementOnce;
        private String onlyOnlineFor;
        @Override public UUID resolveUuid(String player) { return UUID.nameUUIDFromBytes(player.getBytes()); }
        @Override public boolean isOnline(String player) { return online && (onlyOnlineFor == null || onlyOnlineFor.equals(player)); }
        @Override public ActionResult execute(ExecutionContext context, ProductAction action) {
            counts.merge(action.type(), 1, Integer::sum);
            if (action.type() == ActionType.ECONOMY && failEconomy) return ActionResult.retryable("Vault unavailable");
            if (action.requiresOnline() && !online) return ActionResult.waiting("offline");
            if (action.type() == ActionType.KIT && failKitOnce) { failKitOnce = false; return ActionResult.retryable("transient"); }
            if (action.type() == ActionType.ANNOUNCEMENT && failAnnouncementOnce) { failAnnouncementOnce = false; return ActionResult.retryable("webhook unavailable"); }
            if (action.type() == ActionType.LUCKPERMS_TEMPORARY) { long n = counts.get(ActionType.LUCKPERMS_TEMPORARY); return ActionResult.completed("group=g;previous=" + (n - 1) * 1000 + ";new=" + n * 1000); }
            return ActionResult.completed(action.id());
        }
        @Override public ActionResult revoke(ExecutionContext context, ProductAction action, String result) { return ActionResult.completed("revoked"); }
        int calls(ActionType type) { return counts.getOrDefault(type, 0); }
    }
}
