ALTER TABLE widgets ADD COLUMN sku VARCHAR(20);
CREATE UNIQUE INDEX ux_widgets_sku ON widgets (sku);
