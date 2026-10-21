package com.ias.banco_ias.application;

import java.math.BigDecimal;

import com.ias.banco_ias.domain.CollectionOrder;
import com.ias.banco_ias.domain.PaymentReport;
import reactor.core.publisher.Mono;

public interface PaymentReportRepository {

	/** Empty when the order does not exist. */
	Mono<CollectionOrder> findOrder(String orderId);

	/**
	 * Atomically subtracts {@code amount} from the pending balance only if the order belongs to
	 * the merchant, is OPEN and has enough balance. Emits true if the row was updated.
	 */
	Mono<Boolean> decreaseBalanceIfAvailable(String orderId, String merchantId, BigDecimal amount);

	Mono<PaymentReport> insertReport(PaymentReport report);

	/** Empty when no report has been processed with this reference. */
	Mono<PaymentReport> findByReference(String paymentReference);

}
