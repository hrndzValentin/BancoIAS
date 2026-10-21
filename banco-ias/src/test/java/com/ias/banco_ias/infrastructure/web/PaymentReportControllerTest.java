package com.ias.banco_ias.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import com.ias.banco_ias.application.PaymentReportQueryService;
import com.ias.banco_ias.application.ProcessPaymentReportUseCase;
import com.ias.banco_ias.application.ProcessingOutcome.Conflict;
import com.ias.banco_ias.application.ProcessingOutcome.Processed;
import com.ias.banco_ias.application.ProcessingOutcome.Replayed;
import com.ias.banco_ias.domain.PaymentCommand;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(PaymentReportController.class)
class PaymentReportControllerTest {

	private static final String VALID_BODY = """
			{"paymentReference":"PSE-REF-001","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10","amount":600000}
			""";

	private static final Instant PROCESSED_AT = Instant.parse("2026-10-20T15:30:00.123456Z");

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private ProcessPaymentReportUseCase processPaymentReport;

	@MockitoBean
	private PaymentReportQueryService queryService;

	private static PaymentReport report(ReportStatus status, RejectionReason reason, String amount) {
		return new PaymentReport("PSE-REF-001", "ORD-PSE-1001", "COM-10", new BigDecimal(amount), status, reason,
				"a".repeat(64), PROCESSED_AT);
	}

	private WebTestClient.ResponseSpec post(String body) {
		return webTestClient.post()
			.uri("/api/payment-reports")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange();
	}

	@Test
	void appliedReportReturnsCreatedWithLocation() {
		when(processPaymentReport.process(any()))
			.thenReturn(Mono.just(new Processed(report(ReportStatus.APPLIED, null, "600000.00"))));

		post(VALID_BODY).expectStatus()
			.isCreated()
			.expectHeader()
			.location("/api/payment-reports/PSE-REF-001")
			.expectHeader()
			.doesNotExist("Idempotent-Replayed")
			.expectBody()
			.jsonPath("$.paymentReference").isEqualTo("PSE-REF-001")
			.jsonPath("$.collectionOrderId").isEqualTo("ORD-PSE-1001")
			.jsonPath("$.merchantId").isEqualTo("COM-10")
			.jsonPath("$.amount").isEqualTo(600000.00)
			.jsonPath("$.status").isEqualTo("APPLIED")
			.jsonPath("$.rejectionReason").isEmpty()
			.jsonPath("$.processedAt").isEqualTo("2026-10-20T15:30:00.123456Z")
			.jsonPath("$.payloadHash").doesNotExist()
			.jsonPath("$.id").doesNotExist();
	}

	@Test
	void rejectedReportReturnsOk() {
		when(processPaymentReport.process(any())).thenReturn(Mono
			.just(new Processed(report(ReportStatus.REJECTED, RejectionReason.INSUFFICIENT_BALANCE, "600000.00"))));

		post(VALID_BODY).expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist("Location")
			.expectBody()
			.jsonPath("$.status").isEqualTo("REJECTED")
			.jsonPath("$.rejectionReason").isEqualTo("INSUFFICIENT_BALANCE");
	}

	@Test
	void replayReturnsOkWithHeaderAndOriginalResult() {
		when(processPaymentReport.process(any()))
			.thenReturn(Mono.just(new Replayed(report(ReportStatus.APPLIED, null, "600000.00"))));

		post(VALID_BODY).expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("Idempotent-Replayed", "true")
			.expectBody()
			.jsonPath("$.status").isEqualTo("APPLIED")
			.jsonPath("$.amount").isEqualTo(600000.00);
	}

	@Test
	void conflictReturnsProblemWithoutOriginalReport() {
		when(processPaymentReport.process(any()))
			.thenReturn(Mono.just(new Conflict(report(ReportStatus.APPLIED, null, "600000.00"))));

		post(VALID_BODY).expectStatus()
			.isEqualTo(409)
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.status").isEqualTo(409)
			.jsonPath("$.title").isEqualTo("Payment reference conflict")
			.jsonPath("$.detail").isNotEmpty()
			.jsonPath("$.paymentReference").isEqualTo("PSE-REF-001")
			.jsonPath("$.amount").doesNotExist();
	}

	@Test
	void nonPositiveAmountIsNotAValidationErrorButReachesTheUseCase() {
		when(processPaymentReport.process(any())).thenReturn(Mono
			.just(new Processed(report(ReportStatus.REJECTED, RejectionReason.INVALID_AMOUNT, "-5.00"))));

		post("""
				{"paymentReference":"PSE-REF-001","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10","amount":-5}
				""").expectStatus().isOk().expectBody().jsonPath("$.rejectionReason").isEqualTo("INVALID_AMOUNT");
	}

	@Test
	void invalidBodyFieldsReturnBadRequestWithFieldDetails() {
		post("""
				{"paymentReference":" ","collectionOrderId":"%s","merchantId":"COM-10","amount":10.123}
				""".formatted("X".repeat(31)))
			.expectStatus()
			.isBadRequest()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Invalid request")
			.jsonPath("$.errors.length()").isEqualTo(3)
			.jsonPath("$.errors[?(@.field == 'amount')]").exists()
			.jsonPath("$.errors[?(@.field == 'collectionOrderId')]").exists()
			.jsonPath("$.errors[?(@.field == 'paymentReference')]").exists();
		verify(processPaymentReport, never()).process(any(PaymentCommand.class));
	}

	@Test
	void missingAmountReturnsBadRequest() {
		post("""
				{"paymentReference":"PSE-REF-001","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10"}
				""").expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.errors[0].field").isEqualTo("amount");
	}

	@Test
	void malformedJsonReturnsBadRequest() {
		post("{\"paymentReference\": ").expectStatus()
			.isBadRequest()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Invalid request")
			.jsonPath("$.detail").isNotEmpty();
		verify(processPaymentReport, never()).process(any(PaymentCommand.class));
	}

	@Test
	void wrongTypeInBodyReturnsBadRequest() {
		post("""
				{"paymentReference":"PSE-REF-001","collectionOrderId":"ORD-PSE-1001","merchantId":"COM-10","amount":"abc"}
				""").expectStatus().isBadRequest().expectBody().jsonPath("$.title").isEqualTo("Invalid request");
	}

	@Test
	void findByReferenceReturnsReport() {
		when(queryService.findByReference("PSE-REF-001"))
			.thenReturn(Mono.just(report(ReportStatus.APPLIED, null, "600000.00")));

		webTestClient.get()
			.uri("/api/payment-reports/PSE-REF-001")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.paymentReference").isEqualTo("PSE-REF-001")
			.jsonPath("$.payloadHash").doesNotExist();
	}

	@Test
	void unknownReferenceReturnsNotFoundProblem() {
		when(queryService.findByReference("PSE-REF-404")).thenReturn(Mono.empty());

		webTestClient.get()
			.uri("/api/payment-reports/PSE-REF-404")
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.status").isEqualTo(404)
			.jsonPath("$.title").isEqualTo("Payment report not found")
			.jsonPath("$.paymentReference").isEqualTo("PSE-REF-404");
	}

	@Test
	void recentUsesDefaultLimitOfTwenty() {
		when(queryService.findRecent(20)).thenReturn(Flux.just(report(ReportStatus.APPLIED, null, "600000.00")));

		webTestClient.get()
			.uri("/api/payment-reports")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.length()").isEqualTo(1)
			.jsonPath("$[0].paymentReference").isEqualTo("PSE-REF-001");
		verify(queryService).findRecent(20);
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 100 })
	void recentAcceptsLimitBoundaries(int limit) {
		when(queryService.findRecent(limit)).thenReturn(Flux.empty());

		webTestClient.get().uri("/api/payment-reports?limit={limit}", limit).exchange().expectStatus().isOk();
		verify(queryService).findRecent(limit);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 101 })
	void recentRejectsLimitOutOfRange(int limit) {
		webTestClient.get()
			.uri("/api/payment-reports?limit={limit}", limit)
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Invalid request")
			.jsonPath("$.errors[0].parameter").isEqualTo("limit");
		verify(queryService, never()).findRecent(anyInt());
	}

	@Test
	void recentRejectsNonNumericLimit() {
		webTestClient.get()
			.uri("/api/payment-reports?limit=abc")
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectHeader()
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Invalid request");
		verify(queryService, never()).findRecent(anyInt());
	}

}
