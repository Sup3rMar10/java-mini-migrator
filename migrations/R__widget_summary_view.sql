CREATE OR REPLACE VIEW widget_summary AS
SELECT w.id, w.name, COALESCE(SUM(o.quantity), 0) AS ordered
FROM widgets w LEFT JOIN orders o ON o.widget_id = w.id
GROUP BY w.id, w.name;
