CREATE TABLE IF NOT EXISTS purchase_renewals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  delivery_id INTEGER NOT NULL REFERENCES purchase_deliveries(id),
  renewal_ref TEXT NOT NULL,
  actor TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(delivery_id, renewal_ref)
);
