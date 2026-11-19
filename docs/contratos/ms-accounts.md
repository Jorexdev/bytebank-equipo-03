# Contrato de ms-accounts

Gestión de cuentas bancarias, titulares compartidos, saldos, ingresos en ventanilla, extractos de movimientos y
publicación de eventos en Kafka (`bytebank.movements`). Las rutas `/api/accounts/**` pasan por el gateway y requieren
token JWT; las rutas `/internal/accounts/**` son internas entre servicios y exigen `X-Internal-Key`.

## Cuentas compartidas (múltiples titulares)

Una cuenta puede tener entre **1 y 4 titulares** (todos con rol `CLIENTE`). Al abrirse una cuenta, el usuario que la
crea queda registrado como titular inicial (`ownerId`) y también dentro de la lista de titulares (`holderIds`).

- Todos los titulares de una cuenta comparten los mismos permisos en `ms-accounts`:
  - ver la cuenta en `GET /api/accounts` y `GET /api/accounts/{iban}`;
  - consultar su extracto en `GET /api/accounts/{iban}/movements`;
  - agregar nuevos co-titulares mientras no se alcance el máximo de 4.
- `ms-accounts` expone `holderIds` (conservando `ownerId` por retrocompatibilidad) tanto en `/internal/accounts/{iban}`
  como en el evento Kafka `bytebank.movements`, para que `ms-transfers`, `ms-cards` y `ms-notifications` reconozcan a
  todos los titulares de una cuenta compartida.
- `ownerId` identifica al titular creador; `holderIds` contiene los identificadores de todos los titulares ordenados
  por fecha de incorporación.

## Agregar un titular a una cuenta

`POST /api/accounts/{iban}/holders` (rol `CLIENTE`)

```json
{ "email": "luis@bytebank.test" }
```

- Validación de entrada y autenticación:
  - el email es obligatorio, con formato válido y un máximo de 254 caracteres: si no, `400 DATOS_NO_VALIDOS`;
  - sin token válido: `401 NO_AUTENTICADO`;
  - un `GESTOR` no puede ejecutar esta operación: `403 ACCESO_DENEGADO`.
- Pre-validación sobre la cuenta (sin consultar todavía a `ms-auth`, para evitar enumeración de usuarios y llamadas
  remotas innecesarias):
  - si la cuenta no existe: `404 CUENTA_NO_ENCONTRADA`;
  - si el usuario autenticado no es titular de la cuenta: `403 ACCESO_DENEGADO`;
  - si la cuenta no está `ACTIVA` (`BLOQUEADA` o `CERRADA`): `409 CUENTA_NO_OPERATIVA`;
  - si el email coincide con el del propio solicitante: `409 TITULAR_YA_EXISTENTE`;
  - si la cuenta ya tiene 4 titulares: `409 MAXIMO_TITULARES_ALCANZADO`.
- Resolución del cliente en `ms-auth` (`GET /internal/users/by-email`, fuera de la transacción de base de datos para
  no retener bloqueos durante la llamada de red):
  - si no existe ningún usuario con ese email: `404 USUARIO_NO_ENCONTRADO`;
  - si el usuario encontrado no tiene rol `CLIENTE` (por ejemplo, es un `GESTOR`): `400 USUARIO_NO_ES_CLIENTE`;
  - si `ms-auth` no está disponible o devuelve una respuesta inválida: `503 SERVICIO_NO_DISPONIBLE`.
- Re-validación atómica y persistencia bajo bloqueo pesimista (`SELECT ... FOR UPDATE`), para evitar que peticiones
  simultáneas superen el límite de 4 titulares o operen sobre una cuenta que cambió de estado:
  - re-verifica existencia (`404 CUENTA_NO_ENCONTRADA`), titularidad del solicitante (`403 ACCESO_DENEGADO`), estado
    `ACTIVA` (`409 CUENTA_NO_OPERATIVA`), no duplicidad (`409 TITULAR_YA_EXISTENTE`) y límite de 4 titulares
    (`409 MAXIMO_TITULARES_ALCANZADO`).

Respuesta `201 Created`:

```json
{
  "iban": "ES7639990001000000000001",
  "status": "ACTIVA",
  "balance": 2500.00,
  "ownerId": 2,
  "holderIds": [2, 3],
  "createdAt": "2026-06-12T10:00:00Z"
}
```

## Rutas públicas (`/api/accounts`)

- `POST /api/accounts` (`CLIENTE`): abre una cuenta nueva `ACTIVA` con saldo `0.00` y el solicitante como titular
  inicial. Responde `201 Created`.
- `GET /api/accounts` (`CLIENTE` o `GESTOR`): devuelve `200 OK` con todas las cuentas en las que el usuario autenticado
  es titular (propias y compartidas), ordenadas por fecha de creación ascendente (`createdAt ASC, id ASC`).
- `GET /api/accounts/{iban}` (`CLIENTE` titular o `GESTOR`): devuelve `200 OK` con el detalle de la cuenta. Si un
  `CLIENTE` no es titular: `403 ACCESO_DENEGADO`. Si no existe: `404 CUENTA_NO_ENCONTRADA`.
- `GET /api/accounts/{iban}/movements?from=YYYY-MM-DD&to=YYYY-MM-DD&page=0&size=20` (`CLIENTE` titular o `GESTOR`):
  devuelve `200 OK` con el extracto paginado ordenado del movimiento más reciente al más antiguo.
- `PATCH /api/accounts/{iban}/status` (`GESTOR`): cambia el estado (`ACTIVA`, `BLOQUEADA`, `CERRADA`) bajo bloqueo
  pesimista y responde `200 OK` (`400 DATOS_NO_VALIDOS`, `404 CUENTA_NO_ENCONTRADA`).
- `POST /api/accounts/{iban}/deposits` (`GESTOR`): registra un ingreso en ventanilla en una cuenta `ACTIVA`, genera el
  movimiento `INGRESO`, publica el evento en Kafka tras el commit y responde `200 OK` (`400 DATOS_NO_VALIDOS`,
  `404 CUENTA_NO_ENCONTRADA`, `409 CUENTA_NO_OPERATIVA`).

## Contrato interno (`/internal/accounts`, requiere `X-Internal-Key`)

### Consultar cuenta

`GET /internal/accounts/{iban}`

Usado por `ms-transfers` y `ms-cards` para validar estado y titularidad. Devuelve `404 CUENTA_NO_ENCONTRADA` si el
IBAN no existe.

```json
{
  "iban": "ES7639990001000000000001",
  "status": "ACTIVA",
  "balance": 2500.00,
  "ownerId": 2,
  "holderIds": [2, 3]
}
```

### Cargo y abono en el ledger

- `POST /internal/accounts/debit`: descuenta el importe de una cuenta `ACTIVA` con saldo suficiente bajo bloqueo
  pesimista (`400 DATOS_NO_VALIDOS`, `404 CUENTA_NO_ENCONTRADA`, `409 CUENTA_NO_OPERATIVA`,
  `409 FONDOS_INSUFICIENTES`).
- `POST /internal/accounts/credit`: suma el importe en una cuenta `ACTIVA` bajo bloqueo pesimista
  (`400 DATOS_NO_VALIDOS`, `404 CUENTA_NO_ENCONTRADA`, `409 CUENTA_NO_OPERATIVA`).

## Eventos en Kafka (`bytebank.movements`)

Cada movimiento confirmado en base de datos publica un único evento en el topic `bytebank.movements` con el IBAN como
clave de partición, diferido a `afterCommit()` para garantizar que nunca se emiten avisos de operaciones revertidas:

```json
{
  "movementId": 154,
  "iban": "ES7639990001000000000001",
  "ownerId": 2,
  "holderIds": [2, 3],
  "type": "TRANSFERENCIA_EMITIDA",
  "amount": -50.00,
  "balanceAfter": 2450.00,
  "concept": "Alquiler",
  "reference": "TRF-8f2c",
  "createdAt": "2026-06-12T10:05:00Z"
}
```
