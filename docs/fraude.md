# Validación antifraude (ms-fraud)

ms-fraud recibe cada transferencia antes de ejecutarse y devuelve un veredicto:

| Veredicto | Qué significa |
|---|---|
| `APROBADA` | Se puede ejecutar |
| `REVISAR` | Un gestor tiene que revisarla antes de ejecutarla |
| `BLOQUEADA` | Se rechaza |

Si saltan varias reglas, gana el veredicto más grave. Cada validación queda guardada (tabla `fraud_checks`)
y los gestores pueden consultarlas en `GET /api/fraud/checks?iban=...`.

## Reglas acordadas con negocio

| Regla | Código | Condición | Veredicto | Estado |
|---|---|---|---|---|
| Importe máximo por operación | `IMPORTE_MAXIMO` | Importe de 20.000 € o más | BLOQUEADA | Hecha |
| Transferencia a otra entidad | `EXTERNA_IMPORTE_ALTO` | Destino de otro banco e importe de 1.000 € o más | REVISAR | Hecha |
| Frecuencia | `FRECUENCIA_ALTA` | Más de 5 transferencias desde la misma cuenta en 10 minutos (se bloquea la sexta) | BLOQUEADA | Hecha |

Los umbrales se configuran en `application.yml` (`bytebank.fraud.*`).

## Contrato

`POST /internal/fraud/check` (con `X-Internal-Key`)

```json
{ "originIban": "ES...", "destinationIban": "ES...", "userId": 2, "amount": 1500.00 }
```

Respuesta:

```json
{ "checkId": 17, "verdict": "REVISAR", "reasons": ["EXTERNA_IMPORTE_ALTO"] }
```

## Cómo actúa ms-transfers

`ms-transfers` llama a este endpoint en cada transferencia, después de prevalidar el destino y antes
de mover dinero. Con la respuesta aplica lo siguiente:

| Veredicto | Qué hace ms-transfers |
|---|---|
| `APROBADA` | Continúa con el cargo y el abono normal |
| `REVISAR` | Guarda la transferencia como `PENDIENTE_REVISION`, sin mover dinero |
| `BLOQUEADA` | Guarda la transferencia como `RECHAZADA` con `FRAUDE_DETECTADO`, sin mover dinero |

Además:

- **Fail-closed:** si `ms-fraud` no responde o la respuesta no trae veredicto, la operación falla con
  `503 SERVICIO_NO_DISPONIBLE`. No se guarda la transferencia ni se toca el dinero.
- **Umbral local:** `ms-transfers` también pone en `PENDIENTE_REVISION` los importes de más de 5.000 €
  aunque el veredicto sea `APROBADA` (con 5.000,00 € exactos se ejecuta). Se configura en la
  `application.yml` de `ms-transfers` (`bytebank.transfers.review-threshold`, variable
  `TRANSFERS_REVIEW_THRESHOLD`).
- Cada transferencia guarda el `checkId` y el veredicto en las columnas `fraud_check_id` y
  `fraud_verdict` de su tabla (migración `V2__transfers.sql`).

## Pendiente

- Endpoints de gestor para listar/aprobar/rechazar las transferencias en `PENDIENTE_REVISION`
  (otro ticket).
- Los motivos de fraude (`reasons`) van al log pero no se persisten; en `REVISAR` se guarda un
  motivo genérico (`TRANSFERENCIA_EN_REVISION`). Falta decidir si se guardan y si también se
  persisten en `BLOQUEADA`.
