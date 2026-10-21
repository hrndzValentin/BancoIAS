package com.ias.banco_ias.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.ias.banco_ias.RabbitTestcontainersConfiguration;
import com.ias.banco_ias.TestcontainersConfiguration;
import com.ias.banco_ias.application.ProcessPaymentReportUseCase;
import com.ias.banco_ias.application.ProcessingOutcome;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.PaymentCommand;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.ReportStatus;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.events.enabled=true")
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, RabbitTestcontainersConfiguration.class })
class PaymentEventPublishingIntegrationTest {

	private static final String TEST_QUEUE = "test.payment-applied";

	private static final long RECEIVE_TIMEOUT_MS = 5_000;

	private static final long NO_MESSAGE_TIMEOUT_MS = 1_000;

	@Autowired
	private ProcessPaymentReportUseCase useCase;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private TopicExchange paymentsEventsExchange;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private DatabaseClient databaseClient;

	@Autowired
	private ConnectionFactory connectionFactory;

	@BeforeEach
	void resetDatabaseAndQueue() {
		Mono<Void> reset = databaseClient.sql("DELETE FROM payment_report")
			.then()
			.then(databaseClient.sql("DELETE FROM collection_order").then())
			.then(new ResourceDatabasePopulator(new ClassPathResource("data.sql")).populate(connectionFactory));
		StepVerifier.create(reset).verifyComplete();

		Queue queue = new Queue(TEST_QUEUE, false);
		amqpAdmin.declareExchange(paymentsEventsExchange);
		amqpAdmin.declareQueue(queue);
		Binding binding = BindingBuilder.bind(queue).to(paymentsEventsExchange).with("payment.applied");
		amqpAdmin.declareBinding(binding);
		amqpAdmin.purgeQueue(TEST_QUEUE, false);
	}

	private PaymentReport processExpecting(PaymentCommand command, Class<? extends ProcessingOutcome> outcomeType) {
		ProcessingOutcome outcome = useCase.process(command).block();
		assertThat(outcome).isInstanceOf(outcomeType);
		return switch (outcome) {
			case Processed(PaymentReport report) -> report;
			case Replayed(PaymentReport original) -> original;
			default -> throw new AssertionError("Unexpected outcome " + outcome);
		};
	}

	private static PaymentCommand command(String reference, String orderId, String amount) {
		return new PaymentCommand(reference, orderId, "COM-10", new BigDecimal(amount));
	}

	@Test
	void appliedPaymentPublishesExactlyOneEventWithTheContract() {
		PaymentReport report = processExpecting(command("REF-EVT-1", "ORD-PSE-1001", "600000"), Processed.class);
		assertThat(report.status()).isEqualTo(ReportStatus.APPLIED);

		Message message = rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS);
		assertThat(message).isNotNull();
		assertThat(rabbitTemplate.receive(TEST_QUEUE, NO_MESSAGE_TIMEOUT_MS)).isNull();

		String expectedEventId = UUID
			.nameUUIDFromBytes("PaymentApplied:REF-EVT-1".getBytes(StandardCharsets.UTF_8))
			.toString();
		assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
		assertThat(message.getMessageProperties().getMessageId()).isEqualTo(expectedEventId);
		assertThat(message.getMessageProperties().getType()).isEqualTo("PaymentApplied");
		assertThat(message.getMessageProperties().getReceivedExchange()).isEqualTo("payments.events");
		assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.applied");

		JsonNode body = jsonMapper.readTree(message.getBody());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "paymentReference",
				"collectionOrderId", "merchantId", "amount", "occurredAt");
		assertThat(body.get("eventId").asString()).isEqualTo(expectedEventId);
		assertThat(body.get("eventType").asString()).isEqualTo("PaymentApplied");
		assertThat(body.get("paymentReference").asString()).isEqualTo("REF-EVT-1");
		assertThat(body.get("collectionOrderId").asString()).isEqualTo("ORD-PSE-1001");
		assertThat(body.get("merchantId").asString()).isEqualTo("COM-10");
		assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("600000");
		assertThat(Instant.parse(body.get("occurredAt").asString())).isEqualTo(report.processedAt());
	}

	@Test
	void rejectedPaymentPublishesNothing() {
		PaymentReport report = processExpecting(command("REF-EVT-2", "ORD-PSE-1002", "100000"), Processed.class);
		assertThat(report.status()).isEqualTo(ReportStatus.REJECTED);

		assertThat(rabbitTemplate.receive(TEST_QUEUE, NO_MESSAGE_TIMEOUT_MS)).isNull();
	}

	@Test
	void replayDoesNotPublishAgain() {
		PaymentCommand command = command("REF-EVT-3", "ORD-PSE-1001", "300000");
		processExpecting(command, Processed.class);
		processExpecting(command, Replayed.class);

		assertThat(rabbitTemplate.receive(TEST_QUEUE, RECEIVE_TIMEOUT_MS)).isNotNull();
		assertThat(rabbitTemplate.receive(TEST_QUEUE, NO_MESSAGE_TIMEOUT_MS)).isNull();
	}

}
