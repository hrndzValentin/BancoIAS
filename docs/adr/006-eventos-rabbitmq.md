# ADR-006 · Evento PaymentApplied a RabbitMQ (punto opcional)

**Estado:** borrador para revisión (redactado a partir de las decisiones tomadas durante el desarrollo)

## Contexto
Cuando un reporte queda APPLIED, otros sistemas deben poder reaccionar. El pago ya está confirmado en
Postgres: la publicación no puede deshacerlo ni cambiar la respuesta HTTP. El backend es reactivo.

## Alternativas (verificadas el 2026‑10‑20)
| Opción | Evaluación |
|---|---|
| Reactor RabbitMQ | **Archivado**: el repo redirige a `spring-attic/reactor-rabbitmq` (`archived: true`). Última versión 1.5.6 (Maven Central, 2023‑03), compilada contra `reactor-core` 3.4.28 y `amqp-client` 5.14.2. No está en `reactor-bom` 2025.0.7 ni en el BOM de Boot 4.1.1, y no tiene autoconfiguración, `@ServiceConnection` ni health. |
| **spring-amqp + `boundedElastic`** | `spring-amqp` 4.1.1 y `amqp-client` 5.30.0 gestionados por Boot 4.1.1. Autoconfiguración y publisher confirms por propiedad. API bloqueante, que se aísla. |
| Publicar dentro de la transacción | Publicaría pagos que luego hacen rollback (por ejemplo, un duplicado de RF05). |

## Decisión
- **Puerto y adaptador:** el puerto `application/PaymentEventPublisher` lo implementa
  `infrastructure/messaging/RabbitPaymentEventPublisher`. `RabbitTemplate.send` corre en
  `Schedulers.boundedElastic()` (nunca en Netty) y el confirm (`CorrelationData.getFuture()`) se encadena
  con `Mono.fromFuture`. Un nack se trata como error.
- **Cuándo se publica:** `ProcessPaymentReportUseCase.publishIfApplied` se encadena **después** del
  `TransactionalOperator` y solo para `Processed` con APPLIED. Nunca en REJECTED, `Replayed` ni `Conflict`.
- **Best-effort:** se aplica `timeout(app.events.publish-timeout = 2s)`. Si falla o vence el timeout, se
  registra un log de error y el pago sigue confirmado (`onErrorResume`). No hay `subscribe()` manual.
- **Topología mínima:** el exchange topic durable `payments.events` y la routing key `payment.applied`.
  Las colas son de los consumidores; la app no declara ninguna.
- **Mensaje:** JSON con `eventId`, `eventType`, `paymentReference`, `collectionOrderId`, `merchantId`,
  `amount` y `occurredAt`; persistente; `messageId = eventId`. El `eventId` es determinista
  (`UUID.nameUUIDFromBytes("PaymentApplied:" + referencia)`), lo que permite deduplicar.
- **Activación:** con `app.events.enabled=false` se usa `NoOpPaymentEventPublisher`. El perfil de pruebas
  lo desactiva y solo las pruebas de RabbitMQ lo activan.
- **Health:** `management.health.rabbit.enabled=false`. Un broker caído no marca el servicio como DOWN; la
  caída se ve en los logs de error de publicación.
- **Conexión:** `spring.rabbitmq.connection-timeout=2s`, para no retener un hilo de `boundedElastic` 60 s.

## Trade-offs y garantía
- **At-most-once.** Si la app cae entre el commit y la publicación, si el broker no está o si vence el
  timeout, el evento se pierde. Tampoco se reintenta, así que no se duplica.
- **Outbox** (mejora, no implementada): insertar el evento en una tabla `outbox_event` en la misma
  transacción que el UPDATE y el INSERT, y que un relay lo publique con confirms y lo marque como enviado.
  Así la garantía pasa a **at-least-once**, y los consumidores deduplican por `eventId`.
- **Excepción a "nada bloqueante"** (registrada en `CLAUDE.md`): queda acotada a esta publicación.
- Con el broker caído, el hilo de `boundedElastic` puede quedar ocupado hasta el `connection-timeout`,
  aunque el caso de uso ya haya respondido.

## Validación
- `PaymentEventPublishingIntegrationTest` (Postgres + RabbitMQ 4.1.4 en Testcontainers):
  - `appliedPaymentPublishesExactlyOneEventWithTheContract` (un solo mensaje, los 7 campos, persistente,
    `application/json`, `messageId`, exchange y routing key);
  - `rejectedPaymentPublishesNothing`;
  - `replayDoesNotPublishAgain`.
- `PaymentEventBrokerDownIntegrationTest.paymentIsAppliedAndResponseIsSuccessfulWhenBrokerIsDown`: el
  broker es inalcanzable y la respuesta es 201, con el saldo descontado y el log de error.
- `PaymentEventPublicationTest` (unitaria):
  - `appliedPaymentPublishesOneEventAfterProcessing`, `rejectedPaymentDoesNotPublish`,
    `replayedAndConflictingReportsDoNotPublish`, `publicationFailureKeepsThePaymentApplied`;
  - `slowPublicationTimesOutAndKeepsThePaymentApplied`: un publisher de prueba de 10 s, con tiempo
    virtual, se corta a los 2 s.
- Las 78 pruebas previas pasan sin modificarse.
- Manual con contenedores desechables:
  - un POST aplicado dejó 1 mensaje en una cola enlazada y el rechazado no dejó ninguno;
  - con el broker detenido, el POST respondió 201 en 42 ms y `/actuator/health` siguió en `UP`.
