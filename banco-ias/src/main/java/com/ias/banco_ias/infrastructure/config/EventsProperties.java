package com.ias.banco_ias.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.events.*}. {@code publishTimeout} is read by the use case, which owns the
 * "publication never fails the payment" policy.
 */
@ConfigurationProperties("app.events")
public record EventsProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("payments.events") String exchange,
		@DefaultValue("payment.applied") String routingKey,
		@DefaultValue("2s") Duration publishTimeout) {
}
