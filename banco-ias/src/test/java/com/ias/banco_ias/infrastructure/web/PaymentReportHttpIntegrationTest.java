package com.ias.banco_ias.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.ias.banco_ias.TestcontainersConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentReportHttpIntegrationTest {

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

	private WebTestClient.ResponseSpec post(String amount) {
		return webTestClient.post()
			.uri("/api/payment-reports")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
					{"paymentReference":"PSE-REF-HTTP","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10","amount":%s}
					""".formatted(amount))
			.exchange();
	}

	@Test
	void appliesThenReplaysThenRejectsConflictingReport() {
		post("600000").expectStatus()
			.isCreated()
			.expectHeader()
			.location("/api/payment-reports/PSE-REF-HTTP")
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPLIED");

		post("600000").expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("Idempotent-Replayed", "true")
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPLIED")
			.jsonPath("$.amount").isEqualTo(600000.00);

		post("500000").expectStatus()
			.isEqualTo(409)
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.paymentReference").isEqualTo("PSE-REF-HTTP");

		webTestClient.get()
			.uri("/api/payment-reports/PSE-REF-HTTP")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.amount").isEqualTo(600000.00);

		webTestClient.get()
			.uri("/api/payment-reports")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1);

		StepVerifier
			.create(databaseClient.sql("SELECT pending_balance FROM collection_order WHERE id = 'ORD-PSE-1001'")
				.map(row -> row.get("pending_balance", BigDecimal.class))
				.one())
			.assertNext(balance -> assertThat(balance).isEqualByComparingTo("400000"))
			.verifyComplete();
	}

}
