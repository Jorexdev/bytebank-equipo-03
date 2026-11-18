ALTER TABLE notifications
    DROP CONSTRAINT uk_notifications_movement_id;

ALTER TABLE notifications
    ADD CONSTRAINT uk_notifications_user_id_movement_id UNIQUE (user_id, movement_id);
