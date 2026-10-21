package com.ias.banco_ias.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.ias.banco_ias.TestcontainersConfiguration;
import com.ias.banco_ias.application.ProcessingOutcome.Conflict;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.PaymentCommand;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProcessPaymentReportIntegrationTest {

	@Autowired
	private ProcessPaymentReportUseCase useCase;

	@Autowired
	private PaymentReportRepository repository;

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

	private static PaymentCommand command(String reference, String orderId, String merchantId, String amount) {
		return new PaymentCommand(reference, orderId, merchantId, new BigDecimal(amount));
	}

	private Mono<PaymentReport> processNew(PaymentCommand command) {
		return useCase.process(command).map(outcome -> {
			assertThat(outcome).isInstanceOf(Processed.class);
			return ((Processed) outcome).report();
		});
	}

	private void assertBalance(String orderId, String expected) {
		StepVerifier
			.create(databaseClient.sql("SELECT pending_balance FROM collection_order WHERE id = :id")
				.bind("id", orderId)
				.map(row -> row.get("pending_balance", BigDecimal.class))
				.one())
			.assertNext(balance -> assertThat(balance).isEqualByComparingTo(expected))
			.verifyComplete();
	}

	private void assertStoredReport(String reference, ReportStatus status, RejectionReason reason) {
		StepVerifier
			.create(databaseClient
				.sql("SELECT status, rejection_reason FROM payment_report WHERE payment_reference = :reference")
				.bind("reference", reference)
				.map(row -> row.get("status", String.class) + "/" + row.get("rejection_reason", String.class))
				.all()
				.collectList())
			.assertNext(rows -> assertThat(rows).containsExactly(status + "/" + reason))
			.verifyComplete();
	}

	// Conservation invariant: what was applied plus what is still pending equals the initial balance.
	private void assertConservation(String orderId, String initialBalance) {
		StepVerifier.create(databaseClient.sql("""
				SELECT o.pending_balance
				     + COALESCE((SELECT SUM(r.amount) FROM payment_report r
				                  WHERE r.collection_order_id = o.id AND r.status = 'APPLIED'), 0) AS total
				  FROM collection_order o
				 WHERE o.id = :id
				""").bind("id", orderId).map(row -> row.get("total", BigDecimal.class)).one())
			.assertNext(total -> assertThat(total).isEqualByComparingTo(initialBalance))
			.verifyComplete();
	}

	private void assertReportCount(String orderId, long expected) {
		StepVerifier
			.create(databaseClient.sql("SELECT COUNT(*) AS total FROM payment_report WHERE collection_order_id = :id")
				.bind("id", orderId)
				.map(row -> row.get("total", Long.class))
				.one())
			.expectNext(expected)
			.verifyComplete();
	}

	@Test
	void appliesPaymentAndDecreasesBalance() {
		StepVerifier.create(processNew(command("REF-1", "ORD-PSE-1001", "COM-10", "600000")))
			.assertNext(report -> assertThat(report.status()).isEqualTo(ReportStatus.APPLIED))
			.verifyComplete();

		assertStoredReport("REF-1", ReportStatus.APPLIED, null);
		assertBalance("ORD-PSE-1001", "400000");
	}

	@Test
	void appliesPaymentEqualToPendingBalance() {
		StepVerifier.create(processNew(command("REF-1", "ORD-PSE-1001", "COM-10", "1000000")))
			.assertNext(report -> assertThat(report.status()).isEqualTo(ReportStatus.APPLIED))
			.verifyComplete();

		assertBalance("ORD-PSE-1001", "0");
	}

	@Test
	void appliesPartialPaymentsUntilBalanceIsExhausted() {
		StepVerifier
			.create(processNew(command("REF-1", "ORD-PSE-1001", "COM-10", "600000"))
				.then(processNew(command("REF-2", "ORD-PSE-1001", "COM-10", "400000")))
				.then(processNew(command("REF-3", "ORD-PSE-1001", "COM-10", "1"))))
			.assertNext(report -> assertThat(report.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_BALANCE))
			.verifyComplete();

		assertStoredReport("REF-1", ReportStatus.APPLIED, null);
		assertStoredReport("REF-2", ReportStatus.APPLIED, null);
		assertStoredReport("REF-3", ReportStatus.REJECTED, RejectionReason.INSUFFICIENT_BALANCE);
		assertBalance("ORD-PSE-1001", "0");
	}

	@Test
	void persistsEachRejectionWithoutTouchingBalances() {
		Flux<PaymentReport> rejected = Flux.concat(
				processNew(command("REF-AMOUNT", "ORD-PSE-1001", "COM-10", "0")),
				processNew(command("REF-MISSING", "ORD-PSE-9999", "COM-10", "100000")),
				processNew(command("REF-MERCHANT", "ORD-PSE-1001", "COM-20", "100000")),
				processNew(command("REF-BLOCKED", "ORD-PSE-1002", "COM-10", "100000")),
				processNew(command("REF-BALANCE", "ORD-PSE-2001", "COM-20", "2000000.01")));
		StepVerifier.create(rejected).expectNextCount(5).verifyComplete();

		assertStoredReport("REF-AMOUNT", ReportStatus.REJECTED, RejectionReason.INVALID_AMOUNT);
		assertStoredReport("REF-MISSING", ReportStatus.REJECTED, RejectionReason.ORDER_NOT_FOUND);
		assertStoredReport("REF-MERCHANT", ReportStatus.REJECTED, RejectionReason.MERCHANT_MISMATCH);
		assertStoredReport("REF-BLOCKED", ReportStatus.REJECTED, RejectionReason.ORDER_NOT_ENABLED);
		assertStoredReport("REF-BALANCE", ReportStatus.REJECTED, RejectionReason.INSUFFICIENT_BALANCE);
		assertBalance("ORD-PSE-1001", "1000000");
		assertBalance("ORD-PSE-1002", "800000");
		assertBalance("ORD-PSE-2001", "2000000");
	}

	@RepeatedTest(5)
	void concurrentReportsNeverExceedPendingBalance() {
		int reports = 10;
		Flux<PaymentReport> concurrent = Flux.range(1, reports)
			.flatMap(i -> processNew(command("REF-C-" + i, "ORD-PSE-1001", "COM-10", "300000")), reports);

		StepVerifier.create(concurrent.collectList()).assertNext(results -> {
			Map<ReportStatus, List<PaymentReport>> byStatus =
					results.stream().collect(Collectors.groupingBy(PaymentReport::status));
			assertThat(byStatus.get(ReportStatus.APPLIED)).hasSize(3);
			assertThat(byStatus.get(ReportStatus.REJECTED)).hasSize(7)
				.allSatisfy(report -> assertThat(report.rejectionReason())
					.isEqualTo(RejectionReason.INSUFFICIENT_BALANCE));
		}).verifyComplete();

		assertBalance("ORD-PSE-1001", "100000");
		assertConservation("ORD-PSE-1001", "1000000");
	}

	@RepeatedTest(5)
	void concurrentReportsOnBlockedOrderAreAllRejected() {
		int reports = 5;
		Flux<PaymentReport> concurrent = Flux.range(1, reports)
			.flatMap(i -> processNew(command("REF-B-" + i, "ORD-PSE-1002", "COM-10", "100000")), reports);

		StepVerifier.create(concurrent.collectList())
			.assertNext(results -> assertThat(results).hasSize(reports).allSatisfy(report -> {
				assertThat(report.status()).isEqualTo(ReportStatus.REJECTED);
				assertThat(report.rejectionReason()).isEqualTo(RejectionReason.ORDER_NOT_ENABLED);
			}))
			.verifyComplete();

		assertReportCount("ORD-PSE-1002", reports);
		assertBalance("ORD-PSE-1002", "800000");
		assertConservation("ORD-PSE-1002", "800000");
	}

	@RepeatedTest(5)
	void concurrentDistinctAndRepeatedReferencesApplyEachReferenceOnce() {
		int references = 3;
		int copies = 3;
		Flux<ProcessingOutcome> mixed = Flux.range(0, references * copies)
			.flatMap(i -> useCase.process(command("REF-M-" + (i % references), "ORD-PSE-1001", "COM-10", "300000")),
					references * copies);

		StepVerifier.create(mixed.collectList()).assertNext(outcomes -> {
			assertThat(outcomes).filteredOn(Processed.class::isInstance)
				.hasSize(references)
				.allSatisfy(outcome -> assertThat(((Processed) outcome).report().status())
					.isEqualTo(ReportStatus.APPLIED));
			assertThat(outcomes).filteredOn(Replayed.class::isInstance).hasSize(references * (copies - 1));
		}).verifyComplete();

		assertReportCount("ORD-PSE-1001", references);
		for (int i = 0; i < references; i++) {
			assertStoredReport("REF-M-" + i, ReportStatus.APPLIED, null);
		}
		assertBalance("ORD-PSE-1001", "100000");
		assertConservation("ORD-PSE-1001", "1000000");
	}

	@RepeatedTest(5)
	void concurrentReportsOnTwoOrdersDoNotInterfere() {
		int reportsPerOrder = 10;
		// Even i → ORD-PSE-1001 (1.000.000, 300.000 each); odd i → ORD-PSE-2001 (2.000.000, 500.000 each).
		Flux<PaymentReport> concurrent = Flux.range(0, reportsPerOrder * 2)
			.flatMap(i -> processNew((i % 2 == 0) ? command("REF-A-" + i, "ORD-PSE-1001", "COM-10", "300000")
					: command("REF-B-" + i, "ORD-PSE-2001", "COM-20", "500000")), reportsPerOrder * 2);

		StepVerifier.create(concurrent.collectList()).assertNext(results -> {
			assertThat(results).filteredOn(report -> report.collectionOrderId().equals("ORD-PSE-1001"))
				.filteredOn(report -> report.status() == ReportStatus.APPLIED)
				.hasSize(3);
			assertThat(results).filteredOn(report -> report.collectionOrderId().equals("ORD-PSE-2001"))
				.filteredOn(report -> report.status() == ReportStatus.APPLIED)
				.hasSize(4);
			assertThat(results).filteredOn(report -> report.status() == ReportStatus.REJECTED)
				.hasSize(13)
				.allSatisfy(report -> assertThat(report.rejectionReason())
					.isEqualTo(RejectionReason.INSUFFICIENT_BALANCE));
		}).verifyComplete();

		assertBalance("ORD-PSE-1001", "100000");
		assertBalance("ORD-PSE-2001", "0");
		assertConservation("ORD-PSE-1001", "1000000");
		assertConservation("ORD-PSE-2001", "2000000");
		assertBalance("ORD-PSE-1002", "800000");
	}

	@Test
	void repeatedReferenceWithSamePayloadReplaysOriginal() {
		PaymentCommand command = command("REF-R", "ORD-PSE-1001", "COM-10", "600000");
		PaymentReport original = processNew(command).block();

		StepVerifier.create(useCase.process(command)).expectNext(new Replayed(original)).verifyComplete();

		assertStoredReport("REF-R", ReportStatus.APPLIED, null);
		assertBalance("ORD-PSE-1001", "400000");
	}

	@Test
	void repeatedReferenceOfRejectedReportReplaysRejection() {
		PaymentCommand command = command("REF-RJ", "ORD-PSE-1002", "COM-10", "100000");
		PaymentReport original = processNew(command).block();

		StepVerifier.create(useCase.process(command)).assertNext(outcome -> {
			assertThat(outcome).isEqualTo(new Replayed(original));
			assertThat(original.rejectionReason()).isEqualTo(RejectionReason.ORDER_NOT_ENABLED);
		}).verifyComplete();

		assertStoredReport("REF-RJ", ReportStatus.REJECTED, RejectionReason.ORDER_NOT_ENABLED);
		assertBalance("ORD-PSE-1002", "800000");
	}

	@Test
	void repeatedReferenceWithDifferentAmountIsConflictAndKeepsOriginal() {
		PaymentReport original = processNew(command("REF-X", "ORD-PSE-1001", "COM-10", "600000")).block();

		StepVerifier.create(useCase.process(command("REF-X", "ORD-PSE-1001", "COM-10", "500000")))
			.expectNext(new Conflict(original))
			.verifyComplete();

		StepVerifier.create(repository.findByReference("REF-X")).expectNext(original).verifyComplete();
		assertBalance("ORD-PSE-1001", "400000");
	}

	@RepeatedTest(5)
	void parallelRequestsWithSameReferenceApplyOnce() {
		int requests = 5;
		PaymentCommand command = command("REF-P", "ORD-PSE-1001", "COM-10", "300000");
		Flux<ProcessingOutcome> parallel = Flux.range(1, requests).flatMap(i -> useCase.process(command), requests);

		StepVerifier.create(parallel.collectList()).assertNext(outcomes -> {
			assertThat(outcomes).filteredOn(Processed.class::isInstance).hasSize(1);
			assertThat(outcomes).filteredOn(Replayed.class::isInstance).hasSize(requests - 1);
			PaymentReport applied = outcomes.stream()
				.filter(Processed.class::isInstance)
				.map(outcome -> ((Processed) outcome).report())
				.findFirst()
				.orElseThrow();
			assertThat(applied.status()).isEqualTo(ReportStatus.APPLIED);
			assertThat(outcomes).filteredOn(Replayed.class::isInstance)
				.allSatisfy(outcome -> assertThat(((Replayed) outcome).original()).isEqualTo(applied));
		}).verifyComplete();

		assertStoredReport("REF-P", ReportStatus.APPLIED, null);
		assertBalance("ORD-PSE-1001", "700000");
		assertConservation("ORD-PSE-1001", "1000000");
	}

}
