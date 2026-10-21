package com.ias.banco_ias.infrastructure.messaging;

import com.ias.banco_ias.application.PaymentAppliedEvent;
import com.ias.banco_ias.application.PaymentEventPublisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitPaymentEventPublisher implements PaymentEventPublisher {

	private final RabbitTemplate rabbitTemplate;

	private final JsonMapper jsonMapper;

	private final String exchange;

	private final String routingKey;

	public RabbitPaymentEventPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper, String exchange,
			String routingKey) {
		this.rabbitTemplate = rabbitTemplate;
		this.jsonMapper = jsonMapper;
		this.exchange = exchange;
		this.routingKey = routingKey;
	}

	@Override
	public Mono<Void> publish(PaymentAppliedEvent event) {
		// RabbitTemplate is blocking (connection and channel write), so it runs on boundedElastic,
		// never on a Netty event loop. The publisher confirm then arrives asynchronously.
		return Mono.fromCallable(() -> {
			CorrelationData correlation = new CorrelationData(event.eventId().toString());
			rabbitTemplate.send(exchange, routingKey, toMessage(event), correlation);
			return correlation.getFuture();
		})
			.subscribeOn(Schedulers.boundedElastic())
			.flatMap(Mono::fromFuture)
			.flatMap(confirm -> confirm.ack() ? Mono.<Void>empty()
					: Mono.error(new AmqpException("Broker rejected event " + event.eventId() + ": " + confirm.reason())));
	}

	private Message toMessage(PaymentAppliedEvent event) {
		return MessageBuilder.withBody(jsonMapper.writeValueAsBytes(event))
			.setContentType(MessageProperties.CONTENT_TYPE_JSON)
			.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
			.setMessageId(event.eventId().toString())
			.setType(event.eventType())
			.build();
	}

}
