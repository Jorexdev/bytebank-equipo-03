# Diagnostico del proyecto ByteBank

Informe inicial del repositorio para la tarea ARR con el estado del backend, por que no arrancaba el proyecto al clonarlo y los puntos clave que dejamos anotados para el equipo

---

## 1. Como esta montado

El proyecto es el backend de la banca online ByteBank montado con microservicios en Java 17 y Spring Boot 3.3.5 con Postgres, Kafka y Docker Compose

* **Modulo common:** Libreria compartida que usan los servicios, tiene los filtros de seguridad para validar los JWT, el filtro de la clave interna para llamadas entre servicios, el manejador global de errores con ApiException y la utilidad para validar y generar IBAN
* **Microservicios (7 servicios):**
  * `ms-gateway` (puerto 8080): Es la puerta de entrada, solo redirige las rutas publicas `/api/**` a cada servicio correspondiente
  * `ms-auth` (puerto 8081): Registro, login y emision de tokens JWT
  * `ms-accounts` (puerto 8082): Cuentas bancarias, saldos, ingresos y extracto de movimientos
  * `ms-transfers` (puerto 8083): Logica de transferencias entre cuentas propias y a otros bancos
  * `ms-cards` (puerto 8084): Tarjetas de debito y pagos
  * `ms-fraud` (puerto 8085): Motor con las reglas antifraude para revisar transferencias
  * `ms-notifications` (puerto 8086): Consume eventos del topic `bytebank.movements` de Kafka y guarda los avisos para los clientes
* **Bases de datos:** Una sola instancia de PostgreSQL 15 pero con 6 bases de datos independientes (`auth_db`, `accounts_db`, `transfers_db`, `cards_db`, `fraud_db` y `notifications_db`) usando Flyway para las migraciones
* **Seguridad:** Clientes y gestores usan token JWT en el header `Authorization: Bearer <token>`, mientras que las llamadas internas entre servicios van por `/internal/**` y piden obligatoriamente el header `X-Internal-Key`
* **Datos de prueba:** Viene configurado el perfil `demo` que al arrancar crea usuarios de prueba (`gestor@bytebank.test`, `ana@bytebank.test` y `luis@bytebank.test`) con cuentas y saldo cargado

---

## 2. Que falla y por que (fallos de arranque resueltos en ARR)

Al intentar levantar el proyecto con `docker compose up --build` siguiendo el README habia 3 problemas que impedian que funcionara:

1. **Error de puerto en docker-compose:**
   * En `docker-compose.yml` la variable `AUTH_URL` tenia puesto `http://ms-auth:8018`, pero `ms-auth` corre en el puerto `8081`. Cuando el gateway intentaba mandar el login a auth la conexion reventaba con error de conexion rechazada (500/502).
   * **Solucion:** Se cambio el puerto a `8081` en el compose.

2. **Error en la migracion V3 de Flyway en ms-accounts:**
   * El script `ms-accounts/.../V3__accounts_owner_index.sql` intentaba crear un indice haciendo `CREATE INDEX idx_accounts_owner ON accounts (owner)`. Pero en la tabla la columna se llama `owner_id`. Postgres tiraba error de columna no encontrada y el servicio de accounts se caia al iniciar.
   * **Solucion:** Se cambio `owner` por `owner_id` en el sql de la migracion.

3. **Falta del archivo .env:**
   * En `docker-compose.yml` se piden obligatoriamente las variables `POSTGRES_PASSWORD` e `INTERNAL_KEY`. Si no existe el archivo `.env` docker se frena antes de levantar nada.
   * **Solucion:** Crear el `.env` local copiando el `.env.example`.

---

## 3. Que falta (pendientes que dejo el equipo anterior)

Revisando el codigo y las notas del README hay varias cosas que quedaron a medias:

* **ms-cards:** Los endpoints de emitir tarjeta (`POST /api/cards`) y de pagar (`POST /api/cards/{id}/payments`) devuelven 501 Not Implemented, estan sin terminar
* **ms-notifications:** Ya recibe los eventos de movimientos desde Kafka y los guarda, pero no tiene implementado el endpoint `GET /api/notifications` para que el cliente pueda consultarlos
* **ms-fraud:** El servicio de transferencias todavia no consulta a ms-fraud antes de mover el dinero, y en fraud falta programar la regla de frecuencia de transferencias
* **Extracto de cuentas:** Hay que revisar la logica del extracto de movimientos en ms-accounts porque hay quejas reportadas

---

## 4. Que riesgos vemos

* **Clave secreta del JWT hardcodeada:** En `common/security/SecurityConstants.java` el secret del JWT esta escrito directo en el codigo (`bytebank-secret-key-...`). Cualquiera que vea el repo puede generar tokens validos. Hay que sacarlo a variable de entorno
* **Misma clave interna para todo:** La `INTERNAL_KEY` es compartida entre todos los servicios sin rotacion ni certificados mTLS entre ellos
* **Falta de rollback o saga completa en transferencias:** Cuando se hace una transferencia primero se saca plata de una cuenta y luego se ingresa en la otra con llamadas http normales. Ahora se reembolsa el cargo si falla el abono al destino (ver seccion 6), pero sigue sin haber idempotencia ni outbox, asi que un timeout ambiguo puede requerir conciliacion manual
* **Unico servidor de Postgres:** Todas las bases de datos de los servicios estan metidas en el mismo contenedor de postgres, si se cae ese contenedor se cae todo el sistema
* **Ojo con volumenes viejos de Docker:** Si alguien intento levantar docker antes de arreglar la migracion V3 de accounts, la base de datos de postgres puede haber guardado el error de flyway. Si pasa eso hay que hacer `docker compose down -v` para limpiar los volumenes y arrancar limpio

---

## 5. Bug detectado y corregido: transferencias con importe negativo

**Sintoma reportado por soporte:** Ana hace una transferencia de -100 € a la cuenta de Luis y el resultado es el contrario: a Ana le sube el saldo 100 € y a Luis le baja 100 €.

**Causa raiz:** No se validaba que el importe de la transferencia fuera positivo.

* En `ms-transfers/.../TransferDtos.java` el campo `amount` solo tenia `@NotNull`, asi que aceptaba valores negativos.
* En `ms-transfers/.../TransferService.java` el importe se enviaba tal cual a ms-accounts.
* En `ms-accounts/.../LedgerService.java`, un `debit` con importe negativo pasaba la comprobacion de saldo y terminaba sumando al origen (`balance -= -100`).
* Despues, el `credit` con importe negativo terminaba restando al destino (`balance += -100`).
* Resultado: una transferencia enviada como `-100` movia el dinero en sentido contrario al esperado.

**Reproduccion:** Se anadio el test `rejectsNegativeAmount` en `ms-transfers/src/test/java/dev/bytebank/transfers/transfer/TransferServiceTest.java`. Antes de la correccion el test fallaba con `Expecting code to raise a throwable` y en el log aparecia `Transferencia null completada`, lo que confirmaba que el importe negativo se procesaba. Tras la correccion el test pasa y queda como test de regresion; no se conserva ningun test en rojo.

**Solucion aplicada (dos capas):**

1. **Capa HTTP:** `@Positive(message = "El importe debe ser mayor que cero")` en el `amount` de `CreateTransferRequest`. El manejador global devuelve `400 DATOS_NO_VALIDOS` con el detalle en `fields.amount`.
2. **Capa de servicio:** validacion en `TransferService.create` con `ApiException.badRequest("IMPORTE_NO_VALIDO", "El importe debe ser mayor que cero")`. Esto protege cualquier llamada directa al servicio que no pase por el controller.

**Tests:** `rejectsNegativeAmount` y `rejectsZeroAmount` en `TransferServiceTest`, y `TransferDtosTest` para validar la anotacion `@Positive` con importes negativos y positivos.

Resultado: 3 tests de `common` y 8 tests de `ms-transfers` en verde (0 fallos, 0 errores).

**Riesgo residual:** `ms-accounts` sigue sin validar el importe en `MovementRequest`, por lo que `/internal/accounts/debit` y `/internal/accounts/credit` aceptarian importes negativos si se llaman directamente. 
Queda fuera del alcance de `ms-transfers` y deberian tratarse.

---

## 6. Bug detectado y corregido: transferencia a cuenta bloqueada que pierde el dinero

**Sintoma reportado por soporte:** Ana transfiere 50 EUR a una cuenta que estaba bloqueada. La transferencia sale como rechazada, pero a Ana le descuentan los 50 EUR y el dinero no llega a ninguna cuenta.

**Causa raiz:** En `ms-transfers/.../TransferService.java` se hacia primero el cargo en la cuenta de origen y despues el abono en la de destino. Si el destino estaba bloqueado, `ms-accounts` devolvia `CUENTA_NO_OPERATIVA` al intentar el abono. El servicio marcaba la transferencia como `RECHAZADA`, pero no revertia el cargo que ya habia hecho, asi que el dinero quedaba descontado del origen y no aparecia en el destino.

**Reproduccion:** Se anadio el test `doesNotDebitWhenDestinationIsBlocked` en `TransferServiceTest`. Antes de la correccion el servicio llamaba a `debit` antes de detectar el bloqueo; ahora el test comprueba que no se ejecuta ningun cargo ni abono cuando el destino no esta operativo. Tambien se anadio `doesNotDebitWhenDestinationDoesNotExist` para el caso de un IBAN de destino inexistente.

**Solucion aplicada (solo en `ms-transfers`):**

1. **Prevalidacion del destino:** Antes de mover dinero, si el destino es de ByteBank se consulta `GET /internal/accounts/{iban}`. Si la cuenta no existe o no esta `ACTIVA`, la transferencia se guarda como `RECHAZADA` con el motivo correspondiente y no se ejecuta ningun cargo.
2. **Reembolso compensatorio:** Si el cargo en origen ya se ha hecho y el abono en destino falla (por ejemplo, porque la cuenta se bloquea entre la validacion y el abono), se llama a `AccountsClient.refund`, que registra un `INGRESO` en la cuenta de origen con referencia `TRF-{id}-REV` y concepto de reversion. Despues la transferencia queda `RECHAZADA` con el codigo original.
3. **Fallo del reembolso:** Si el reembolso tambien falla, se guarda la transferencia como `RECHAZADA` con `rejectionReason = "REVERSION_FALLIDA"` y se registra un error en el log para conciliacion manual. No se reintenta automaticamente porque `ms-accounts` todavia no soporta claves de idempotencia.

Adicional al bug de la cuenta bloqueada, se detecto que el mismo fallo de control de acceso permitia a un usuario mover dinero desde cuentas ajenas si conocia el IBAN. Por ejemplo, Ana podia hacer una transferencia desde la cuenta de Luis y el sistema no lo detectaba.
**Causa raiz:** `ms-transfers/.../TransferService.java` llamaba a `accountsClient.getAccount(origin)` para comprobar que la cuenta existia, pero descartaba la respuesta y no comparaba el `ownerId` de la cuenta con el `id` del usuario autenticado. El endpoint interno de debito de `ms-accounts` tampoco recibe el usuario, solo la clave interna, asi que no podia aplicar esta autorizacion. El resultado era un fallo de control de acceso tipo IDOR: la victima perdia dinero desde su propia cuenta.

**Solucion aplicada (solo en `ms-transfers`):** Antes de guardar la transferencia, validar el destino o mover dinero, `TransferService.create` recupera la cuenta origen y comprueba su titular:

La respuesta es `403 ACCESO_DENEGADO`. No se usa `409 Conflict` porque no es un conflicto de estado de la cuenta, sino un problema de autorizacion. Se mantiene el mismo criterio que `ms-accounts` aplica en SEG-01 para consultas de cuentas ajenas.

**Tests anadidos:** `doesNotDebitWhenDestinationIsBlocked`, `doesNotDebitWhenDestinationDoesNotExist`, `refundsOriginWhenCreditFails` y `marksReversionFailedWhenRefundFails` en `TransferServiceTest`.
`rejectsTransferFromAccountOwnedByAnotherUser` en `TransferServiceTest`. Verifica que la peticion falla con `403 ACCESO_DENEGADO` y que no se guarda la transferencia ni se ejecuta ningun cargo, abono o reembolso.

Resultado actual: 9 tests de `common` y 12 tests de `ms-transfers` en verde (0 fallos, 0 errores).

**Riesgo residual:** Los endpoints internos de `ms-accounts` siguen confiando en la `X-Internal-Key` y no reciben el usuario autenticado, por lo que la autorizacion depende de que cada servicio que los invoca la aplique correctamente. Como mejora de defensa en profundidad se podria anadir el `userId` al contrato interno de debito y validar tambien en `ms-accounts`, pero eso queda fuera de este ticket. 
La compensacion cubre el caso normal de fallo del abono, pero no es una saga completa. Si `ms-accounts` confirma el abono pero la respuesta se pierde por timeout, `ms-transfers` podria reembolsar al origen y duplicar el dinero. Para cerrar ese hueco hace falta idempotencia y outbox en `ms-accounts`/`ms-transfers`, fuera del alcance de este ticket.

---
