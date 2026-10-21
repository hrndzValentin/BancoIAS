# ADR-005 · Arquitectura

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
La prueba técnica pide un diseño defendible y escalable, pero acotado a unas 2 horas. Las reglas de
negocio (RF02) deben poder probarse sin Spring ni base de datos, y la concurrencia sin tocar la capa web.

## Alternativas
| Opción | Por qué no |
|---|---|
| Capas clásicas controller → service → repository | Mezcla reglas de negocio con persistencia; las reglas no se prueban aisladas. |
| Hexagonal completa (un puerto por dependencia, mappers en cada borde) | Ceremonia desproporcionada para el alcance. |
| Caso de uso que depende directo del adaptador concreto | La dependencia apuntaría de `application` hacia `infrastructure`. |

## Decisión
**Hexagonal ligera** en `banco-ias/src/main/java/com/ias/banco_ias/`:

| Paquete | Contenido | Depende de |
|---|---|---|
| `domain` | records `CollectionOrder`, `PaymentCommand`, `PaymentReport`; enums; `PaymentRules.evaluate` (función pura) | nada (sin Spring ni R2DBC) |
| `application` | `ProcessPaymentReportUseCase`, `PaymentReportQueryService`, puerto `PaymentReportRepository`, `ProcessingOutcome` | `domain`, `TransactionalOperator` y `DuplicateKeyException` de Spring |
| `infrastructure` | `persistence` (adaptador R2DBC), `web` (controller, DTOs, advice), `config` (`Clock`, `TransactionalOperator`) | `application`, `domain` |

- Hay un único puerto, `PaymentReportRepository`, implementado por el adaptador, para que la dependencia
  vaya de `infrastructure` hacia `application`.
- **Concesión pragmática:** `application` usa `TransactionalOperator` y `DuplicateKeyException` de Spring,
  en lugar de abstraer la transacción y la excepción.
- **Frontend** (`frontend/`, Angular 22.2.1):
  - un solo componente standalone (`App`) con estado en signals y formulario reactivo;
  - `PaymentReportService` traduce el código HTTP y los headers a la unión discriminada `PaymentResult`,
    así que el componente no inspecciona status ni headers;
  - sin routing, SSR ni librerías de UI.

## Trade-offs
- `application` no es independiente de Spring. Cambiar el gestor de transacciones tocaría el caso de uso.
- Un solo componente en el frontend es suficiente para RF07; si la UI crece, conviene dividirlo.

## Validación
- `grep` de imports: ni `domain` ni `application` importan `infrastructure`, y `domain` no importa Spring.
- `PaymentRulesTest` y `PaymentCommandTest`: JUnit puro, sin contexto de Spring.
- `ProcessPaymentReportUseCaseTest`: Mockito sobre el puerto, con `TransactionalOperator` en passthrough.
- `PaymentReportControllerTest`: `@WebFluxTest` con los casos de uso mockeados.
- `payment-report.service.spec.ts`: `HttpTestingController` cubre cada variante de `PaymentResult`.
