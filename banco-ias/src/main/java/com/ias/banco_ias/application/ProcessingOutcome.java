package com.ias.banco_ias.application;

import com.ias.banco_ias.domain.PaymentReport;

/**
 * Result of processing a payment report, distinguishing a new report from a repeated
 * paymentReference (RF05).
 */
public sealed interface ProcessingOutcome {

	/** The report was processed now (APPLIED or REJECTED). */
	record Processed(PaymentReport report) implements ProcessingOutcome {
	}

	/** The reference was already processed with the same payload; the original result is returned. */
	record Replayed(PaymentReport original) implements ProcessingOutcome {
	}

	/** The reference was already processed with a different payload; the original is preserved. */
	record Conflict(PaymentReport original) implements ProcessingOutcome {
	}

}
