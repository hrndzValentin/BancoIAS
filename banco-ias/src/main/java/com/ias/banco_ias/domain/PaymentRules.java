package com.ias.banco_ias.domain;

import java.math.BigDecimal;
import java.util.Optional;

public final class PaymentRules {

	private PaymentRules() {
	}

	/**
	 * Evaluates the RF02 rules in order and returns the first one that fails,
	 * or empty when the payment can be applied.
	 */
	public static Optional<RejectionReason> evaluate(Optional<CollectionOrder> order, PaymentCommand command) {
		if (command.amount().compareTo(BigDecimal.ZERO) <= 0) {
			return Optional.of(RejectionReason.INVALID_AMOUNT);
		}
		if (order.isEmpty()) {
			return Optional.of(RejectionReason.ORDER_NOT_FOUND);
		}
		CollectionOrder found = order.get();
		if (!found.merchantId().equals(command.merchantId())) {
			return Optional.of(RejectionReason.MERCHANT_MISMATCH);
		}
		if (found.status() != OrderStatus.OPEN) {
			return Optional.of(RejectionReason.ORDER_NOT_ENABLED);
		}
		if (command.amount().compareTo(found.pendingBalance()) > 0) {
			return Optional.of(RejectionReason.INSUFFICIENT_BALANCE);
		}
		return Optional.empty();
	}

}
