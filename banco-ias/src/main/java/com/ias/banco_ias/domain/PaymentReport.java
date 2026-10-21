package com.ias.banco_ias.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentReport(
		String paymentReference,
		String collectionOrderId,
		String merchantId,
		BigDecimal amount,
		ReportStatus status,
		RejectionReason rejectionReason,
		String payloadHash,
		Instant processedAt) {

	// Mirrors chk_rejection_consistency in schema.sql.
	public PaymentReport {
		if ((status == ReportStatus.REJECTED) != (rejectionReason != null)) {
			throw new IllegalArgumentException("rejectionReason is required if and only if status is REJECTED");
		}
	}

}
