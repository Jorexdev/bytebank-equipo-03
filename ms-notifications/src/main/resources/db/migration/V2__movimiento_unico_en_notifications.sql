DELETE FROM notifications
WHERE id IN (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY movement_id ORDER BY id) AS numero_fila
        FROM notifications
    ) AS notificaciones_ordenadas
    WHERE numero_fila > 1
);

ALTER TABLE notifications
    ADD CONSTRAINT uk_notifications_movement_id UNIQUE (movement_id);
