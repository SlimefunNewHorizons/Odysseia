package org.metamechanists.odysseia.purchase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Repositorio SQLite transaccional; todas las mutaciones críticas viven aquí. */
public final class PurchaseRepository implements AutoCloseable {
    private static final List<String> MIGRATIONS = List.of(
            "db/migration/V1__purchase_engine.sql",
            "db/migration/V2__financial_events.sql",
            "db/migration/V3__player_identities.sql",
            "db/migration/V4__renewals.sql");
    private final Connection connection;

    public PurchaseRepository(File database) throws SQLException, IOException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        }
        migrate();
    }

    private void migrate() throws SQLException, IOException {
        for (int index = 0; index < MIGRATIONS.size(); index++) {
            int version = index + 1;
            if (isApplied(version)) continue;
            connection.setAutoCommit(false);
            try {
                String sql = resource(MIGRATIONS.get(index));
                for (String statement : sql.split(";")) if (!statement.isBlank()) try (Statement query = connection.createStatement()) { query.execute(statement); }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO schema_migrations(version,applied_at) VALUES(?,?)")) {
                    insert.setInt(1, version); insert.setString(2, now()); insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | IOException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
    }

    private boolean isApplied(int version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version=?")) {
            query.setInt(1, version); return query.executeQuery().next();
        }
    }

    private String resource(String path) throws IOException {
        try (InputStream input = PurchaseRepository.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new FileNotFoundException(path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public synchronized Delivery createOrLoad(String provider, String transaction, String player, UUID uuid,
                                               ProductDefinition product, String actor) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String timestamp = now();
            try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO purchase_deliveries(provider,transaction_id,player_name,player_uuid,product_id,product_version,state,received_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)")) {
                insert.setString(1, provider); insert.setString(2, transaction); insert.setString(3, player);
                insert.setString(4, uuid == null ? null : uuid.toString()); insert.setString(5, product.id());
                insert.setInt(6, product.version()); insert.setString(7, PurchaseState.RECEIVED.name());
                insert.setString(8, timestamp); insert.setString(9, timestamp); insert.executeUpdate();
            }
            Delivery delivery = find(provider, transaction, product.id()).orElseThrow();
            int position = 0;
            for (ProductAction action : product.actions()) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO purchase_actions(delivery_id,action_key,position,action_type,state,requires_online,refund_policy,required,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    insert.setLong(1, delivery.id()); insert.setString(2, action.id()); insert.setInt(3, position++);
                    insert.setString(4, action.type().name()); insert.setString(5, ActionState.PENDING.name());
                    insert.setInt(6, action.requiresOnline() ? 1 : 0); insert.setString(7, action.refundPolicy().name());
                    insert.setInt(8, action.required() ? 1 : 0); insert.setString(9, timestamp); insert.executeUpdate();
                }
            }
            audit(delivery.id(), actor, "RECEIVED", product.id());
            connection.commit();
            return find(provider, transaction, product.id()).orElseThrow();
        } catch (SQLException error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }

    public synchronized Optional<Delivery> find(String provider, String transaction, String product) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM purchase_deliveries WHERE provider=? AND transaction_id=? AND product_id=?")) {
            query.setString(1, provider); query.setString(2, transaction); query.setString(3, product);
            ResultSet row = query.executeQuery(); return row.next() ? Optional.of(delivery(row)) : Optional.empty();
        }
    }

    public synchronized List<Delivery> findByTransaction(String transaction) throws SQLException {
        return deliveries("SELECT * FROM purchase_deliveries WHERE transaction_id=? ORDER BY id", transaction);
    }

    public synchronized List<Delivery> findPendingForPlayer(String player) throws SQLException {
        return deliveries("SELECT * FROM purchase_deliveries WHERE lower(player_name)=lower(?) AND state IN ('WAITING_FOR_PLAYER','PARTIALLY_DELIVERED','FAILED_RETRYABLE','RECEIVED','PROCESSING') ORDER BY id", player);
    }

    public synchronized List<Delivery> findPendingForIdentity(UUID uuid, String canonicalName) throws SQLException {
        String normalized = canonicalName.startsWith(".") ? canonicalName.substring(1) : canonicalName;
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM purchase_deliveries WHERE (player_uuid=? OR (player_uuid IS NULL AND lower(player_name) IN (lower(?),lower(?)))) AND state IN ('WAITING_FOR_PLAYER','PARTIALLY_DELIVERED','FAILED_RETRYABLE','FAILED_MANUAL_REVIEW','RECEIVED','PROCESSING') ORDER BY id")) {
            query.setString(1, uuid.toString()); query.setString(2, canonicalName); query.setString(3, normalized);
            ResultSet rows = query.executeQuery(); List<Delivery> result = new ArrayList<>();
            while (rows.next()) result.add(delivery(rows)); return result;
        }
    }

    public synchronized List<Delivery> pending() throws SQLException {
        return deliveries("SELECT * FROM purchase_deliveries WHERE state NOT IN ('COMPLETED','CANCELLED','REFUNDED','CHARGEBACK') ORDER BY received_at", null);
    }

    public synchronized List<Delivery> history(String player) throws SQLException {
        return deliveries("SELECT * FROM purchase_deliveries WHERE lower(player_name)=lower(?) ORDER BY received_at DESC LIMIT 50", player);
    }

    /**
     * Registra la identidad observada del jugador que acaba de conectar.
     *
     * <p>{@code canonical_name} es UNIQUE COLLATE NOCASE, asi que el upsert por {@code player_uuid}
     * no basta: si otro UUID ya reclamo ese nick (cambio de nombre, o el mismo jugador entrando con
     * un UUID distinto al que se registro antes) SQLite aborta con SQLITE_CONSTRAINT_UNIQUE y el
     * jugador se queda sin identidad ni alias. El titular anterior se retira del nick conservando su
     * fila, su historial y un alias {@code DISPLACED_CANONICAL} con el nick en claro, de modo que
     * sigue siendo localizable y ninguna entrega se pierde.
     *
     * @return los UUID que perdieron la reclamacion del nick; vacio en el caso normal.
     */
    public synchronized List<UUID> observeIdentity(UUID uuid, String canonical, String platform, String source, String confidence) throws SQLException {
        String now = now(); String normalized = canonical.startsWith(".") ? canonical.substring(1) : canonical;
        connection.setAutoCommit(false);
        try {
            List<UUID> displaced = releaseCanonicalClaim(uuid, canonical, now);
            upsertIdentity(uuid, canonical, normalized, platform, source, confidence, now);
            connection.commit();
            return displaced;
        } catch (SQLException error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }

    private void upsertIdentity(UUID uuid, String canonical, String normalized, String platform, String source, String confidence, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_identities(player_uuid,canonical_name,normalized_name,platform,bedrock_prefix,first_seen_at,last_seen_at,verification_source,confidence,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET canonical_name=excluded.canonical_name,normalized_name=excluded.normalized_name,platform=excluded.platform,bedrock_prefix=excluded.bedrock_prefix,last_seen_at=excluded.last_seen_at,verification_source=excluded.verification_source,confidence=excluded.confidence,updated_at=excluded.updated_at")) {
            statement.setString(1, uuid.toString()); statement.setString(2, canonical); statement.setString(3, normalized);
            statement.setString(4, platform); statement.setInt(5, canonical.startsWith(".") ? 1 : 0);
            statement.setString(6, now); statement.setString(7, now); statement.setString(8, source); statement.setString(9, confidence); statement.setString(10, now); statement.executeUpdate();
        }
    }

    /**
     * Libera el nick de cualquier identidad ajena que lo tenga reclamado.
     *
     * <p>No borra la fila desplazada: {@code player_identity_aliases} depende de ella con
     * ON DELETE CASCADE y puede sostener entregas ya vinculadas. El nick retirado se marca con el
     * propio UUID como sufijo, valor unico por construccion, y el nick en claro sobrevive como alias.
     */
    private List<UUID> releaseCanonicalClaim(UUID uuid, String canonical, String now) throws SQLException {
        List<UUID> displaced = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT player_uuid FROM player_identities WHERE canonical_name=? COLLATE NOCASE AND player_uuid<>?")) {
            query.setString(1, canonical); query.setString(2, uuid.toString());
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) displaced.add(UUID.fromString(rows.getString("player_uuid"))); }
        }
        if (displaced.isEmpty()) return displaced;
        for (UUID previous : displaced) {
            try (PreparedStatement alias = connection.prepareStatement("INSERT INTO player_identity_aliases(player_uuid,alias,alias_type,confidence,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?) ON CONFLICT(player_uuid,alias) DO UPDATE SET alias_type=excluded.alias_type,last_seen_at=excluded.last_seen_at")) {
                alias.setString(1, previous.toString()); alias.setString(2, canonical); alias.setString(3, "DISPLACED_CANONICAL");
                alias.setString(4, "HIGH"); alias.setString(5, now); alias.setString(6, now); alias.executeUpdate();
            }
            try (PreparedStatement retire = connection.prepareStatement("UPDATE player_identities SET canonical_name=?,updated_at=? WHERE player_uuid=?")) {
                retire.setString(1, canonical + "~" + previous); retire.setString(2, now); retire.setString(3, previous.toString()); retire.executeUpdate();
            }
        }
        return displaced;
    }

    public synchronized void observeAlias(UUID uuid, String alias, String type, String confidence) throws SQLException {
        String now = now();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_identity_aliases(player_uuid,alias,alias_type,confidence,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?) ON CONFLICT(player_uuid,alias) DO UPDATE SET alias_type=excluded.alias_type,confidence=excluded.confidence,last_seen_at=excluded.last_seen_at")) {
            statement.setString(1, uuid.toString()); statement.setString(2, alias); statement.setString(3, type); statement.setString(4, confidence); statement.setString(5, now); statement.setString(6, now); statement.executeUpdate();
        }
    }

    public synchronized Optional<PlayerIdentity> findIdentityByCanonical(String name) throws SQLException { return identity("SELECT * FROM player_identities WHERE canonical_name=? COLLATE NOCASE", name); }
    public synchronized Optional<PlayerIdentity> findIdentityByUuid(UUID uuid) throws SQLException { return identity("SELECT * FROM player_identities WHERE player_uuid=?", uuid.toString()); }
    public synchronized List<PlayerIdentity> findByAlias(String alias) throws SQLException { return identities("SELECT DISTINCT i.* FROM player_identities i JOIN player_identity_aliases a ON a.player_uuid=i.player_uuid WHERE a.alias=? COLLATE NOCASE", alias, null); }
    public synchronized List<PlayerIdentity> findByAlias(String alias, String type) throws SQLException { return identities("SELECT DISTINCT i.* FROM player_identities i JOIN player_identity_aliases a ON a.player_uuid=i.player_uuid WHERE a.alias=? COLLATE NOCASE AND a.alias_type=?", alias, type); }

    public synchronized void bindIdentity(long deliveryId, PlayerIdentity identity, String actor) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE purchase_deliveries SET player_name=?,player_uuid=?,updated_at=? WHERE id=?")) {
            update.setString(1, identity.canonicalName()); update.setString(2, identity.uuid().toString()); update.setString(3, now()); update.setLong(4, deliveryId); update.executeUpdate();
        }
        audit(deliveryId, actor, "IDENTITY_BOUND", identity.uuid() + ";" + identity.canonicalName());
    }

    private Optional<PlayerIdentity> identity(String sql, String value) throws SQLException {
        List<PlayerIdentity> found = identities(sql, value, null); return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }
    private List<PlayerIdentity> identities(String sql, String first, String second) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, first); if (second != null) query.setString(2, second); ResultSet rows = query.executeQuery(); List<PlayerIdentity> result = new ArrayList<>();
            while (rows.next()) result.add(new PlayerIdentity(UUID.fromString(rows.getString("player_uuid")), rows.getString("canonical_name"), rows.getString("platform"), rows.getString("confidence"))); return result;
        }
    }

    private List<Delivery> deliveries(String sql, String parameter) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            if (parameter != null) query.setString(1, parameter);
            ResultSet rows = query.executeQuery(); List<Delivery> result = new ArrayList<>();
            while (rows.next()) result.add(delivery(rows)); return result;
        }
    }

    public synchronized List<ActionRecord> actionable(long deliveryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM purchase_actions WHERE delivery_id=? AND state IN ('PENDING','WAITING_FOR_PLAYER','FAILED_RETRYABLE','PROCESSING') ORDER BY position")) {
            query.setLong(1, deliveryId); ResultSet rows = query.executeQuery(); List<ActionRecord> result = new ArrayList<>();
            while (rows.next()) result.add(action(rows)); return result;
        }
    }

    public synchronized List<ActionRecord> actions(long deliveryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM purchase_actions WHERE delivery_id=? ORDER BY position")) {
            query.setLong(1, deliveryId); ResultSet rows = query.executeQuery(); List<ActionRecord> result = new ArrayList<>();
            while (rows.next()) result.add(action(rows)); return result;
        }
    }

    public synchronized void actionState(long actionId, ActionState state, String result, String error) throws SQLException {
        String timestamp = now();
        try (PreparedStatement update = connection.prepareStatement("UPDATE purchase_actions SET state=?,attempts=attempts+1,result=?,last_error=?,started_at=COALESCE(started_at,?),updated_at=?,completed_at=CASE WHEN ? IN ('COMPLETED','REVOKED','SKIPPED') THEN ? ELSE completed_at END WHERE id=?")) {
            update.setString(1, state.name()); update.setString(2, safe(result)); update.setString(3, safe(error));
            update.setString(4, timestamp); update.setString(5, timestamp); update.setString(6, state.name());
            update.setString(7, timestamp); update.setLong(8, actionId); update.executeUpdate();
        }
    }

    public synchronized void deliveryState(long id, PurchaseState state, String error) throws SQLException {
        String timestamp = now();
        try (PreparedStatement update = connection.prepareStatement("UPDATE purchase_deliveries SET state=?,attempts=attempts+1,last_error=?,updated_at=?,completed_at=CASE WHEN ?='COMPLETED' THEN ? ELSE completed_at END WHERE id=?")) {
            update.setString(1, state.name()); update.setString(2, safe(error)); update.setString(3, timestamp);
            update.setString(4, state.name()); update.setString(5, timestamp); update.setLong(6, id); update.executeUpdate();
        }
    }

    public synchronized boolean markAnnouncementSent(long id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE purchase_deliveries SET announcement_sent=1,updated_at=? WHERE id=? AND announcement_sent=0")) {
            update.setString(1, now()); update.setLong(2, id); return update.executeUpdate() == 1;
        }
    }

    public synchronized boolean registerFinancialEvent(String provider, String transaction, String product, String type, String actor) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO purchase_financial_events(provider,transaction_id,product_id,event_type,actor,created_at) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, provider); insert.setString(2, transaction); insert.setString(3, product);
            insert.setString(4, type); insert.setString(5, actor); insert.setString(6, now()); return insert.executeUpdate() == 1;
        }
    }

    public synchronized boolean hasRenewal(long deliveryId, String ref) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT 1 FROM purchase_renewals WHERE delivery_id=? AND renewal_ref=?")) {
            q.setLong(1, deliveryId); q.setString(2, ref); return q.executeQuery().next();
        }
    }

    public synchronized boolean registerRenewal(long deliveryId, String ref, String actor) throws SQLException {
        try (PreparedStatement i = connection.prepareStatement("INSERT OR IGNORE INTO purchase_renewals(delivery_id,renewal_ref,actor,created_at) VALUES(?,?,?,?)")) {
            i.setLong(1, deliveryId); i.setString(2, ref); i.setString(3, actor); i.setString(4, now()); return i.executeUpdate() == 1;
        }
    }

    public synchronized void audit(Long id, String actor, String event, String detail) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO purchase_audit(delivery_id,actor,event,detail,created_at) VALUES(?,?,?,?,?)")) {
            if (id == null) insert.setNull(1, Types.INTEGER); else insert.setLong(1, id);
            insert.setString(2, actor); insert.setString(3, event); insert.setString(4, safe(detail)); insert.setString(5, now()); insert.executeUpdate();
        }
    }

    private Delivery delivery(ResultSet row) throws SQLException {
        return new Delivery(row.getLong("id"), row.getString("provider"), row.getString("transaction_id"),
                row.getString("player_name"), row.getString("player_uuid"), row.getString("product_id"),
                PurchaseState.valueOf(row.getString("state")), row.getInt("announcement_sent") == 1,
                row.getString("last_error"), row.getString("received_at"));
    }
    private ActionRecord action(ResultSet row) throws SQLException {
        return new ActionRecord(row.getLong("id"), row.getLong("delivery_id"), row.getString("action_key"),
                row.getInt("position"), ActionType.valueOf(row.getString("action_type")), ActionState.valueOf(row.getString("state")),
                row.getInt("attempts"), row.getInt("requires_online") == 1, RefundPolicy.valueOf(row.getString("refund_policy")),
                row.getInt("required") == 1, row.getString("last_error"), row.getString("result"));
    }
    private String safe(String value) { return value == null ? null : value.substring(0, Math.min(value.length(), 1000)); }
    private String now() { return Instant.now().toString(); }
    @Override public synchronized void close() throws SQLException { connection.close(); }

    public record Delivery(long id, String provider, String transaction, String player, String uuid, String product,
                           PurchaseState state, boolean announcementSent, String lastError, String receivedAt) {}
    public record ActionRecord(long id, long deliveryId, String key, int position, ActionType type, ActionState state,
                               int attempts, boolean requiresOnline, RefundPolicy refundPolicy, boolean required,
                               String lastError, String result) {}
    public record PlayerIdentity(UUID uuid, String canonicalName, String platform, String confidence) {}
}
