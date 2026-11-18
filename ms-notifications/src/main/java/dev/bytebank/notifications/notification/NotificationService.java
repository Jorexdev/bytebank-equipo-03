package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notifications) {
        this.notificationRepository = notifications;
    }

    @Transactional(readOnly = true)
    public Page<Notification> listForUser(Long userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return notificationRepository.findByUserId(userId, pageable);
    }

    public void notifyOwner(MovementEvent event) {
        if (this.movementWasAlreadyNotified(event.movementId())) return;

        Notification notification = this.createNotification(event);

        try {
            this.notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException ex) {
            log.info("El movimiento {} ya tiene un aviso (detectado por constraint único); se ignora el evento repetido",
                    event.movementId());
        }
    }

    private boolean movementWasAlreadyNotified(Long movementId) {
        if (notificationRepository.existsByMovementId(movementId)) {
            log.info("El movimiento {} ya tiene un aviso; se ignora el evento repetido", movementId);
            return true;
        }
        return false;
    }

    private Notification createNotification(MovementEvent event) {
        return new Notification(
                event.ownerId(),
                event.movementId(),
                event.iban(),
                event.type(),
                event.amount(),
                event.balanceAfter(),
                event.reference(),
                NotificationMessages.of(event),
                event.createdAt()
        );
    }
}