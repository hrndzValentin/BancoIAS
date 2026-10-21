package com.ias.banco_ias.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.ias.banco_ias.domain.PaymentReport;

public record PaymentAppliedEvent(
		UUID eventId,
		String eventType,
		String paymentReference,
		String collectionOrderId,
		String merchantId,
		BigDecimal amount,
		Instant occurredAt) {

	public static final String TYPE = "PaymentApplied";

	// The id is derived from the reference, so the same applied payment always yields the same eventId
	// and consumers (or a future outbox relay) can deduplicate.
	public static PaymentAppliedEvent from(PaymentReport report) {
		UUID eventId = UUID.nameUUIDFromBytes((TYPE + ":" + report.paymentReference()).getBytes(StandardCharsets.UTF_8));
		return new PaymentAppliedEvent(eventId, TYPE, report.paymentReference(), report.collectionOrderId(),
				report.merchantId(), report.amount(), report.processedAt());
	}

}
