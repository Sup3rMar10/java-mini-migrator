/* Needs the sku column from V1.2. A plain string sort would run V1.10
   before V1.2 and this file would fail; numeric ordering avoids that. */
INSERT INTO widgets (id, name, sku, price_cents) VALUES (1, 'Sprocket; large', 'SPR-L', 1250);
INSERT INTO widgets (id, name, sku, price_cents) VALUES (2, 'Gear -- small', 'GEA-S', 400);
INSERT INTO widgets (id, name, sku, price_cents) VALUES (3, 'Bob''s cog', 'COG-B', 75);
