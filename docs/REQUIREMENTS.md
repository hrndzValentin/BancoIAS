# Requisitos — Prueba BancoIAS (WebFlux + Angular)

Core de recaudos PSE: aplicar reportes de pago (abonos parciales) sobre órdenes de recaudo.
Los reportes pueden llegar duplicados o simultáneos. Nunca exceder el saldo pendiente. Alcance: ~2 h.

## Seed (sin pagos aplicados al iniciar)

| Orden        | Comercio | Estado  | Saldo (COP) |
|--------------|----------|---------|------------:|
| ORD-PSE-1001 | COM-10   | OPEN    | 1000000     |
| ORD-PSE-1002 | COM-10   | BLOCKED | 800000      |
| ORD-PSE-2001 | COM-20   | OPEN    | 2000000     |

Reporte: `paymentReference`, `collectionOrderId`, `merchantId`, `amount`. `processedAt` lo asigna el sistema.

## Requisitos funcionales

- **RF01** Procesar un reporte y registrar fecha/hora de procesamiento.
- **RF02** Aplicar solo si: amount > 0, la orden existe, pertenece al merchantId, está OPEN y amount ≤ saldo.
- **RF03** Persistir todo reporte: datos, APPLIED/REJECTED, processedAt, razón. Un rechazo no descuenta saldo.
- **RF04** Reportes simultáneos sobre la misma orden no pueden superar el saldo.
- **RF05** Referencia repetida: sin segunda aplicación; devolver el resultado original. Con datos distintos: respuesta controlada, el original se preserva.
- **RF06** GET por paymentReference y listado de recientes (sin paginación avanzada).
- **RF07** Angular: registrar reporte, mostrar resultado y listar recientes con integración real (UI simple, comercio COM-10).

## Restricciones

- Spring Boot WebFlux (nada bloqueante) y Angular. Reportes persistidos.
- Fuera de alcance: auth, CRUD de órdenes/comercios, integración PSE real.
- Pruebas automatizadas de las reglas (sin % de cobertura exigido).
- Opcional: evento a RabbitMQ cuando un reporte es APPLIED.
