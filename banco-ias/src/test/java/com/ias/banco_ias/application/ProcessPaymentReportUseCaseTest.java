package com.ias.banco_ias.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.ias.banco_ias.application.ProcessingOutcome.Conflict;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.CollectionOrder;
import com.ias.banco_ias.domain.OrderStatus;
import com.ias.banco_ias.domain.PaymentCommand;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.reactive.TransactionalOperator;

class ProcessPaymentReportUseCaseTest {

	private static final Instant NOW = Instant.parse("2026-10-20T15:30:00.123456Z");

	private static final String REFERENCE = "PSE-REF-001";

	private static final CollectionOrder OPEN_ORDER =
			new CollectionOrder("ORD-PSE-1001", "COM-10", OrderStatus.OPEN, new BigDecimal("1000000"));

	private final PaymentReportRepository repository = mock(PaymentReportRepository.class);

	private ProcessPaymentReportUseCase useCase;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		TransactionalOperator passthrough = mock(TransactionalOperator.class);
		when(passthrough.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.empty());
		when(repository.insertReport(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		useCase = new ProcessPaymentReportUseCase(repository, passthrough, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static PaymentCommand command(String merchantId, String amount) {
		return new PaymentCommand(REFERENCE, "ORD-PSE-1001", merchantId, new BigDecimal(amount));
	}

	private static PaymentReport storedReport(PaymentCommand command) {
		return new PaymentReport(command.paymentReference(), command.collectionOrderId(), command.merchantId(),
				command.amount(), ReportStatus.APPLIED, null, command.payloadHash(), NOW.minusSeconds(60));
	}

	private static PaymentReport processedReport(ProcessingOutcome outcome) {
		assertThat(outcome).isInstanceOf(Processed.class);
		return ((Processed) outcome).report();
	}

	@Test
	void appliesWhenBalanceIsDecreased() {
		PaymentCommand command = command("COM-10", "600000");
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable("ORD-PSE-1001", "COM-10", new BigDecimal("600000")))
			.thenReturn(Mono.just(true));

		StepVerifier.create(useCase.process(command)).assertNext(outcome -> {
			PaymentReport report = processedReport(outcome);
			assertThat(report.status()).isEqualTo(ReportStatus.APPLIED);
			assertThat(report.rejectionReason()).isNull();
			assertThat(report.processedAt()).isEqualTo(NOW);
			assertThat(report.payloadHash()).isEqualTo(command.payloadHash());
		}).verifyComplete();
	}

	@Test
	void rejectsWithInsufficientBalanceWhenConditionalUpdateLosesTheRace() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable(any(), any(), any())).thenReturn(Mono.just(false));

		StepVerifier.create(useCase.process(command("COM-10", "600000"))).assertNext(outcome -> {
			PaymentReport report = processedReport(outcome);
			assertThat(report.status()).isEqualTo(ReportStatus.REJECTED);
			assertThat(report.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_BALANCE);
		}).verifyComplete();
	}

	@Test
	void rejectedByRulesNeverTouchesBalance() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));

		StepVerifier.create(useCase.process(command("COM-20", "600000")))
			.assertNext(outcome -> assertThat(processedReport(outcome).rejectionReason())
				.isEqualTo(RejectionReason.MERCHANT_MISMATCH))
			.verifyComplete();
		verify(repository, never()).decreaseBalanceIfAvailable(any(), any(), any());
	}

	@Test
	void missingOrderIsPersistedAsOrderNotFound() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.empty());

		StepVerifier.create(useCase.process(command("COM-10", "600000")))
			.assertNext(outcome -> assertThat(processedReport(outcome).rejectionReason())
				.isEqualTo(RejectionReason.ORDER_NOT_FOUND))
			.verifyComplete();
		verify(repository).insertReport(any(PaymentReport.class));
		verify(repository, never()).decreaseBalanceIfAvailable(any(), any(), any());
	}

	@Test
	void fastPathReplaysOriginalWithSamePayload() {
		PaymentCommand command = command("COM-10", "600000");
		PaymentReport original = storedReport(command);
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.just(original));

		StepVerifier.create(useCase.process(command)).expectNext(new Replayed(original)).verifyComplete();
		verify(repository, never()).findOrder(any());
		verify(repository, never()).decreaseBalanceIfAvailable(any(), any(), any());
		verify(repository, never()).insertReport(any());
	}

	@Test
	void fastPathReportsConflictWithDifferentPayload() {
		PaymentReport original = storedReport(command("COM-10", "600000"));
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.just(original));

		StepVerifier.create(useCase.process(command("COM-10", "500000")))
			.expectNext(new Conflict(original))
			.verifyComplete();
		verify(repository, never()).insertReport(any());
	}

	@Test
	void duplicateKeyRereadsAndReplaysOriginalWithSamePayload() {
		PaymentCommand command = command("COM-10", "600000");
		PaymentReport original = storedReport(command);
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.empty(), Mono.just(original));
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable(any(), any(), any())).thenReturn(Mono.just(true));
		doReturn(Mono.error(new DuplicateKeyException("duplicate"))).when(repository).insertReport(any());

		StepVerifier.create(useCase.process(command)).expectNext(new Replayed(original)).verifyComplete();
		verify(repository, times(2)).findByReference(REFERENCE);
	}

	@Test
	void duplicateKeyRereadsAndReportsConflictWithDifferentPayload() {
		PaymentReport original = storedReport(command("COM-10", "600000"));
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.empty(), Mono.just(original));
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable(any(), any(), any())).thenReturn(Mono.just(true));
		doReturn(Mono.error(new DuplicateKeyException("duplicate"))).when(repository).insertReport(any());

		StepVerifier.create(useCase.process(command("COM-10", "500000")))
			.expectNext(new Conflict(original))
			.verifyComplete();
		verify(repository, times(2)).findByReference(REFERENCE);
	}

	@Test
	void otherIntegrityViolationsPropagateWithoutReread() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable(any(), any(), any())).thenReturn(Mono.just(true));
		doReturn(Mono.error(new DataIntegrityViolationException("check violation"))).when(repository).insertReport(any());

		StepVerifier.create(useCase.process(command("COM-10", "600000")))
			.expectErrorSatisfies(error -> assertThat(error).isExactlyInstanceOf(DataIntegrityViolationException.class))
			.verify();
		verify(repository, times(1)).findByReference(REFERENCE);
	}

}
