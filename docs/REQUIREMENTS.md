# Requisitos — Prueba técnica BancoIAS (Full Stack Java WebFlux / Angular)

> Resumen estructurado del enunciado `01_Prueba_Tecnica_DFullStack_Java_Webflux_Angular_Yohan_Valentin_Sanchez_Hernandez.pdf`.
> Esfuerzo estimado: **2 horas**. El alcance obligatorio debe mantenerse acotado a ese esfuerzo.

## 1. Contexto

- **BancoIAS** es una entidad financiera con clientes personas y empresas.
- El **core de recaudos PSE** registra pagos reportados sobre **órdenes de recaudo** de comercios.
- Una orden admite **abonos parciales** hasta completar su saldo pendiente.
- Los reportes pueden llegar **repetidos** o **casi simultáneamente** por distintos canales; el core debe mantener resultados **trazables** y saldos **consistentes**.
- Es una simulación interna: no se opera con dinero real ni con una pasarela PSE real.

**Objetivo:** solución Full Stack mínima y funcional para registrar y aplicar reportes de pago PSE sobre órdenes de recaudo, conservar el resultado de cada reporte y consultar los pagos procesados, **sin exceder nunca el saldo pendiente** de cada orden.

## 2. Datos semilla

Pueden cargarse por datos semilla, archivo, memoria u otro mecanismo. No se requiere módulo de administración de órdenes.

| Orden de recaudo | Comercio | Estado  | Saldo pendiente (COP) |
|------------------|----------|---------|----------------------:|
| ORD-PSE-1001     | COM-10   | OPEN    | 1.000.000             |
| ORD-PSE-1002     | COM-10   | BLOCKED | 800.000               |
| ORD-PSE-2001     | COM-20   | OPEN    | 2.000.000             |

- Al iniciar la aplicación se asume que **no hay pagos aplicados** sobre estas órdenes.
- Los pagos parciales están permitidos mientras no superen el saldo pendiente.
- La interfaz puede operar inicialmente con el comercio **COM-10**.

### Datos mínimos de un reporte de pago

| Campo               | Ejemplo        | Significado                       |
|---------------------|----------------|-----------------------------------|
| `paymentReference`  | `PSE-REF-001`  | Referencia única del reporte      |
| `collectionOrderId` | `ORD-PSE-1001` | Orden de recaudo asociada         |
| `merchantId`        | `COM-10`       | Comercio relacionado con la orden |
| `amount`            | `600000`       | Valor reportado en COP            |

Los nombres técnicos son ilustrativos (pueden adaptarse conservando su significado). La **fecha y hora de procesamiento la registra el sistema**, no el cliente.

## 3. Requisitos funcionales

| ID   | Requisito | Detalle |
|------|-----------|---------|
| RF01 | **Procesar un reporte de pago PSE** | Registrar un pago reportado para una orden con los datos mínimos, y registrar la fecha/hora de procesamiento. |
| RF02 | **Validar reglas de negocio** | Un pago solo se aplica si: (1) `amount > 0`; (2) la orden existe; (3) la orden pertenece al `merchantId` informado; (4) la orden está habilitada (`OPEN`); (5) `amount` ≤ saldo pendiente. |
| RF03 | **Conservar el resultado** | Todo reporte procesado persiste sus datos principales, si fue **aplicado** o **rechazado**, cuándo se procesó y, cuando corresponda, una razón general del resultado. Un rechazo **no** reduce el saldo. |
| RF04 | **Solicitudes simultáneas** | Reportes casi simultáneos sobre la misma orden no deben permitir que el total aplicado supere el saldo disponible. Estrategia a criterio del candidato. |
| RF05 | **Referencias repetidas (idempotencia)** | Si una `paymentReference` ya procesada se recibe de nuevo: no hay segunda aplicación ni segundo descuento, y se conserva el resultado original. Si llega con **datos diferentes**, se maneja de forma controlada preservando el reporte original. Estrategia a criterio del candidato. |
| RF06 | **Consultar reportes** | El backend permite consultar un reporte por `paymentReference` y listar los reportes procesados recientemente. Sin paginación avanzada. |
| RF07 | **Interfaz Angular** | Permite registrar un reporte, visualizar claramente el resultado y consultar reportes recientes. Debe evidenciar integración real con el backend; no se evalúa diseño gráfico avanzado. |

## 4. Restricciones técnicas y operativas

- **Backend obligatorio:** Java + Spring Boot **WebFlux**.
- **Frontend obligatorio:** **Angular**.
- Los reportes de pago procesados **deben persistirse**.
- Modelo, tecnología, estructura y estrategia de persistencia: a criterio del candidato.
- **Fuera de alcance:** autenticación, administración de órdenes o comercios, integración real con PSE, entidades financieras o servicios externos.
- Demás decisiones tecnológicas: a criterio del candidato.
- Alcance obligatorio acotado al esfuerzo estimado (2 h).

### Pruebas automatizadas

- Incluir pruebas sobre los comportamientos relevantes que demuestren que las reglas funcionan.
- No hay porcentaje de cobertura exigido.

### Registro de decisiones técnicas

Para cada decisión relevante, documentar cuando aplique: contexto/problema, alternativas consideradas, decisión y justificación, trade-offs o riesgos, y cómo se validó. No hay arquitectura predeterminada; las decisiones deben poder defenderse técnicamente.

### Registro de uso de IA

Si se usa IA, documentar brevemente: en qué actividades, qué resultados se aprovecharon, cómo se validaron, qué se corrigió o descartó, y qué consideraciones se aplicaron para no exponer información sensible. Si no se usa en alguna actividad, puede indicarse.

### Opcional — RabbitMQ

- No resta puntuación si no se implementa y **no** forma parte del criterio de completitud.
- Propósito: permitir que otros sistemas reaccionen cuando un reporte es **aplicado** (publicar/procesar un evento de recaudo aplicado).
- Topología, contrato, reintentos, duplicados y manejo de errores quedan abiertos. Si se implementa, documentar las decisiones.

## 5. Entregables

Repositorio Git con, como mínimo:

- [ ] Código fuente del backend
- [ ] Código fuente del frontend
- [ ] Pruebas automatizadas
- [ ] Instrucciones suficientes para ejecutar la solución
- [ ] Registro de decisiones técnicas
- [ ] Registro del uso de IA
- [ ] Historial de commits realizado durante el desarrollo

### Criterio de completitud

El alcance obligatorio está completo cuando la entrega permite verificar **RF01–RF07**, la persistencia requerida, las pruebas automatizadas, las instrucciones de ejecución y la trazabilidad de decisiones técnicas y uso de IA. Toda limitación o alcance pendiente debe quedar **identificado explícitamente**, de modo que el evaluador distinga qué se completó y qué quedó pendiente.

## 6. Puntos abiertos a decidir (interpretación, no parte del enunciado)

- Estrategia de concurrencia para RF04 (bloqueo optimista/pesimista, update condicional atómico, serialización por orden, etc.).
- Estrategia de idempotencia para RF05 (unicidad de `paymentReference`, comparación de payload, respuesta ante conflicto).
- Si un reporte que falla una validación de formato (p. ej. `amount ≤ 0`) se persiste como **rechazado** o se responde solo como error de entrada.
- Si el saldo de la orden se actualiza en la propia orden o se deriva de los pagos aplicados.
- Criterio de "recientes" en RF06 (últimos N, orden por fecha de procesamiento).
- Si una orden con saldo 0 pasa a un estado distinto (p. ej. `PAID`) o se mantiene `OPEN`.
