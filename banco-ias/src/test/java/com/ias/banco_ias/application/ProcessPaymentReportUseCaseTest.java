package com.ias.banco_ias.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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

import org.springframework.transaction.reactive.TransactionalOperator;

class ProcessPaymentReportUseCaseTest {

	private static final Instant NOW = Instant.parse("2026-10-20T15:30:00.123456Z");

	private static final CollectionOrder OPEN_ORDER =
			new CollectionOrder("ORD-PSE-1001", "COM-10", OrderStatus.OPEN, new BigDecimal("1000000"));

	private final PaymentReportRepository repository = mock(PaymentReportRepository.class);

	private ProcessPaymentReportUseCase useCase;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		TransactionalOperator passthrough = mock(TransactionalOperator.class);
		when(passthrough.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(repository.insertReport(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		useCase = new ProcessPaymentReportUseCase(repository, passthrough, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static PaymentCommand command(String merchantId, String amount) {
		return new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", merchantId, new BigDecimal(amount));
	}

	@Test
	void appliesWhenBalanceIsDecreased() {
		PaymentCommand command = command("COM-10", "600000");
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable("ORD-PSE-1001", "COM-10", new BigDecimal("600000")))
			.thenReturn(Mono.just(true));

		StepVerifier.create(useCase.process(command)).assertNext(report -> {
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

		StepVerifier.create(useCase.process(command("COM-10", "600000"))).assertNext(report -> {
			assertThat(report.status()).isEqualTo(ReportStatus.REJECTED);
			assertThat(report.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_BALANCE);
		}).verifyComplete();
	}

	@Test
	void rejectedByRulesNeverTouchesBalance() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));

		StepVerifier.create(useCase.process(command("COM-20", "600000")))
			.assertNext(report -> assertThat(report.rejectionReason()).isEqualTo(RejectionReason.MERCHANT_MISMATCH))
			.verifyComplete();
		verify(repository, never()).decreaseBalanceIfAvailable(any(), any(), any());
	}

	@Test
	void missingOrderIsPersistedAsOrderNotFound() {
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.empty());

		StepVerifier.create(useCase.process(command("COM-10", "600000")))
			.assertNext(report -> assertThat(report.rejectionReason()).isEqualTo(RejectionReason.ORDER_NOT_FOUND))
			.verifyComplete();
		verify(repository).insertReport(any(PaymentReport.class));
		verify(repository, never()).decreaseBalanceIfAvailable(any(), any(), any());
	}

}
