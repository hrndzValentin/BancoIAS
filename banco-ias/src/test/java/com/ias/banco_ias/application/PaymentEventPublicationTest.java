package com.ias.banco_ias.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.transaction.reactive.TransactionalOperator;

class PaymentEventPublicationTest {

	private static final Instant NOW = Instant.parse("2026-10-20T15:30:00.123456Z");

	private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

	private static final String REFERENCE = "PSE-REF-001";

	private static final CollectionOrder OPEN_ORDER =
			new CollectionOrder("ORD-PSE-1001", "COM-10", OrderStatus.OPEN, new BigDecimal("1000000"));

	private final PaymentReportRepository repository = mock(PaymentReportRepository.class);

	private final PaymentEventPublisher publisher = mock(PaymentEventPublisher.class);

	private TransactionalOperator passthrough;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		passthrough = mock(TransactionalOperator.class);
		when(passthrough.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.empty());
		when(repository.findOrder("ORD-PSE-1001")).thenReturn(Mono.just(OPEN_ORDER));
		when(repository.decreaseBalanceIfAvailable(any(), any(), any())).thenReturn(Mono.just(true));
		when(repository.insertReport(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
		when(publisher.publish(any())).thenReturn(Mono.empty());
	}

	private ProcessPaymentReportUseCase useCase(PaymentEventPublisher eventPublisher) {
		return new ProcessPaymentReportUseCase(repository, passthrough, Clock.fixed(NOW, ZoneOffset.UTC),
				eventPublisher, PUBLISH_TIMEOUT);
	}

	private static PaymentCommand command(String merchantId, String amount) {
		return new PaymentCommand(REFERENCE, "ORD-PSE-1001", merchantId, new BigDecimal(amount));
	}

	private static void assertApplied(ProcessingOutcome outcome) {
		assertThat(outcome).isInstanceOf(Processed.class);
		assertThat(((Processed) outcome).report().status()).isEqualTo(ReportStatus.APPLIED);
	}

	@Test
	void appliedPaymentPublishesOneEventAfterProcessing() {
		StepVerifier.create(useCase(publisher).process(command("COM-10", "600000")))
			.assertNext(PaymentEventPublicationTest::assertApplied)
			.verifyComplete();

		ArgumentCaptor<PaymentAppliedEvent> event = ArgumentCaptor.forClass(PaymentAppliedEvent.class);
		verify(publisher).publish(event.capture());
		assertThat(event.getValue().eventType()).isEqualTo("PaymentApplied");
		assertThat(event.getValue().paymentReference()).isEqualTo(REFERENCE);
		assertThat(event.getValue().collectionOrderId()).isEqualTo("ORD-PSE-1001");
		assertThat(event.getValue().merchantId()).isEqualTo("COM-10");
		assertThat(event.getValue().amount()).isEqualByComparingTo("600000");
		assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
	}

	@Test
	void rejectedPaymentDoesNotPublish() {
		StepVerifier.create(useCase(publisher).process(command("COM-20", "600000")))
			.assertNext(outcome -> assertThat(((Processed) outcome).report().rejectionReason())
				.isEqualTo(RejectionReason.MERCHANT_MISMATCH))
			.verifyComplete();
		verify(publisher, never()).publish(any());
	}

	@Test
	void replayedAndConflictingReportsDoNotPublish() {
		PaymentCommand command = command("COM-10", "600000");
		PaymentReport original = new PaymentReport(REFERENCE, "ORD-PSE-1001", "COM-10", command.amount(),
				ReportStatus.APPLIED, null, command.payloadHash(), NOW);
		when(repository.findByReference(REFERENCE)).thenReturn(Mono.just(original));

		StepVerifier.create(useCase(publisher).process(command)).expectNext(new Replayed(original)).verifyComplete();
		StepVerifier.create(useCase(publisher).process(command("COM-10", "500000")))
			.expectNext(new Conflict(original))
			.verifyComplete();
		verify(publisher, never()).publish(any());
	}

	@Test
	void publicationFailureKeepsThePaymentApplied() {
		when(publisher.publish(any())).thenReturn(Mono.error(new IllegalStateException("broker unavailable")));

		StepVerifier.create(useCase(publisher).process(command("COM-10", "600000")))
			.assertNext(PaymentEventPublicationTest::assertApplied)
			.verifyComplete();
	}

	@Test
	void slowPublicationTimesOutAndKeepsThePaymentApplied() {
		// Test publisher that would take 10s; the use case must give up after its 2s timeout.
		PaymentEventPublisher slowPublisher = event -> Mono.delay(Duration.ofSeconds(10)).then();

		StepVerifier.withVirtualTime(() -> useCase(slowPublisher).process(command("COM-10", "600000")))
			.expectSubscription()
			.expectNoEvent(PUBLISH_TIMEOUT.minusMillis(1))
			.thenAwait(Duration.ofMillis(1))
			.assertNext(PaymentEventPublicationTest::assertApplied)
			.verifyComplete();
	}

}
