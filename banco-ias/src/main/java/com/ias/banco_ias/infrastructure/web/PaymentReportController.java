package com.ias.banco_ias.infrastructure.web;

import java.net.URI;

import com.ias.banco_ias.application.PaymentReportQueryService;
import com.ias.banco_ias.application.ProcessPaymentReportUseCase;
import com.ias.banco_ias.application.ProcessingOutcome;
import com.ias.banco_ias.application.ProcessingOutcome.Conflict;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.ReportStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping(PaymentReportController.BASE_PATH)
public class PaymentReportController {

	static final String BASE_PATH = "/api/payment-reports";

	static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

	private final ProcessPaymentReportUseCase processPaymentReport;

	private final PaymentReportQueryService queryService;

	public PaymentReportController(ProcessPaymentReportUseCase processPaymentReport,
			PaymentReportQueryService queryService) {
		this.processPaymentReport = processPaymentReport;
		this.queryService = queryService;
	}

	@PostMapping
	public Mono<ResponseEntity<?>> process(@Valid @RequestBody PaymentReportRequest request) {
		return processPaymentReport.process(request.toCommand()).map(PaymentReportController::toResponse);
	}

	@GetMapping("/{paymentReference}")
	public Mono<PaymentReportResponse> findByReference(@PathVariable String paymentReference) {
		return queryService.findByReference(paymentReference)
			.map(PaymentReportResponse::from)
			.switchIfEmpty(Mono.error(() -> new PaymentReportNotFoundException(paymentReference)));
	}

	// Constraints directly on the parameter trigger Spring's built-in method validation
	// (HandlerMethodValidationException), so no @Validated is needed on the class.
	@GetMapping
	public Flux<PaymentReportResponse> findRecent(
			@RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
		return queryService.findRecent(limit).map(PaymentReportResponse::from);
	}

	private static ResponseEntity<?> toResponse(ProcessingOutcome outcome) {
		return switch (outcome) {
			case Processed(PaymentReport report) when report.status() == ReportStatus.APPLIED ->
				ResponseEntity.created(location(report)).body(PaymentReportResponse.from(report));
			case Processed(PaymentReport report) -> ResponseEntity.ok(PaymentReportResponse.from(report));
			case Replayed(PaymentReport original) -> ResponseEntity.ok()
				.header(IDEMPOTENT_REPLAYED_HEADER, "true")
				.body(PaymentReportResponse.from(original));
			case Conflict(PaymentReport original) -> ResponseEntity.status(HttpStatus.CONFLICT)
				.body(conflictProblem(original.paymentReference()));
		};
	}

	private static URI location(PaymentReport report) {
		return UriComponentsBuilder.fromPath(BASE_PATH + "/{paymentReference}")
			.buildAndExpand(report.paymentReference())
			.encode()
			.toUri();
	}

	private static ProblemDetail conflictProblem(String paymentReference) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The payment reference was already processed with different data; the original report is preserved.");
		problem.setTitle("Payment reference conflict");
		problem.setProperty("paymentReference", paymentReference);
		return problem;
	}

}
