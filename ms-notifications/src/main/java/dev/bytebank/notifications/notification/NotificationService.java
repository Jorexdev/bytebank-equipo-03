package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public Page<Notification> listForUser(Long userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return notifications.findByUserId(userId, pageable);
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
