package com.ias.banco_ias.application;

import com.ias.banco_ias.domain.PaymentReport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

@Service
public class PaymentReportQueryService {

	private final PaymentReportRepository repository;

	public PaymentReportQueryService(PaymentReportRepository repository) {
		this.repository = repository;
	}

	/** Empty when no report exists. The reference is trimmed like in {@code PaymentCommand}. */
	public Mono<PaymentReport> findByReference(String paymentReference) {
		return repository.findByReference(paymentReference.trim());
	}

	public Flux<PaymentReport> findRecent(int limit) {
		return repository.findRecent(limit);
	}

}
