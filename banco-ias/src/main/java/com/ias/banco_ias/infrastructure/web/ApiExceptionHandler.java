package com.ias.banco_ias.infrastructure.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(WebExchangeBindException.class)
	ProblemDetail handleInvalidBody(WebExchangeBindException ex) {
		List<Map<String, String>> errors = ex.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.sorted(Comparator.comparing((Map<String, String> error) -> error.get("field"))
				.thenComparing(error -> error.get("message")))
			.toList();
		ProblemDetail problem = badRequest("The request body has invalid fields.");
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleInvalidParameters(HandlerMethodValidationException ex) {
		List<Map<String, String>> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> Map.of("parameter", String.valueOf(result.getMethodParameter().getParameterName()),
						"message", messageOf(error))))
			.toList();
		ProblemDetail problem = badRequest("The request has invalid parameters.");
		problem.setProperty("errors", errors);
		return problem;
	}

	// Malformed JSON, wrong types in the body or in query parameters. WebExchangeBindException is a
	// subclass, but its more specific handler above takes precedence.
	@ExceptionHandler(ServerWebInputException.class)
	ProblemDetail handleUnreadableInput(ServerWebInputException ex) {
		return badRequest(ex.getReason() != null ? ex.getReason() : "The request could not be read.");
	}

	@ExceptionHandler(PaymentReportNotFoundException.class)
	ProblemDetail handleNotFound(PaymentReportNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Payment report not found");
		problem.setProperty("paymentReference", ex.getPaymentReference());
		return problem;
	}

	private static ProblemDetail badRequest(String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
		problem.setTitle("Invalid request");
		return problem;
	}

	private static String messageOf(MessageSourceResolvable error) {
		return String.valueOf(error.getDefaultMessage());
	}

}
