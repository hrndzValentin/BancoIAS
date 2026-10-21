# ADR-002 · Idempotencia por `paymentReference` (RF05)

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
La misma `paymentReference` puede llegar varias veces, incluso en paralelo. No puede aplicarse ni
descontarse dos veces, debe devolverse el resultado original y, si los datos difieren, el reporte
original debe preservarse.

## Alternativas
| Opción | Por qué no |
|---|---|
| Solo "¿existe?" antes de procesar | Hay una carrera entre la consulta y el `INSERT`. |
| Tabla aparte de claves de idempotencia (header `Idempotency-Key`) | El enunciado ya define la referencia como clave única; agrega estado. |
| Lock distribuido por referencia | Infraestructura extra para algo que la base ya garantiza. |

## Decisión
- **Fuente de verdad:** `UNIQUE(payment_reference)` en `payment_report`, más un `payload_hash` SHA-256
  de los campos de negocio (`PaymentCommand.payloadHash()`).
- **Normalización única:** el constructor de `PaymentCommand` aplica `trim()` a la referencia, la orden
  y el comercio. El monto se fija a escala 2 (HALF_UP, igual que `NUMERIC(19,2)`), así que `600000` y
  `600000.00` producen el mismo hash.
- **Flujo** (`ProcessPaymentReportUseCase.process`):
  1. Camino rápido, fuera de la transacción: `findByReference`. Con el mismo hash devuelve `Replayed`
     (también si el original fue REJECTED); con un hash distinto, `Conflict`.
  2. Si la referencia no existe, sigue el flujo transaccional de ADR-001.
  3. `onErrorResume(DuplicateKeyException)` envuelve al `TransactionalOperator`. Cuando actúa, la
     transacción ya hizo rollback, incluido el descuento de saldo. Entonces relee el original y aplica la
     misma comparación.
- **Solo se captura `DuplicateKeyException`.** Postgres responde 23505, r2dbc-postgresql lo convierte
  en `R2dbcDataIntegrityViolationException` y Spring R2DBC 7.0.9 lo traduce a `DuplicateKeyException`.
  Una violación de CHECK (23514) llega como `DataIntegrityViolationException` y se propaga como error.
- **Resultado sin excepciones:** `sealed interface ProcessingOutcome` con `Processed`, `Replayed` y `Conflict`.

## Trade-offs
- Las peticiones duplicadas en paralelo ejecutan el UPDATE y luego hacen rollback: trabajo desperdiciado
  y espera por el lock de fila, a cambio de corrección sin locks adicionales.
- Un reintento con un payload corregido para una referencia que salió REJECTED recibe `Conflict`; el
  cliente debe usar una referencia nueva.
- El hash no distingue montos con más de 2 decimales. La API los bloquea con `@Digits(fraction = 2)`.

## Validación
- `ProcessPaymentReportIntegrationTest`:
  - `parallelRequestsWithSameReferenceApplyOnce` (5 en paralelo → 1 `Processed`, 4 `Replayed`, 1 fila, saldo 700.000);
  - `concurrentDistinctAndRepeatedReferencesApplyEachReferenceOnce`;
  - `repeatedReferenceWithSamePayloadReplaysOriginal`, `repeatedReferenceOfRejectedReportReplaysRejection`,
    `repeatedReferenceWithDifferentAmountIsConflictAndKeepsOriginal`.
- `ProcessPaymentReportUseCaseTest`: `duplicateKeyRereadsAndReplaysOriginalWithSamePayload`,
  `duplicateKeyRereadsAndReportsConflictWithDifferentPayload`, `otherIntegrityViolationsPropagateWithoutReread`.
- `R2dbcPaymentReportRepositoryIntegrationTest`: `uniqueViolationIsTranslatedToDuplicateKeyException`,
  `checkViolationIsNotTranslatedToDuplicateKeyException`.
- Con el log de SQL activado: 25 INSERT y 25 UPDATE en 5 repeticiones de la prueba en paralelo. La rama
  del duplicado se ejecuta de verdad, y aun así el saldo termina siempre en 700.000.
