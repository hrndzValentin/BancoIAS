package com.ias.banco_ias.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PaymentCommandTest {

	@Test
	void sameAmountWithDifferentScaleProducesSameHash() {
		PaymentCommand plain = new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal("600000"));
		PaymentCommand scaled = new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal("600000.00"));

		assertEquals(plain.payloadHash(), scaled.payloadHash());
	}

	@Test
	void identifiersAreTrimmedOnceAndHashOverNormalizedValues() {
		PaymentCommand padded = new PaymentCommand("  PSE-REF-001 ", " ORD-PSE-1001\t", "\tCOM-10  ", new BigDecimal("600000"));
		PaymentCommand clean = new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal("600000"));

		assertEquals("PSE-REF-001", padded.paymentReference());
		assertEquals("ORD-PSE-1001", padded.collectionOrderId());
		assertEquals("COM-10", padded.merchantId());
		assertEquals(clean.payloadHash(), padded.payloadHash());
	}

	@Test
	void differentAmountProducesDifferentHash() {
		PaymentCommand original = new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal("600000"));
		PaymentCommand changed = new PaymentCommand("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal("500000"));

		assertNotEquals(original.payloadHash(), changed.payloadHash());
	}

}
