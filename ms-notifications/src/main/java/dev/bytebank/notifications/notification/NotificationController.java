package dev.bytebank.notifications.notification;

import dev.bytebank.common.security.CurrentUser;
import dev.bytebank.common.web.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var notifications = notificationService.listForUser(CurrentUser.get().id(), page, size);
        var response = PageResponse.of(notifications, NotificationResponse::from);
        return ResponseEntity.ok(response);
    }
}
