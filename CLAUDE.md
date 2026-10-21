# BancoIAS PSE - Prueba técnica

Requisitos: ver docs/REQUIREMENTS.md (fuente de verdad).

## Stack
- banco-ias/: Java 21, Spring Boot WebFlux, R2DBC, PostgreSQL, Testcontainers
- frontend/: Angular (standalone components)

## Arquitectura
Hexagonal ligera: domain / application / infrastructure. Nada de JPA ni código bloqueante.

## Decisiones ya tomadas
- RF04: UPDATE atómico condicional (pending_balance >= amount) en la misma transacción.
- RF05: UNIQUE(payment_reference) + hash del payload; mismo payload → resultado original, distinto → 409.

## Reglas de trabajo
- Un paso del plan = un commit con Conventional Commits.
- NO hagas commit sin que yo lo pida.
- Antes de cambios grandes, propón el plan y espera aprobación.
- Cada feature lleva sus pruebas.
