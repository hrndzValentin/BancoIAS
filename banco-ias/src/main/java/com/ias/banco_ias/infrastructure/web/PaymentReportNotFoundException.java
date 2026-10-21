package com.ias.banco_ias.infrastructure.web;

public class PaymentReportNotFoundException extends RuntimeException {

	private final String paymentReference;

	public PaymentReportNotFoundException(String paymentReference) {
		super("No payment report found for reference " + paymentReference);
		this.paymentReference = paymentReference;
	}

	public String getPaymentReference() {
		return paymentReference;
	}

}
