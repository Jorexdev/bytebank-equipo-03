package dev.bytebank.notifications.notification;

import dev.bytebank.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    public List<NotificationResponse> list() {
        return notificationService.listForUser(CurrentUser.get().id()).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
