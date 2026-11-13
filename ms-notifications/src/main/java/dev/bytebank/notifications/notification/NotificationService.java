package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<Notification> listForUser(Long userId) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void notifyOwner(MovementEvent event) {
        Notification notification = this.createNotification(event);
        notifications.save(notification);
    }

    private Notification createNotification(MovementEvent event) {
        return new Notification(
                event.ownerId(),
                event.movementId(),
                event.iban(),
                event.type(),
                BigDecimal.valueOf(event.amount()),
                BigDecimal.valueOf(event.balanceAfter()),
                event.reference(),
                NotificationMessages.of(event),
                event.createdAt()
        );
    }
}
