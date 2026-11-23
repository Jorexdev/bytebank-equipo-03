# Revisión de transferencias

Las transferencias superiores a 5.000 € no mueven dinero al crearse. Se
guardan con estado `PENDIENTE_REVISION` y solo un usuario con rol `GESTOR`
puede consultarlas, aprobarlas o rechazarlas.

## Autenticación

La API usa JWT en `Authorization: Bearer <token>`. Con el perfil `demo` están
disponibles:

| Usuario | Contraseña | Rol |
|---|---|---|
| `gestor@bytebank.test` | `Gestor1234!` | `GESTOR` |
| `ana@bytebank.test` | `Cliente1234!` | `CLIENTE` |

El token se obtiene mediante:

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"gestor@bytebank.test","password":"Gestor1234!"}'
```

## Listar transferencias pendientes

```http
GET /api/transfers/pending?page=0&size=20
Authorization: Bearer <token-de-gestor>
```

`page` empieza en cero y `size` acepta valores entre 1 y 100. La respuesta
mantiene el formato paginado común:

```json
{
  "content": [
    {
      "id": 42,
      "originIban": "ES...",
      "destinationIban": "ES...",
      "amount": 6000.00,
      "concept": "Alquiler",
      "status": "PENDIENTE_REVISION",
      "rejectionReason": null,
      "createdAt": "2026-11-20T12:00:00Z",
      "reviewReason": "TRANSFERENCIA_SUPERA_UMBRAL",
      "reviewedBy": null,
      "reviewedAt": null,
      "reviewNote": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Solo devuelve transferencias en estado `PENDIENTE_REVISION`, ordenadas de más
reciente a más antigua.

## Aprobar una transferencia

```http
POST /api/transfers/{id}/approve
Authorization: Bearer <token-de-gestor>
```

No requiere cuerpo. Devuelve `200 OK` con la transferencia resultante y los
campos `reviewedBy` y `reviewedAt`.

## Rechazar una transferencia

```http
POST /api/transfers/{id}/reject
Authorization: Bearer <token-de-gestor>
Content-Type: application/json

{
  "comment": "Operación no justificada"
}
```

El cuerpo es opcional: puede invocarse sin cuerpo, con `{}` o con `{ "comment": "..." }`.
`comment` es opcional y admite como máximo 255 caracteres; si se omite o solo contiene
espacios, `reviewNote` queda en `null`. La respuesta `200 OK` incluye `status: "RECHAZADA"`,
`rejectionReason: "RECHAZADA_POR_GESTOR"`, `reviewNote`, `reviewedBy` y `reviewedAt`.

## Errores

| HTTP | Código | Situación |
|---|---|---|
| 400 | `DATOS_NO_VALIDOS` | Paginación, comentario (> 255 caracteres) o cuerpo JSON inválido |
| 403 | `ACCESO_DENEGADO` | El usuario no tiene rol `GESTOR` |
| 404 | `TRANSFERENCIA_NO_ENCONTRADA` | No existe la transferencia |
| 409 | `TRANSFERENCIA_NO_PENDIENTE` | Ya fue revisada |
| 415 | `TIPO_CONTENIDO_NO_SOPORTADO` | Se envía cuerpo con un `Content-Type` distinto de `application/json` |
| 503 | `SERVICIO_NO_DISPONIBLE` | `ms-accounts` no responde antes de debitar (la transferencia permanece en `PENDIENTE_REVISION` para reintentar) |

## Prueba E2E con Docker Compose

Requisitos: Docker Compose, `curl` y Python 3. Primero copia `.env.example` a
`.env` y establece secretos locales; después levanta el entorno:

```bash
cp .env.example .env
docker compose up -d --build
./scripts/e2e-transfer-review.sh
```

El script usa los usuarios demo, obtiene dinámicamente una cuenta de Ana,
crea una transferencia superior a 5.000 €, verifica que aparece pendiente en
la bandeja del gestor y la rechaza con una nota. Se puede cambiar la URL del
gateway con `API_BASE`, por ejemplo:

```bash
API_BASE=http://localhost:8080 ./scripts/e2e-transfer-review.sh
```

Al terminar:

```bash
docker compose down
```
