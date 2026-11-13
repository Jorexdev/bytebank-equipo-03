package dev.bytebank.notifications.notification;

import dev.bytebank.common.error.ApiError;
import dev.bytebank.common.security.CurrentUser;
import dev.bytebank.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Notificaciones", description = "Operaciones sobre las notificaciones del usuario autenticado")
@SecurityRequirement(name = "Bearer")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    @PreAuthorize("hasAnyRole('CLIENTE', 'GESTOR')")
    @Operation(
            summary = "Listar notificaciones del usuario autenticado",
            description = "Devuelve las notificaciones del usuario autenticado paginadas. "
                    + "El orden es por fecha de creación descendente (más recientes primero). "
                    + "Requiere un token JWT válido con rol CLIENTE o GESTOR."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Listado de notificaciones obtenido correctamente",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PageResponse.class),
                            examples = @ExampleObject(
                                    name = "Respuesta exitosa",
                                    summary = "Página de notificaciones",
                                    value = """
                                            {
                                              "content": [
                                                {
                                                  "id": 1,
                                                  "iban": "ES7600000000000000000000",
                                                  "type": "DEPOSITO",
                                                  "amount": 150.00,
                                                  "balanceAfter": 1150.00,
                                                  "reference": "REF-001",
                                                  "movementId": 42,
                                                  "message": "Depósito recibido por 150.00 €",
                                                  "createdAt": "2026-01-15T10:30:00Z"
                                                }
                                              ],
                                              "page": 0,
                                              "size": 20,
                                              "totalElements": 1,
                                              "totalPages": 1
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parámetros de paginación inválidos",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(
                                    name = "Error de validación",
                                    summary = "Tamaño de página fuera de rango",
                                    value = """
                                            {
                                              "timestamp": "2026-01-15T10:30:00Z",
                                              "status": 400,
                                              "error": "Bad Request",
                                              "code": "VALIDACION",
                                              "message": "El parámetro 'size' debe estar entre 1 y 100",
                                              "path": "/api/notifications"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Token JWT ausente o inválido",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(
                                    name = "No autenticado",
                                    summary = "Falta credencial",
                                    value = """
                                            {
                                              "timestamp": "2026-01-15T10:30:00Z",
                                              "status": 401,
                                              "error": "Unauthorized",
                                              "code": "NO_AUTENTICADO",
                                              "message": "Se requiere autenticación para acceder a este recurso",
                                              "path": "/api/notifications"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "El usuario no tiene permisos suficientes",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(
                                    name = "Sin permisos",
                                    summary = "Rol insuficiente",
                                    value = """
                                            {
                                              "timestamp": "2026-01-15T10:30:00Z",
                                              "status": 403,
                                              "error": "Forbidden",
                                              "code": "SIN_PERMISOS",
                                              "message": "No tiene permisos para acceder a este recurso",
                                              "path": "/api/notifications"
                                            }
                                            """
                            )
                    )
            )
    })
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @Parameter(
                    description = "Número de página (base 0)",
                    example = "0"
            )
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(
                    description = "Cantidad de elementos por página (entre 1 y 100)",
                    example = "20"
            )
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        var notifications = notificationService.listForUser(CurrentUser.get().id(), page, size);
        var response = PageResponse.of(notifications, NotificationResponse::from);
        return ResponseEntity.ok(response);
    }
}