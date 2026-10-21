# ADR-004 · Contrato HTTP (RF01, RF06)

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
La UI y otros clientes deben distinguir sin ambigüedad cinco situaciones: aplicado, rechazado por
negocio, repetición, conflicto y entrada inválida. El controller no debe conocer el puerto de persistencia.

## Alternativas
| Opción | Por qué no |
|---|---|
| 422 para rechazos de negocio | El reporte sí se procesó y se guardó (RF03); es un resultado válido, no un error. |
| 201 también para el replay | Daría a entender que se creó algo nuevo. |
| Devolver el reporte original en el 409 | Expone más datos de los necesarios ante un payload que no coincide. |
| `@Positive` en `amount` | Impediría demostrar y persistir el rechazo INVALID_AMOUNT. |

## Decisión
| Endpoint | Respuesta |
|---|---|
| `POST /api/payment-reports` | `201` + `Location` si APPLIED · `200` si REJECTED · `200` + `Idempotent-Replayed: true` con el original · `409` ProblemDetail (`paymentReference` y mensaje, sin el reporte) |
| `GET /api/payment-reports/{ref}` | `200` · `404` ProblemDetail |
| `GET /api/payment-reports?limit=` | `200`; `limit` entre 1 y 100, por defecto 20; fuera de rango → `400` |

- **Mapeo:** `PaymentReportController.toResponse` hace un `switch` con pattern matching sobre `ProcessingOutcome`.
- **Validación de entrada** (`PaymentReportRequest`): `@NotBlank`, con `@Size` de 50/30/30 igual a las
  columnas, y `@Digits(17, 2)` en el monto.
- **Errores** (`ApiExceptionHandler`): ProblemDetail (RFC 9457).
  - 400 por `WebExchangeBindException`, con detalle por campo;
  - 400 por `HandlerMethodValidationException`, con detalle por parámetro;
  - 400 por `ServerWebInputException` (JSON malformado o tipos inválidos);
  - 404 por `PaymentReportNotFoundException`.
- **Respuesta** (`PaymentReportResponse`): no expone `payloadHash` ni el id interno.
- **Consultas:** pasan por `PaymentReportQueryService`.
- **Sin CORS:** el frontend usa el proxy de `ng serve`.

## Trade-offs
- Un cliente que solo mire el código HTTP verá 200 para un rechazo; debe leer `status` y `rejectionReason`.
- Sin autenticación, el 409 y el replay confirman que una referencia existe, y el replay y el
  `GET /{ref}` devuelven el reporte a quien conozca la referencia.

## Validación
- `PaymentReportControllerTest` (`@WebFluxTest`, 17 pruebas):
  - `appliedReportReturnsCreatedWithLocation`, `rejectedReportReturnsOk`,
    `replayReturnsOkWithHeaderAndOriginalResult`, `conflictReturnsProblemWithoutOriginalReport`;
  - `invalidBodyFieldsReturnBadRequestWithFieldDetails`, `malformedJsonReturnsBadRequest`,
    `wrongTypeInBodyReturnsBadRequest`;
  - `recentRejectsLimitOutOfRange` (0 y 101), `recentAcceptsLimitBoundaries` (1 y 100),
    `unknownReferenceReturnsNotFoundProblem`.
- `PaymentReportHttpIntegrationTest.appliesThenReplaysThenRejectsConflictingReport`: servidor real con
  Postgres; 201 → 200 replay → 409, con saldo final de 400.000.
