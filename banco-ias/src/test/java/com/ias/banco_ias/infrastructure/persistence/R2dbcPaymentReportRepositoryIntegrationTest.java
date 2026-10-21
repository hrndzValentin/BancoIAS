package com.ias.banco_ias.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import com.ias.banco_ias.TestcontainersConfiguration;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.ReportStatus;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class R2dbcPaymentReportRepositoryIntegrationTest {

	@Autowired
	private R2dbcPaymentReportRepository repository;

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
	void doesNotDecreaseWhenAmountExceedsBalance() {
		StepVerifier.create(repository.decreaseBalanceIfAvailable("ORD-PSE-1001", "COM-10", new BigDecimal("1000001")))
			.expectNext(false)
			.verifyComplete();
		StepVerifier.create(repository.findOrder("ORD-PSE-1001"))
			.assertNext(order -> assertThat(order.pendingBalance()).isEqualByComparingTo("1000000"))
			.verifyComplete();
	}

	@Test
	void onlyOneOfTwoConcurrentDecreasesSucceeds() {
		Flux<Boolean> concurrent = Flux.range(1, 2)
			.flatMap(i -> repository.decreaseBalanceIfAvailable("ORD-PSE-1001", "COM-10", new BigDecimal("600000")), 2);

		StepVerifier.create(concurrent.collectList())
			.assertNext(results -> assertThat(results).containsExactlyInAnyOrder(true, false))
			.verifyComplete();
		StepVerifier.create(repository.findOrder("ORD-PSE-1001"))
			.assertNext(order -> assertThat(order.pendingBalance()).isEqualByComparingTo("400000"))
			.verifyComplete();
	}

	@Test
	void findOrderIsEmptyForUnknownOrder() {
		StepVerifier.create(repository.findOrder("ORD-PSE-9999")).verifyComplete();
	}

	private static PaymentReport report(String reference) {
		return new PaymentReport(reference, "ORD-PSE-1001", "COM-10", new BigDecimal("600000.00"),
				ReportStatus.APPLIED, null, "a".repeat(64), Instant.parse("2026-10-20T15:30:00.123456Z"));
	}

	@Test
	void findByReferenceReturnsStoredReport() {
		PaymentReport report = report("REF-FIND");

		StepVerifier.create(repository.insertReport(report).then(repository.findByReference("REF-FIND")))
			.expectNext(report)
			.verifyComplete();
	}

	@Test
	void findByReferenceIsEmptyForUnknownReference() {
		StepVerifier.create(repository.findByReference("REF-UNKNOWN")).verifyComplete();
	}

	@Test
	void uniqueViolationIsTranslatedToDuplicateKeyException() {
		StepVerifier.create(repository.insertReport(report("REF-DUP")).then(repository.insertReport(report("REF-DUP"))))
			.expectErrorSatisfies(error -> assertThat(error).isExactlyInstanceOf(DuplicateKeyException.class))
			.verify();
	}

	@Test
	void checkViolationIsNotTranslatedToDuplicateKeyException() {
		StepVerifier
			.create(databaseClient.sql("UPDATE collection_order SET pending_balance = -1 WHERE id = 'ORD-PSE-1001'")
				.then())
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DataIntegrityViolationException.class)
				.isNotInstanceOf(DuplicateKeyException.class))
			.verify();
	}

}
