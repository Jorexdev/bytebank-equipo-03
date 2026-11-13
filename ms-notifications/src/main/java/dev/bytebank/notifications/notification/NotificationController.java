package dev.bytebank.notifications.notification;

import dev.bytebank.common.security.CurrentUser;
import dev.bytebank.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('CLIENTE', 'GESTOR')")
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        var notifications = notificationService.listForUser(CurrentUser.get().id(), page, size);
        var response = PageResponse.of(notifications, NotificationResponse::from);
        return ResponseEntity.ok(response);
    }
}
