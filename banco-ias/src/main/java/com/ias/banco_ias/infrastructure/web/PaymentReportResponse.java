package com.ias.banco_ias.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;

// payloadHash and the internal id are deliberately not exposed.
public record PaymentReportResponse(
		String paymentReference,
		String collectionOrderId,
		String merchantId,
		BigDecimal amount,
		ReportStatus status,
		RejectionReason rejectionReason,
		Instant processedAt) {

	static PaymentReportResponse from(PaymentReport report) {
		return new PaymentReportResponse(report.paymentReference(), report.collectionOrderId(), report.merchantId(),
				report.amount(), report.status(), report.rejectionReason(), report.processedAt());
	}

}
