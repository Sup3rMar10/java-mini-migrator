CREATE TABLE orders (
    id        INT PRIMARY KEY,
    widget_id INT NOT NULL,
    quantity  INT NOT NULL,
    CONSTRAINT fk_orders_widget FOREIGN KEY (widget_id) REFERENCES widgets (id)
);
INSERT INTO orders (id, widget_id, quantity) VALUES (100, 1, 4);
