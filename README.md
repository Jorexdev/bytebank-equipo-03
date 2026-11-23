# ByteBank

Backend de la banca online de ByteBank: cuentas, transferencias, tarjetas de débito, validación antifraude y avisos
de movimientos. Microservicios con Spring Boot que se comunican por HTTP (y por Kafka para los avisos), con una base
de datos PostgreSQL por servicio.

## Servicios

| Servicio | Puerto | Qué hace |
|---|---|---|
| ms-gateway | 8080 | Punto de entrada. Solo enruta las rutas `/api/**` |
| ms-auth | 8081 | Registro, login y emisión de tokens JWT |
| ms-accounts | 8082 | Cuentas, saldo y movimientos (extracto) |
| ms-transfers | 8083 | Transferencias entre cuentas |
| ms-cards | 8084 | Tarjetas de débito y pagos |
| ms-fraud | 8085 | Validación antifraude de transferencias |
| ms-notifications | 8086 | Avisos al cliente de cada movimiento (consume eventos de Kafka) |

Más detalle en [docs/arquitectura.md](docs/arquitectura.md).

## Stack

Java 17, Spring Boot 3.3, Spring Security con JWT, Spring Data JPA, Flyway, PostgreSQL 15, Spring Cloud Gateway,
Apache Kafka (modo KRaft), JUnit 5, Mockito, Testcontainers, Docker Compose y GitHub Actions.

## Arrancar en local

```bash
cp .env.example .env      # y cambia las claves
docker compose up --build
```

La API queda en `http://localhost:8080`. Cada servicio publica su Swagger en
`http://localhost:<puerto>/swagger-ui.html` (por ejemplo, `http://localhost:8082/swagger-ui.html` para ms-accounts).

Con el perfil `demo` (el que viene en `.env.example`) se crean estos usuarios y unas cuentas con saldo:

| Usuario | Contraseña | Rol |
|---|---|---|
| gestor@bytebank.test | Gestor1234! | GESTOR |
| ana@bytebank.test | Cliente1234! | CLIENTE (2 cuentas) |
| luis@bytebank.test | Cliente1234! | CLIENTE (1 cuenta) |

Ejemplo:

```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
# usa el token devuelto en la cabecera Authorization: Bearer <token>
curl -s localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN"
```

## Compilar y tests

```bash
./mvnw clean verify
```

Los tests de integración usan Testcontainers, así que necesitan Docker arrancado.

## Estado del proyecto

Todos los módulos y flujos principales del backend están implementados y cubiertos por la CI (compilación, tests unitarios/integración con Testcontainers y construcción de las 7 imágenes Docker):

- **ms-auth**: registro, login y emisión de JWT con secretos por variables de entorno (sin credenciales en código ni contraseñas en logs).
- **ms-accounts**: cuentas individuales y compartidas (hasta 4 titulares, `POST /api/accounts/{iban}/holders`), control de acceso por titularidad/rol, saldos con `BigDecimal`/`NUMERIC(19,2)`, concurrencia con `PESSIMISTIC_WRITE`, extracto paginado en base 0 con filtro por fechas en zona `Europe/Madrid`, y publicación en Kafka tras el commit de la transacción (`afterCommit`). Ver [docs/contratos/ms-accounts.md](docs/contratos/ms-accounts.md).
- **ms-transfers**: transferencias entre cuentas propias y externas con soporte de co-titulares, validación de importes, prevalidación de destino con reembolso compensatorio (`TRF-<id>-REV`), integración fail-closed con `ms-fraud`, y flujo completo de revisión por `GESTOR` para transferencias pendientes (`GET /api/transfers/pending`, `POST /api/transfers/{id}/approve`, `POST /api/transfers/{id}/reject`). Ver [docs/transferencias-revision.md](docs/transferencias-revision.md).
- **ms-fraud**: reglas de importe máximo (`>= 20.000 €`), transferencia externa de importe alto (`>= 1.000 €`) y frecuencia (`> 5` en 10 min), con consulta restringida a `GESTOR` (`GET /api/fraud/checks`). Ver [docs/fraude.md](docs/fraude.md).
- **ms-cards**: emisión (`POST /api/cards`), consulta, bloqueo/desbloqueo y pago con tarjetas de débito (`POST /api/cards/{id}/payments`), límite diario de 600 € con bloqueo pesimista contra condiciones de carrera y soporte de co-titulares. Ver [docs/contratos/ms-cards.md](docs/contratos/ms-cards.md).
- **ms-notifications**: consumo idempotente de eventos de Kafka por `(user_id, movement_id)` para todos los titulares de la cuenta, conservación exacta con `BigDecimal` y consulta paginada en `GET /api/notifications`. Ver [docs/contratos/ms-notifications.md](docs/contratos/ms-notifications.md).
- **Decisiones de arquitectura**: ver [docs/decisiones.md](docs/decisiones.md).
- **Diagnóstico y registro de riesgos por gravedad**: ver [docs/diagnostico.md](docs/diagnostico.md).
