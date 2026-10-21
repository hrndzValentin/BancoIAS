package com.ias.banco_ias.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.ias.banco_ias.TestcontainersConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

// Events enabled but nothing listens on port 1: the broker is unreachable.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.events.enabled=true", "spring.rabbitmq.port=1" })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PaymentEventBrokerDownIntegrationTest {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private DatabaseClient databaseClient;

	@Autowired
	private ConnectionFactory connectionFactory;

	@BeforeEach
	void resetDatabase() {
		Mono<Void> reset = databaseClient.sql("DELETE FROM payment_report")
			.then()
			.then(databaseClient.sql("DELETE FROM collection_order").then())
			.then(new ResourceDatabasePopulator(new ClassPathResource("data.sql")).populate(connectionFactory));
		StepVerifier.create(reset).verifyComplete();
	}

	@Test
	void paymentIsAppliedAndResponseIsSuccessfulWhenBrokerIsDown(CapturedOutput output) {
		webTestClient.post()
			.uri("/api/payment-reports")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
					{"paymentReference":"REF-DOWN-1","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10","amount":600000}
					""")
			.exchange()
			.expectStatus()
			.isCreated()
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPLIED");

		StepVerifier
			.create(databaseClient.sql("SELECT pending_balance FROM collection_order WHERE id = 'ORD-PSE-1001'")
				.map(row -> row.get("pending_balance", BigDecimal.class))
				.one())
			.assertNext(balance -> assertThat(balance).isEqualByComparingTo("400000"))
			.verifyComplete();
		assertThat(output).contains("PaymentApplied event for REF-DOWN-1 was not published");
	}

}
