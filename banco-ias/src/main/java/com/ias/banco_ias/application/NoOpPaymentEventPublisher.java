package com.ias.banco_ias.application;

import reactor.core.publisher.Mono;

/** Used when events are disabled ({@code app.events.enabled=false}). */
public class NoOpPaymentEventPublisher implements PaymentEventPublisher {

	@Override
	public Mono<Void> publish(PaymentAppliedEvent event) {
		return Mono.empty();
	}

}
