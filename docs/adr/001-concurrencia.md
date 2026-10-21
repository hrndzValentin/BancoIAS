# ADR-001 · Concurrencia sobre el saldo (RF04)

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
Varios reportes pueden llegar casi a la vez sobre la misma orden. El total aplicado nunca puede
superar el saldo pendiente, y no puede perderse ningún descuento (*lost update*). El backend es
WebFlux/R2DBC: no se admiten `block()`, `synchronized`, locks en memoria ni schedulers bloqueantes.

## Alternativas
| Opción | Por qué no |
|---|---|
| Leer saldo → restar en Java → `SET pending_balance = :nuevo` | Lost update: dos transacciones leen el mismo saldo y una pisa a la otra. |
| `SELECT … FOR UPDATE` (pesimista) | Correcto, pero agrega una ida a la base y mantiene el lock más tiempo. |
| Columna `version` (optimista) + reintentos | Exige lógica de reintento reactiva; bajo contención genera muchos fallos. |
| `REPEATABLE READ` / `SERIALIZABLE` | Postgres aborta con error de serialización; también exige reintentos. |
| Lock en memoria por orden | No funciona con más de una instancia y está prohibido por las reglas del proyecto. |

## Decisión
Un único `UPDATE` condicional dentro de la misma transacción que el `INSERT` del reporte,
con aislamiento `READ COMMITTED` (el default de Postgres):

```sql
UPDATE collection_order SET pending_balance = pending_balance - :amount
 WHERE id = :id AND merchant_id = :merchantId AND status = 'OPEN' AND pending_balance >= :amount
```
(`R2dbcPaymentReportRepository.decreaseBalanceIfAvailable`; orquestado por
`ProcessPaymentReportUseCase` con `TransactionalOperator`.)

- El dominio (`PaymentRules.evaluate`) decide la razón del rechazo con la orden leída. Ese saldo leído
  nunca se escribe: Postgres calcula el nuevo saldo sobre la versión vigente de la fila.
- **Cuando dos transacciones actualizan la misma fila**, B espera el lock de fila de A. Cuando A hace
  commit, Postgres reevalúa el `WHERE` de B sobre la versión confirmada (*EvalPlanQual*). Si ya no
  alcanza el saldo, B actualiza 0 filas.
- **`false` significa saldo insuficiente:** la orden, el comercio y el estado se acaban de validar en la
  misma transacción, y solo `pending_balance` cambia en el sistema. Este supuesto está comentado junto
  al mapeo en el caso de uso.
- **Segunda capa:** `CHECK (pending_balance >= 0)` en `schema.sql`.

## Trade-offs
- Si algún día el estado de una orden puede cambiar, `false` deja de ser inequívoco y habría que releer
  la orden para dar la razón correcta.
- Quien pierde la carrera recibe `INSUFFICIENT_BALANCE` aunque su lectura previa mostrara saldo.
- El lock de fila se mantiene hasta el commit, que incluye el `INSERT` del reporte (una sentencia corta).

## Validación
Las pruebas están en `ProcessPaymentReportIntegrationTest` y `R2dbcPaymentReportRepositoryIntegrationTest`,
contra Postgres 16 en Testcontainers con un pool de 20 conexiones. Las de concurrencia se repiten 5
veces (`@RepeatedTest(5)`).
- `concurrentReportsNeverExceedPendingBalance`: 10 × 300.000 sobre 1001 → 3 APPLIED, 7 rechazados, saldo 100.000.
- `concurrentReportsOnTwoOrdersDoNotInterfere`, `concurrentReportsOnBlockedOrderAreAllRejected`,
  `onlyOneOfTwoConcurrentDecreasesSucceeds`.
- Con el log de SQL activado: 50 UPDATE en 5 repeticiones, es decir, los 10 comandos de cada repetición
  compiten de verdad en el UPDATE.

**Pruebas de mutación** (se corrieron esas dos clases, 40 ejecuciones, y luego se restauró el código):

| Mutación | Fallan | Dónde falla |
|---|---|---|
| 1. Sin `AND pending_balance >= :amount` | 17/40 | `onError(DataIntegrityViolationException … violates check constraint "collection_order_pending_balance_check")` en las 3 pruebas de concurrencia que agotan saldo y en 2 del adaptador. |
| 2. Mutación 1 + sin el `CHECK` | 13/40 | Conteo de APPLIED: `Expected size: 3 but was: 10` (`concurrentReportsNeverExceedPendingBalance`, `…TwoOrdersDoNotInterfere`); adaptador: `[true, true]` y `expected false; actual true`. |
| 3. Saldo calculado en Java (`SET pending_balance = :saldoCalculado`, mismo `WHERE`) | 15/40 | Conteo de APPLIED: `3 but was: 10`. Saldo: `expected 100000 but was 700000.00` (en una corrida `400000.00`) en `concurrentDistinctAndRepeatedReferencesApplyEachReferenceOnce`. |

**Qué detecta cada capa:**
- **Conteo de APPLIED y saldo final:** detectan sobregiros (mutaciones 2 y 3).
- **Invariante de conservación** (Σ APPLIED + saldo final = saldo inicial): detecta lost updates.
  En las tres mutaciones la prueba se detuvo antes, en el conteo o en el saldo, así que la aserción del
  invariante **no llegó a ejecutarse**. Aun así, los valores observados en la mutación 3 lo violan:
  3 APPLIED × 300.000 + 700.000 = 1.600.000 ≠ 1.000.000. Ese saldo positivo pero incorrecto es un lost
  update; no es un sobregiro.
- **`CHECK`:** es la segunda capa en la base. Sin la condición del `WHERE`, convierte un sobregiro
  silencioso en un error (mutación 1).
