# Decisiones de arquitectura (ADRs)

Registro de las decisiones técnicas tomadas durante el desarrollo de ByteBank, las alternativas evaluadas y los costes o riesgos asumidos en cada caso. El detalle de riesgos priorizados por gravedad se mantiene en [docs/diagnostico.md](diagnostico.md#4-riesgos-ordenados-por-gravedad).

| ID | Decisión | Módulo(s) | Estado |
|---|---|---|---|
| [ADR-01](#adr-01--integración-fail-closed-con-ms-fraud) | Integración *fail-closed* con `ms-fraud` y umbral local de revisión | `ms-transfers`, `ms-fraud` | Aceptada |
| [ADR-02](#adr-02--compensación-por-reembolso-en-transferencias) | Compensación por reembolso (`TRF-<id>-REV`) en lugar de saga completa | `ms-transfers`, `ms-accounts` | Aceptada |
| [ADR-03](#adr-03--bloqueo-pesimista-select--for-update-para-invariantes-concurrentes) | Bloqueo pesimista (`PESSIMISTIC_WRITE`) para invariantes concurrentes | `ms-accounts`, `ms-cards`, `ms-transfers` | Aceptada |
| [ADR-04](#adr-04--publicación-en-kafka-tras-el-commit-aftercommit-con-maxblockms-acotado) | Publicación de eventos Kafka en `afterCommit` con `max.block.ms` acotado | `ms-accounts`, `ms-notifications` | Aceptada |
| [ADR-05](#adr-05--mantener-http-dentro-de-transactional-en-transferserviceapprove-y-cardservicepay-como-riesgo-asumido) | Mantener HTTP dentro de `@Transactional` en `approve` y `pay` como riesgo asumido | `ms-transfers`, `ms-cards`, `ms-accounts` | Aceptada (riesgo asumido) |
| [ADR-06](#adr-06--idempotencia-en-ms-notifications-por-user_id-movement_id-y-compatibilidad-multi-titular) | Idempotencia en notificaciones por `(user_id, movement_id)` y compatibilidad multi-titular | `ms-notifications` | Aceptada |
| [ADR-07](#adr-07--precisión-monetaria-con-bigdecimal--numeric192-y-zona-horaria-europemadrid) | Precisión monetaria con `BigDecimal`/`NUMERIC(19,2)` y zona horaria `Europe/Madrid` | Todos los servicios | Aceptada |
| [ADR-08](#adr-08--seguridad-perimetral-e-interna-jwt-hs256-x-internal-key-y-gateway-de-mínimo-privilegio) | Seguridad perimetral e interna: JWT HS256, `X-Internal-Key` y gateway de mínimo privilegio | `common`, `ms-gateway`, `ms-auth` | Aceptada |

---

## ADR-01 — Integración *fail-closed* con `ms-fraud`

- **Contexto:** `ms-transfers` debe validar cada transferencia contra `POST /internal/fraud/check` de `ms-fraud` antes de mover dinero (`FRA-01`), y además retener para revisión de `GESTOR` las transferencias superiores a `5.000 €` (`CAM-02`).
- **Decisión:** Aplicar estrategia **fail-closed**: si `ms-fraud` no responde, expira el timeout (`2s` conexión / `5s` lectura en `InternalClients`) o devuelve una respuesta sin veredicto, `TransferService#create` devuelve `503 SERVICIO_NO_DISPONIBLE` sin crear la transferencia ni tocar el saldo. El umbral local de revisión (`bytebank.transfers.review-threshold`, por defecto `5000`) se evalúa en `ms-transfers` de forma independiente a las reglas de `ms-fraud`.
- **Alternativa descartada:** Estrategia *fail-open* (ejecutar la transferencia sin validar antifraude cuando `ms-fraud` esté caído).
- **Consecuencias / riesgo asumido:** Se prioriza la integridad antifraude sobre la disponibilidad. Una caída de `ms-fraud` impide temporalmente crear nuevas transferencias hasta que el servicio se recupere; la aprobación posterior de transferencias ya pendientes por un gestor no repite la llamada a `ms-fraud`.

---

## ADR-02 — Compensación por reembolso en transferencias

- **Contexto:** Cada microservicio posee su propia base de datos PostgreSQL (`transfers_db`, `accounts_db`), por lo que no existe una transacción ACID compartida para debitar origen y abonar destino en transferencias internas (`BUG-06`).
- **Decisión:** En `TransferService` se aplican tres capas:
  1. **Prevalidación** de la cuenta origen (activa) y de la cuenta destino propia (existente y activa) antes de debitar.
  2. **Reembolso compensatorio** si el cargo en origen tiene éxito pero el abono en destino falla: se invoca `AccountsClient#refund` registrando un movimiento `INGRESO` en la cuenta origen con referencia `TRF-<id>-REV`.
  3. **Trazabilidad ante doble fallo:** si el propio reembolso falla, la transferencia queda `RECHAZADA` con `rejectionReason = "REVERSION_FALLIDA"` y un error registrado en logs para conciliación manual. No se reintenta automáticamente porque `ms-accounts` no tiene idempotencia por referencia.
- **Alternativa descartada:** Orquestación mediante saga distribuida persistente con tabla de pasos, cola de reintentos y reconciliación automática.
- **Consecuencias / riesgo asumido:** Cubre con simplicidad y trazabilidad los fallos de negocio del abono, pero no elimina el caso de timeout ambiguo: si `ms-accounts` confirma un `credit` y la respuesta HTTP se pierde por timeout, `execute()` captura `RemoteUnavailableException` (subclase de `ApiException`) y podría intentar reembolsar al origen. La evolución a saga completa requiere primero idempotencia en `ms-accounts` (ver [ADR-05](#adr-05--mantener-http-dentro-de-transactional-en-transferserviceapprove-y-cardservicepay-como-riesgo-asumido)).

---

## ADR-03 — Bloqueo pesimista (`SELECT ... FOR UPDATE`) para invariantes concurrentes

- **Contexto:** Hay cuatro invariantes críticas expuestas a condiciones de carrera bajo peticiones simultáneas:
  1. Saldo no negativo en cargos simultáneos sobre una cuenta (`CON-01`).
  2. Límite máximo de 4 titulares por cuenta compartida (`CAM-01`).
  3. Límite diario de `600 €` en pagos simultáneos con una misma tarjeta (`BUG 2`).
  4. Exclusión mutua cuando dos gestores intentan aprobar o rechazar la misma transferencia pendiente a la vez (`CAM-02`).
- **Decisión:** Utilizar **bloqueo pesimista** (`@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE` en PostgreSQL 15) sobre la fila agregada correspondiente (`Account`, `Card`, `Transfer`), reforzado en `accounts` por la restricción `CHECK (balance >= 0)` (`V5__balance_non_negative.sql`). En `AccountService#addHolder`, la consulta HTTP a `ms-auth` se ejecuta **fuera** de la transacción usando `TransactionOperations` y validando que no haya transacción activa antes de llamar por red.
- **Alternativa descartada:** Bloqueo optimista (`@Version`) con bucles de reintento en capa de aplicación (generaría mayor tasa de fallos `OptimisticLockingFailureException` bajo contención directa y no evitaría recalcular sumas agregadas de `card_payments` sin serializar por tarjeta).
- **Consecuencias / riesgo asumido:** Garantiza serialización estricta verificada con tests concurrentes reales sobre PostgreSQL 15 (Testcontainers). El coste es la retención de bloqueos de fila y conexiones del pool mientras dure la transacción; por ello debe evitarse I/O remoto dentro de la transacción siempre que sea posible (ver trade-off explícito en [ADR-05](#adr-05--mantener-http-dentro-de-transactional-en-transferserviceapprove-y-cardservicepay-como-riesgo-asumido)).

---

## ADR-04 — Publicación en Kafka tras el commit (`afterCommit`) con `max.block.ms` acotado

- **Contexto:** `ms-accounts` publicaba `MovementEvent` en Kafka durante la transacción de `LedgerService`: si la transacción hacía rollback, el cliente recibía un aviso de un movimiento que nunca ocurrió (`KAF-02`). Además, el productor de Kafka bloquea por defecto hasta 60 s (`max.block.ms`) si el broker no responde, lo que superaba el timeout HTTP (`5 s`) de `ms-transfers` y `ms-cards` tras haber confirmado el saldo.
- **Decisión:**
  1. Registrar el envío en `MovementPublisher` mediante `TransactionSynchronization.afterCommit()` para garantizar que solo se publiquen operaciones ya confirmadas en `accounts_db`.
  2. Configurar `spring.kafka.producer.properties.max.block.ms: ${KAFKA_MAX_BLOCK_MS:2000}` en `ms-accounts/application.yml` y capturar excepciones de envío en `afterCommit()` con logging de error para que una caída de Kafka nunca bloquee ni tumbe una operación bancaria confirmada.
- **Alternativa descartada:** Patrón *Transactional Outbox* completo (tabla `outbox_events` en `accounts_db`, migración Flyway y worker `@Scheduled` de reenvío y purga).
- **Consecuencias / riesgo asumido:** Elimina al 100 % los avisos fantasma tras rollback y protege la disponibilidad HTTP con complejidad mínima. El trade-off asumido es que, si Kafka está caído en el instante del `afterCommit()`, ese aviso concreto se pierde (nunca se pierde integridad de saldo ni de extracto). Si en producción se requiere entrega garantizada *at-least-once* ante caídas prolongadas del broker, el siguiente paso es migrar a *Transactional Outbox*.

---

## ADR-05 — Mantener HTTP dentro de `@Transactional` en `TransferService#approve` y `CardService#pay` como riesgo asumido

- **Contexto:** Tanto `TransferService#approve` (`CAM-02`) como `CardService#pay` (`TAR-01` / `BUG 2`) están anotados con `@Transactional`, adquieren un bloqueo `SELECT ... FOR UPDATE` en su base de datos (`transfers_db` / `cards_db`) y ejecutan llamadas HTTP mutantes contra `ms-accounts` (`debit` y, en transferencias internas, `credit`) antes de hacer commit local. Si `ms-accounts` confirma el movimiento de dinero y falla el flush o el `COMMIT` local (o se produce un read timeout de `5 s` en `debit`), el rollback local devuelve la transferencia a `PENDIENTE_REVISION` (o descarta el registro de `card_payments` y su cómputo en el límite diario de `600 €`) con el dinero ya movido.
- **Decisión:** Mantener el diseño actual en esta entrega y documentarlo explícitamente como **riesgo asumido de gravedad Alta**, en lugar de aplicar un refactor parcial de última hora.
- **Alternativas evaluadas y por qué se pospusieron:**
  1. **Quitar `@Transactional` o separar transacciones sin estado intermedio:** rompería la exclusión mutua ante aprobaciones concurrentes de dos gestores (doble movimiento de dinero) y el control del límite diario de `600 €` en tarjetas.
  2. **Introducir `EN_PROCESO` solo en `ms-transfers` (2 transacciones cortas sin tocar `ms-accounts`):** libera el lock durante el HTTP, pero crea transferencias huérfanas atascadas en `EN_PROCESO` ante un fallo de la segunda transacción o caída del contenedor, y no resuelve el doble cargo ante un timeout ambiguo de `5 s` en `debit` porque `ms-accounts` no tiene idempotencia por referencia.
  3. **Solución completa multi-servicio (3 servicios + migraciones + reconciliadores):** requiere coordinar cambios de contrato público (`TransferStatus`, `PaymentStatus` —contradiciendo además el contrato vigente de `docs/contratos/ms-cards.md`, que define solo `APROBADO`/`RECHAZADO` y exige no persistir nada ante `503`—), nuevas migraciones Flyway y jobs `@Scheduled` de reconciliación.
  - *Contraste con `AccountService#addHolder`:* en `addHolder` sí se sacó el HTTP a `ms-auth` fuera de la transacción porque es una lectura idempotente sin efectos monetarios, mientras que `approve` y `pay` ejecutan escrituras financieras irreversibles en otra base de datos.
- **Hoja de ruta de evolución para producción:**
  1. **`ms-accounts` (prerrequisito):** añadir migración con índice único parcial `CREATE UNIQUE INDEX uq_movements_reference_type ON movements (reference, type) WHERE reference IS NOT NULL` (reemplazando `idx_movements_reference`; no puede ser `UNIQUE (reference)` global porque en transferencias internas `debit` y `credit` comparten la misma `reference` `TRF-<id>` con distinto `type`), validar idempotencia en `LedgerService` tras `FOR UPDATE` y exponer consulta interna por referencia para reconciliación.
  2. **`ms-transfers`:** añadir estado `EN_PROCESO` y `processing_started_at`, separar `approve` (y unificar `create`, cuyo constructor hoy nace en `COMPLETADA`) en dos transacciones cortas con `TransactionOperations` y añadir un job reconciliador (`FOR UPDATE SKIP LOCKED`) contra `ms-accounts`.
  3. **`ms-cards`:** adoptar reserva de cupo diario (`status IN ('APROBADO', 'EN_PROCESO')`), usar referencia determinista `CARD-<paymentId>` (`CardPayment#ledgerReference`) en lugar de `UUID.randomUUID()` y añadir reconciliador para liberar reservas expiradas.
  4. **PostgreSQL / HikariCP:** ajustar explícitamente tamaños de pool y `idle_in_transaction_session_timeout` para evitar agotamiento del pool por defecto (`10` conexiones) ante degradación de `ms-accounts`.

---

## ADR-06 — Idempotencia en `ms-notifications` por `(user_id, movement_id)` y compatibilidad multi-titular

- **Contexto:** Kafka garantiza entrega *at-least-once*, por lo que reinicios o rebalanceos del consumidor pueden reentregar un `MovementEvent` ya procesado (`KAF-03`). Al incorporarse cuentas compartidas de hasta 4 titulares (`CAM-01`), un mismo `movement_id` debe generar un aviso distinto para cada co-titular.
- **Decisión:** Evolucionar la restricción de unicidad de `V2` (`UNIQUE (movement_id)`) a `V3` (`UNIQUE (user_id, movement_id)`), comprobar previamente con `existsByMovementIdAndUserId(...)` y usar `saveAndFlush(...)` capturando `DataIntegrityViolationException` para absorber entregas concurrentes. En `MovementEvent#recipientIds()` se filtran valores `null` de `holderIds` y se mantiene fallback a `ownerId` para compatibilidad con eventos previos a `CAM-01`.
- **Alternativa descartada:** Confiar únicamente en el *offset commit* de Kafka o deduplicar en memoria.
- **Consecuencias / riesgo asumido:** Garantiza idempotencia duradera ante reinicios y concurrencia incluso con múltiples titulares. Como matiz asumido, `NotificationService` interpreta cualquier `DataIntegrityViolationException` en ese guardado como duplicado sin inspeccionar el SQLState específico para mantener compatibilidad con tests unitarios desacoplados del driver JDBC.

---

## ADR-07 — Precisión monetaria con `BigDecimal` / `NUMERIC(19,2)` y zona horaria `Europe/Madrid`

- **Contexto:** El código inicial utilizaba `double` para saldos y avisos, provocando errores de coma flotante como `0.10 + 0.20 = 0.30000000000000004` (`BUG-05`, `BUG 5`), y los filtros por fecha fallaban con movimientos en la franja `00:00–02:00` en España por usar UTC implícito (`BUG-04`).
- **Decisión:**
  1. Usar `BigDecimal` de extremo a extremo en los 6 servicios, columnas `NUMERIC(19,2)` en PostgreSQL (`V4__money_numeric.sql`) y validación uniforme `@DecimalMin("0.01") @Digits(integer = 15, fraction = 2)` en todos los contratos de entrada HTTP (incluidos `ms-transfers`, `ms-accounts`, `ms-cards` y `ms-fraud`).
  2. Parametrizar explícitamente la zona horaria (`bytebank.zone: Europe/Madrid`) y aplicar intervalos semiabiertos `[from 00:00, (to + 1 día) 00:00)` con rango acotado `[1970-01-01, 2999-12-31]` tanto en extractos como en el inicio del día del límite de tarjetas.
- **Alternativa descartada:** Almacenar importes en céntimos enteros (`long` / `BIGINT`), que exigiría transformar el contrato JSON y las migraciones existentes.
- **Consecuencias / riesgo asumido:** Precisión decimal exacta y contratos JSON legibles en euros. La convención de 15 dígitos enteros es ligeramente más estricta que `NUMERIC(19,2)` (17 enteros); si negocio necesitara importes mayores habría que actualizar `@Digits` de forma coordinada en los cuatro módulos.

---

## ADR-08 — Seguridad perimetral e interna: JWT HS256, `X-Internal-Key` y gateway de mínimo privilegio

- **Contexto:** El repositorio inicial tenía secretos hardcodeados en `SecurityConstants.java`, contraseñas en logs, IDORs en cuentas/transferencias (`SEG-01`, `SEG-02`) y `ms-gateway` recibiendo variables sensibles innecesarias.
- **Decisión:**
  1. Eliminar secretos en código y exigir `JWT_SECRET`, `INTERNAL_KEY` y `POSTGRES_PASSWORD` por variables de entorno sin valores por defecto, enmascarando credenciales y tokens en `toString()` (`BytebankProperties`, `Jwt`, `AuthDtos`).
  2. Aplicar principio de mínimo privilegio en `ms-gateway`: solo recibe URLs de destino, no expone rutas `/internal/**`, elimina cualquier cabecera entrante `X-Internal-Key` (`RemoveRequestHeader=X-Internal-Key`) y define timeouts HTTP (`connect-timeout: 2000`, `response-timeout: 15s`).
  3. Separar estrictamente `InternalKeyFilter` y `JwtAuthenticationFilter` mediante `AntPathRequestMatcher("/internal/**")` para que un token `Bearer` nunca sobrescriba `ROLE_INTERNAL` en rutas internas, y aplicar autorización en capa de servicio + `@PreAuthorize` (`CLIENTE` / `GESTOR`).
- **Alternativa descartada:** Migrar la firma JWT a claves asimétricas `RS256` y desplegar `mTLS` entre microservicios.
- **Consecuencias / riesgo asumido:** Reduce al mínimo la superficie de ataque en el perímetro con una configuración sencilla de operar en Docker Compose y CI. Queda asumido como riesgo arquitectónico de gravedad media que todos los servicios comparten `JWT_SECRET` (HS256) y una única `INTERNAL_KEY` estática sin rotación automática ni mTLS.
