# Arquitectura

```
                                   +-------------+
          cliente / gestor ------> | ms-gateway  | :8080 (solo /api/**)
                                   +------+------+
                                          |
    +-----------+------------+------------+------------+------------+------------+
    |           |            |            |            |            |            |
+---v---+ +-----v-----+ +----v-------+ +--v-----+ +----v----+ +-----v----------+
|ms-auth| |ms-accounts| |ms-transfers| |ms-cards| |ms-fraud | |ms-notifications|
| :8081 | |   :8082   | |   :8083    | | :8084  | |  :8085  | |     :8086      |
+---^---+ +---^---+---+ +-----+------+ +---+----+ +----^----+ +-------^--------+
    |         |   |           |            |           |              |
    +---------+   |           +------------+-----------+              |
    llamadas      |             llamadas internas /internal/**        |
    internas      +----------------- Apache Kafka --------------------+
                               (topic bytebank.movements)
```

Cada servicio tiene su propia base de datos (`auth_db`, `accounts_db`, `transfers_db`, `cards_db`, `fraud_db`, `notifications_db`),
todas en la misma instancia de PostgreSQL 15. Ningún servicio lee la base de datos de otro.

`ms-accounts` publica eventos de movimientos en el topic `bytebank.movements` de Apache Kafka (modo KRaft) tras confirmar la
transacción en base de datos (`afterCommit`), y `ms-notifications` los consume de forma idempotente por `(user_id, movement_id)`
para avisar a todos los titulares de la cuenta.

## Seguridad

- **Clientes**: ms-auth emite un JWT HS256 con el id del usuario (`sub`), su `email` y su `role`
  (`CLIENTE` o `GESTOR`). El resto de servicios valida el token en cada petición (módulo `common`).
- **Entre servicios**: las rutas `/internal/**` no pasan por el gateway y exigen la cabecera `X-Internal-Key`
  con la clave compartida (`INTERNAL_KEY`).

## Módulo common

Código compartido por los servicios de dominio:

- `security`: filtro JWT, filtro de clave interna, `CurrentUser` para saber quién llama.
- `error`: `ApiException` y el formato único de error.
- `http`: `InternalClients` para llamar a otros servicios con la clave interna.
- `iban`: validación y generación de IBAN (ByteBank es la entidad 3999).

## Formato de error

Todos los servicios devuelven los errores así:

```json
{
  "timestamp": "2026-06-10T09:30:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "FONDOS_INSUFICIENTES",
  "message": "Saldo insuficiente",
  "path": "/internal/accounts/debit"
}
```

`code` es estable y es lo que deben mirar los clientes y los demás servicios; `message` es para personas.

## Flujo de una transferencia

1. El cliente hace `POST /api/transfers` con origen, destino, importe y concepto.
2. `ms-transfers` valida los datos, comprueba que el usuario es titular o co-titular de la cuenta origen y, si el destino es de ByteBank, prevalida que la cuenta destino exista y esté activa.
3. Consulta a `ms-fraud` (`POST /internal/fraud/check`, política fail-closed `503` si no responde):
   - Si el veredicto es `BLOQUEADA`, la transferencia queda `RECHAZADA` (`FRAUDE_DETECTADO`) sin mover dinero.
   - Si el veredicto es `REVISAR` o el importe supera el umbral de 5.000 € (`TRANSFERENCIA_SUPERA_UMBRAL`), queda `PENDIENTE_REVISION` sin mover dinero hasta que un `GESTOR` la apruebe o rechace (`docs/transferencias-revision.md`).
4. Si se ejecuta directamente (o cuando un `GESTOR` la aprueba bajo bloqueo pesimista `FOR UPDATE`), carga el importe en origen (`POST /internal/accounts/debit`).
5. Si el destino es de ByteBank, abona en destino (`POST /internal/accounts/credit`). Si el abono falla tras el cargo, ejecuta un reembolso compensatorio en origen (`TRF-<id>-REV`, o marca `REVERSION_FALLIDA` si la compensación también falla).
6. La transferencia queda `COMPLETADA` o `RECHAZADA` con el código del error.

Las transferencias a otras entidades solo cargan en origen: la liquidación con el otro banco queda fuera de este sistema.

## Roles

| Rol | Puede |
|---|---|
| CLIENTE | Abrir cuentas, añadir co-titulares (hasta 4), consultar sus cuentas/extractos/avisos, hacer transferencias desde cuentas donde es titular y emitir/bloquear/pagar con sus tarjetas |
| GESTOR | Consultar cuentas/extractos/transferencias/avisos, bloquear y desbloquear cuentas, hacer ingresos en ventanilla, consultar validaciones antifraude y listar/aprobar/rechazar transferencias en `PENDIENTE_REVISION` |
