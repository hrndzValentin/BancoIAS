package com.ias.banco_ias.infrastructure.web;

import java.math.BigDecimal;

import com.ias.banco_ias.domain.PaymentCommand;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// No @Positive on amount: amount <= 0 is a business rejection (INVALID_AMOUNT) that must be persisted.
// The sizes match the payment_report columns so an over-long value never reaches the INSERT.
public record PaymentReportRequest(
		@NotBlank @Size(max = 50) String paymentReference,
		@NotBlank @Size(max = 30) String collectionOrderId,
		@NotBlank @Size(max = 30) String merchantId,
		@NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {

	PaymentCommand toCommand() {
		return new PaymentCommand(paymentReference, collectionOrderId, merchantId, amount);
	}

}
