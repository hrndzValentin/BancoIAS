package com.ias.banco_ias.application;

import reactor.core.publisher.Mono;

public interface PaymentEventPublisher {

	/** Completes when the broker confirmed the event; errors if it could not be published. */
	Mono<Void> publish(PaymentAppliedEvent event);

}
