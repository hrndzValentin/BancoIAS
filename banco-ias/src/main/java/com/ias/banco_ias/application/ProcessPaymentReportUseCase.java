package com.ias.banco_ias.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.ias.banco_ias.application.ProcessingOutcome.Conflict;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.PaymentCommand;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.PaymentRules;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;
import reactor.core.publisher.Mono;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

@Service
public class ProcessPaymentReportUseCase {

	private final PaymentReportRepository repository;

	private final TransactionalOperator transactionalOperator;

	private final Clock clock;

	public ProcessPaymentReportUseCase(PaymentReportRepository repository, TransactionalOperator transactionalOperator,
			Clock clock) {
		this.repository = repository;
		this.transactionalOperator = transactionalOperator;
		this.clock = clock;
	}

	public Mono<ProcessingOutcome> process(PaymentCommand command) {
		return Mono.defer(() -> {
			String payloadHash = command.payloadHash();
			// Fast path, outside any transaction: the reference was already processed.
			return repository.findByReference(command.paymentReference())
				.map(original -> compare(original, payloadHash))
				.switchIfEmpty(Mono.defer(() -> processNew(command, payloadHash)
					.<ProcessingOutcome>map(Processed::new)
					// Outside the TransactionalOperator: by now the whole transaction, balance decrease
					// included, has rolled back. A concurrent request with the same reference won the INSERT.
					.onErrorResume(DuplicateKeyException.class, duplicate -> repository
						.findByReference(command.paymentReference())
						.map(original -> compare(original, payloadHash))
						.switchIfEmpty(Mono.error(duplicate)))));
		});
	}

	private static ProcessingOutcome compare(PaymentReport original, String payloadHash) {
		return original.payloadHash().equals(payloadHash) ? new Replayed(original) : new Conflict(original);
	}

	private Mono<PaymentReport> processNew(PaymentCommand command, String payloadHash) {
		// PostgreSQL stores microseconds; truncating keeps the returned report equal to the stored one.
		Instant processedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		return repository.findOrder(command.collectionOrderId())
			.map(Optional::of)
			// A missing order must still flow on so ORDER_NOT_FOUND gets persisted.
			.defaultIfEmpty(Optional.empty())
			.flatMap(order -> PaymentRules.evaluate(order, command)
				.map(reason -> save(command, ReportStatus.REJECTED, reason, payloadHash, processedAt))
				.orElseGet(() -> apply(command, payloadHash, processedAt)))
			.as(transactionalOperator::transactional);
	}

	private Mono<PaymentReport> apply(PaymentCommand command, String payloadHash, Instant processedAt) {
		return repository
			.decreaseBalanceIfAvailable(command.collectionOrderId(), command.merchantId(), command.amount())
			.flatMap(decreased -> decreased
					? save(command, ReportStatus.APPLIED, null, payloadHash, processedAt)
					// false = insufficient balance: order, merchant and status were just validated and only
					// pending_balance can change concurrently. Revisit if order status ever becomes mutable.
					: save(command, ReportStatus.REJECTED, RejectionReason.INSUFFICIENT_BALANCE, payloadHash,
							processedAt));
	}

	private Mono<PaymentReport> save(PaymentCommand command, ReportStatus status, RejectionReason reason,
			String payloadHash, Instant processedAt) {
		return repository.insertReport(new PaymentReport(command.paymentReference(), command.collectionOrderId(),
				command.merchantId(), command.amount(), status, reason, payloadHash, processedAt));
	}

}
