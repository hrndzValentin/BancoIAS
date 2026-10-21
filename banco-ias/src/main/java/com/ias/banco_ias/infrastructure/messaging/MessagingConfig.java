package com.ias.banco_ias.infrastructure.messaging;

import com.ias.banco_ias.application.NoOpPaymentEventPublisher;
import com.ias.banco_ias.application.PaymentEventPublisher;
import com.ias.banco_ias.infrastructure.config.EventsProperties;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Topology owned by the publisher: only the exchange. Queues belong to the consumers.
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventsProperties.class)
public class MessagingConfig {

	@Bean
	@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true", matchIfMissing = true)
	TopicExchange paymentsEventsExchange(EventsProperties properties) {
		return new TopicExchange(properties.exchange(), true, false);
	}

	@Bean
	@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true", matchIfMissing = true)
	PaymentEventPublisher rabbitPaymentEventPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper,
			EventsProperties properties) {
		return new RabbitPaymentEventPublisher(rabbitTemplate, jsonMapper, properties.exchange(),
				properties.routingKey());
	}

	@Bean
	@ConditionalOnProperty(name = "app.events.enabled", havingValue = "false")
	PaymentEventPublisher noOpPaymentEventPublisher() {
		return new NoOpPaymentEventPublisher();
	}

}
