# ADR-003 · Persistencia

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
Los reportes procesados deben persistirse (RF03). Las garantías de RF04 y RF05 dependen de la base:
locks de fila, unicidad y CHECKs. El stack es reactivo, así que JPA y JDBC bloqueantes quedan fuera.

## Alternativas
| Opción | Por qué no |
|---|---|
| H2 o almacenamiento en memoria | No reproduce la semántica de locks y aislamiento de Postgres que sostiene ADR-001. |
| Spring Data R2DBC con entidades y repositorios | El UPDATE condicional y el `RETURNING` igual requieren SQL explícito; agrega mapeo sin beneficio. |
| Flyway o Liquibase | Correcto a futuro; para el alcance de 2 h basta `spring.sql.init`. |

## Decisión
- **Motor y acceso:** PostgreSQL 16 (`banco-ias/compose.yaml`) con R2DBC `DatabaseClient` y SQL explícito
  en `R2dbcPaymentReportRepository`.
- **Inicialización:** `schema.sql` (`CREATE … IF NOT EXISTS`) y `data.sql` (`INSERT … ON CONFLICT DO NOTHING`)
  con `spring.sql.init.mode: always`. Son idempotentes: un reinicio no borra datos ni restaura saldos.
- **Modelo:**
  - `collection_order(id, merchant_id, status CHECK, pending_balance NUMERIC(19,2) CHECK >= 0)`.
  - `payment_report`, con:
    - `payment_reference UNIQUE`;
    - **sin FK** a la orden, para poder guardar ORDER_NOT_FOUND;
    - `amount` sin CHECK de signo, para poder guardar INVALID_AMOUNT;
    - CHECK de `status` y de `rejection_reason`;
    - `chk_rejection_consistency` y `chk_applied_amount_positive`;
    - `payload_hash CHAR(64)` y `processed_at TIMESTAMPTZ`.
- **Índice** `(processed_at DESC, id DESC)` para "recientes", con el `id` como desempate estable.
- **Hora de procesamiento:** `processedAt` sale de un `Clock` inyectado y se trunca a microsegundos, la
  precisión de Postgres, para que el reporte devuelto sea idéntico al guardado.

## Trade-offs
- Sin migraciones: cambiar el esquema de una base existente exige `docker compose down -v`.
- Las razones de rechazo existen en dos lugares, el enum Java y el CHECK. Agregar una razón exige migrar
  el CHECK; si no, el `INSERT` falla.
- `BigDecimal` en todo el backend; el frontend serializa el monto como `number` de JavaScript.

## Validación
- `R2dbcPaymentReportRepositoryIntegrationTest` (8 pruebas: UPDATE condicional, `findByReference`,
  `findRecentOrdersByProcessedAtThenIdAndAppliesLimit`, traducción de errores).
- `ProcessPaymentReportIntegrationTest.persistsEachRejectionWithoutTouchingBalances`: los 5 rechazos
  quedan guardados, incluido ORDER_NOT_FOUND.
- Se ejecutaron `schema.sql` y `data.sql` dos veces seguidas sobre Postgres 16: sin errores y con 3 órdenes.
