package com.ias.banco_ias.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PaymentRulesTest {

	private static final CollectionOrder OPEN_ORDER =
			new CollectionOrder("ORD-PSE-1001", "COM-10", OrderStatus.OPEN, new BigDecimal("1000000"));

	private static final CollectionOrder BLOCKED_ORDER =
			new CollectionOrder("ORD-PSE-1002", "COM-10", OrderStatus.BLOCKED, new BigDecimal("800000"));

	private static PaymentCommand command(String orderId, String merchantId, String amount) {
		return new PaymentCommand("PSE-REF-001", orderId, merchantId, new BigDecimal(amount));
	}

	@Test
	void appliesValidPayment() {
		assertEquals(Optional.empty(),
				PaymentRules.evaluate(Optional.of(OPEN_ORDER), command("ORD-PSE-1001", "COM-10", "600000")));
	}

	@Test
	void appliesPaymentEqualToPendingBalance() {
		assertEquals(Optional.empty(),
				PaymentRules.evaluate(Optional.of(OPEN_ORDER), command("ORD-PSE-1001", "COM-10", "1000000.00")));
	}

	@Test
	void rejectsZeroAmount() {
		assertEquals(Optional.of(RejectionReason.INVALID_AMOUNT),
				PaymentRules.evaluate(Optional.of(OPEN_ORDER), command("ORD-PSE-1001", "COM-10", "0")));
	}

	@Test
	void rejectsMissingOrder() {
		assertEquals(Optional.of(RejectionReason.ORDER_NOT_FOUND),
				PaymentRules.evaluate(Optional.empty(), command("ORD-PSE-9999", "COM-10", "600000")));
	}

	@Test
	void rejectsOrderOfAnotherMerchant() {
		assertEquals(Optional.of(RejectionReason.MERCHANT_MISMATCH),
				PaymentRules.evaluate(Optional.of(OPEN_ORDER), command("ORD-PSE-1001", "COM-20", "600000")));
	}

	@Test
	void rejectsOrderThatIsNotOpen() {
		assertEquals(Optional.of(RejectionReason.ORDER_NOT_ENABLED),
				PaymentRules.evaluate(Optional.of(BLOCKED_ORDER), command("ORD-PSE-1002", "COM-10", "100000")));
	}

	@Test
	void rejectsAmountAbovePendingBalance() {
		assertEquals(Optional.of(RejectionReason.INSUFFICIENT_BALANCE),
				PaymentRules.evaluate(Optional.of(OPEN_ORDER), command("ORD-PSE-1001", "COM-10", "1000000.01")));
	}

}
