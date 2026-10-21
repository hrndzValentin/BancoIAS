# BancoIAS · Core de recaudos PSE

Solución full stack para la prueba técnica (Java Spring Boot WebFlux + Angular). Registra reportes de
pago PSE sobre órdenes de recaudo, aplica abonos parciales sin superar nunca el saldo pendiente, aunque
lleguen reportes simultáneos o repetidos, persiste el resultado de cada reporte y permite consultarlos.
Los requisitos están resumidos en [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md).

## Arquitectura
```
banco-ias/                     backend (Java 21, Spring Boot 4.1.1 WebFlux, R2DBC, PostgreSQL 16)
  src/main/java/com/ias/banco_ias/
    domain/                    records, enums y PaymentRules (reglas RF02, sin Spring)
    application/               ProcessPaymentReportUseCase, PaymentReportQueryService,
                               puerto PaymentReportRepository, ProcessingOutcome
    infrastructure/
      persistence/             adaptador R2DBC (UPDATE condicional, INSERT … RETURNING)
      web/                     controller, DTOs, manejo de errores con ProblemDetail
      config/                  Clock, TransactionalOperator
  src/main/resources/          schema.sql, data.sql (semilla), application.yaml
frontend/                      Angular 22.2.1 standalone, signals, sin librerías de UI
docs/adr/                      decisiones técnicas
```
Decisiones técnicas: [001 Concurrencia](docs/adr/001-concurrencia.md) ·
[002 Idempotencia](docs/adr/002-idempotencia.md) · [003 Persistencia](docs/adr/003-persistencia.md) ·
[004 Contrato HTTP](docs/adr/004-contrato-http.md) · [005 Arquitectura](docs/adr/005-arquitectura.md)

## Requisitos previos
| Herramienta | Versión usada |
|---|---|
| Java (JDK) | 21 (OpenJDK 21.0.12) |
| Maven | incluido con el wrapper `./mvnw` |
| Node.js / npm | 24.15.0 / 11.12.1 |
| Docker / Docker Compose | 28.4.0 / v5.1.0 (Postgres y Testcontainers) |

**Puertos:** 5432 (PostgreSQL), 8080 (API), 4200 (UI).

**Datos semilla** (`data.sql`; al iniciar no hay pagos aplicados):

| Orden | Comercio | Estado | Saldo (COP) |
|---|---|---|---:|
| ORD-PSE-1001 | COM-10 | OPEN | 1.000.000 |
| ORD-PSE-1002 | COM-10 | BLOCKED | 800.000 |
| ORD-PSE-2001 | COM-20 | OPEN | 2.000.000 |

## Ejecución
```bash
# 1. Base de datos (opcional: spring-boot-docker-compose la levanta sola al arrancar el backend)
cd banco-ias && docker compose up -d

# 2. Backend → http://localhost:8080
./mvnw spring-boot:run

# 3. Frontend → http://localhost:4200 (proxy /api → :8080, sin CORS)
cd ../frontend && npm ci && npm start
```
Para volver a los saldos iniciales: `cd banco-ias && docker compose down -v`. `data.sql` no
sobrescribe órdenes existentes.

### Pruebas
```bash
cd banco-ias && ./mvnw test                 # 78 pruebas; requiere Docker (Testcontainers, postgres:16)
cd frontend && npx ng test --watch=false    # 12 pruebas (Vitest)
```

## Ejemplos con curl
Con la base recién creada:
```bash
API=http://localhost:8080/api/payment-reports
body() { echo "{\"paymentReference\":\"$1\",\"collectionOrderId\":\"$2\",\"merchantId\":\"$3\",\"amount\":$4}"; }

# Aplicado → 201 + Location: /api/payment-reports/PSE-REF-001
curl -i -X POST $API -H 'Content-Type: application/json' -d "$(body PSE-REF-001 ORD-PSE-1001 COM-10 600000)"
# Rechazado (orden BLOCKED) → 200, status REJECTED, rejectionReason ORDER_NOT_ENABLED
curl -i -X POST $API -H 'Content-Type: application/json' -d "$(body PSE-REF-002 ORD-PSE-1002 COM-10 100000)"
# Replay (misma referencia y mismos datos) → 200 + Idempotent-Replayed: true, resultado original
curl -i -X POST $API -H 'Content-Type: application/json' -d "$(body PSE-REF-001 ORD-PSE-1001 COM-10 600000)"
# Conflicto (misma referencia, otro monto) → 409 application/problem+json
curl -i -X POST $API -H 'Content-Type: application/json' -d "$(body PSE-REF-001 ORD-PSE-1001 COM-10 500000)"
# 400: campo inválido (detalle por campo), límite fuera de rango, JSON malformado
curl -i -X POST $API -H 'Content-Type: application/json' -d "$(body ' ' ORD-PSE-1001 COM-10 10.123)"
curl -i "$API?limit=0"
curl -i -X POST $API -H 'Content-Type: application/json' -d '{"paymentReference":'
# 404 → ProblemDetail
curl -i $API/NO-EXISTE
# Consultas (RF06)
curl -s $API/PSE-REF-001
curl -s "$API?limit=5"
```

## Recorrido desde la UI (http://localhost:4200)
1. **RF01/RF03:** pulsa *Generar*, elige `ORD-PSE-1001`, monto `600000` y envía → resultado verde
   "Pago aplicado"; el reporte aparece en la tabla con fecha de procesamiento.
2. **RF02:** con nuevas referencias prueba monto `0`, la orden `ORD-PSE-1002`, el comercio `COM-20` sobre
   1001, una orden inexistente y un monto mayor al saldo → resultado rojo con la razón en español; el
   saldo no cambia.
3. **RF05:** vuelve a enviar la misma referencia y los mismos datos → resultado azul, "resultado original
   del procesamiento". Cambia solo el monto → resultado ámbar, conflicto.
4. **RF06/RF07:** la tabla de recientes se recarga después de cada envío.
5. **RF04** no se puede demostrar a mano desde la UI; lo cubren las pruebas de concurrencia (ver tabla).

## Trazabilidad RF → implementación → prueba
| RF | Implementación | Prueba que lo verifica |
|---|---|---|
| RF01 | `PaymentReportController.process`, `ProcessPaymentReportUseCase.process` (`Clock` para `processedAt`) | `PaymentReportHttpIntegrationTest.appliesThenReplaysThenRejectsConflictingReport`, `ProcessPaymentReportUseCaseTest.appliesWhenBalanceIsDecreased` |
| RF02 | `PaymentRules.evaluate` | `PaymentRulesTest` (7 casos), `ProcessPaymentReportIntegrationTest.persistsEachRejectionWithoutTouchingBalances` |
| RF03 | `R2dbcPaymentReportRepository.insertReport`, `schema.sql` | `persistsEachRejectionWithoutTouchingBalances`, `ProcessPaymentReportUseCaseTest.missingOrderIsPersistedAsOrderNotFound` |
| RF04 | `R2dbcPaymentReportRepository.decreaseBalanceIfAvailable` (UPDATE condicional) | `concurrentReportsNeverExceedPendingBalance`, `concurrentReportsOnTwoOrdersDoNotInterfere`, `concurrentReportsOnBlockedOrderAreAllRejected`, `R2dbcPaymentReportRepositoryIntegrationTest.onlyOneOfTwoConcurrentDecreasesSucceeds` |
| RF05 | `ProcessPaymentReportUseCase` (camino rápido + `DuplicateKeyException`), `PaymentCommand.payloadHash` | `parallelRequestsWithSameReferenceApplyOnce`, `concurrentDistinctAndRepeatedReferencesApplyEachReferenceOnce`, `repeatedReference…` (3), `ProcessPaymentReportUseCaseTest.duplicateKey…` (2) |
| RF06 | `PaymentReportController.findByReference` / `findRecent`, `PaymentReportQueryService` | `PaymentReportControllerTest` (GET, 404, límites), `R2dbcPaymentReportRepositoryIntegrationTest.findRecentOrdersByProcessedAtThenIdAndAppliesLimit` |
| RF07 | `frontend/src/app/app.ts`, `payment-report.service.ts` | `payment-report.service.spec.ts` (11), `app.spec.ts` |

Las pruebas de RF04 y RF05 se repiten 5 veces y verifican el invariante Σ APPLIED + saldo final = saldo
inicial. Las pruebas de mutación están en [ADR-001](docs/adr/001-concurrencia.md#validación).

## Limitaciones y riesgos conocidos
- **Sin autenticación:** el 409 confirma que una referencia existe, y el replay y `GET /{ref}` devuelven
  el reporte a quien conozca la referencia.
- **`false` = saldo insuficiente:** el UPDATE no indica qué condición falló. Es correcto porque hoy solo
  cambia `pending_balance`; si el estado de las órdenes se vuelve mutable, habría que releer la orden
  ([ADR-001](docs/adr/001-concurrencia.md)).
- **Razones de rechazo atadas a un CHECK:** el enum Java y el CHECK de `rejection_reason` deben cambiar
  juntos. Sin migraciones, una razón nueva haría fallar el `INSERT`.
- **Sin migraciones:** `schema.sql` usa `IF NOT EXISTS`; un cambio de esquema exige `docker compose down -v`.
- **RabbitMQ (opcional):** no implementado.
- **El frontend envía el monto como `number` de JavaScript:** pierde precisión por encima de 2⁵³.

## Mejoras propuestas
- **Outbox transaccional** para publicar el evento "recaudo aplicado" a RabbitMQ sin perderlo ni duplicarlo.
- **Flyway** para versionar el esquema.
- **Paginación por cursor** `(processed_at, id)` en los recientes; el índice ya existe.
- **Autenticación y autorización** por comercio.

## Checklist de entregables
- [x] Código fuente del backend (`banco-ias/`)
- [x] Código fuente del frontend (`frontend/`)
- [x] Pruebas automatizadas (78 backend + 12 frontend)
- [x] Instrucciones de ejecución (este README)
- [x] Registro de decisiones técnicas (`docs/adr/`)
- [ ] Registro de uso de IA (`AI_USAGE.md`, pendiente)
- [x] Historial de commits
- [ ] Punto opcional RabbitMQ (no implementado)
