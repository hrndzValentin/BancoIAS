package com.ias.banco_ias.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record PaymentCommand(String paymentReference, String collectionOrderId, String merchantId, BigDecimal amount) {

	// Same scale as payment_report.amount NUMERIC(19,2); HALF_UP matches PostgreSQL rounding.
	private static final int AMOUNT_SCALE = 2;

	// ASCII unit separator: cannot appear in the business identifiers, so fields can't bleed into each other.
	private static final String FIELD_SEPARATOR = "\u001F";

	// Identifiers are normalized once here, so the hash, the domain rules and persistence all see the same values.
	public PaymentCommand {
		paymentReference = Objects.requireNonNull(paymentReference, "paymentReference").trim();
		collectionOrderId = Objects.requireNonNull(collectionOrderId, "collectionOrderId").trim();
		merchantId = Objects.requireNonNull(merchantId, "merchantId").trim();
		Objects.requireNonNull(amount, "amount");
	}

	/**
	 * SHA-256 (hex) of the business fields, used to tell whether a repeated
	 * paymentReference carries the same data as the original report (RF05).
	 */
	public String payloadHash() {
		String canonical = String.join(FIELD_SEPARATOR,
				paymentReference,
				collectionOrderId,
				merchantId,
				amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
