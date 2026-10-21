package com.ias.banco_ias.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.ias.banco_ias.application.PaymentReportRepository;
import com.ias.banco_ias.domain.CollectionOrder;
import com.ias.banco_ias.domain.OrderStatus;
import com.ias.banco_ias.domain.PaymentReport;
import com.ias.banco_ias.domain.RejectionReason;
import com.ias.banco_ias.domain.ReportStatus;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

@Repository
public class R2dbcPaymentReportRepository implements PaymentReportRepository {

	private final DatabaseClient databaseClient;

	public R2dbcPaymentReportRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	@Override
	public Mono<CollectionOrder> findOrder(String orderId) {
		return databaseClient
			.sql("SELECT id, merchant_id, status, pending_balance FROM collection_order WHERE id = :id")
			.bind("id", orderId)
			.map(row -> new CollectionOrder(row.get("id", String.class), row.get("merchant_id", String.class),
					OrderStatus.valueOf(row.get("status", String.class)),
					row.get("pending_balance", BigDecimal.class)))
			.one();
	}

	@Override
	public Mono<Boolean> decreaseBalanceIfAvailable(String orderId, String merchantId, BigDecimal amount) {
		// The new balance is computed by PostgreSQL on the latest committed row version, never by the app.
		return databaseClient.sql("""
				UPDATE collection_order
				   SET pending_balance = pending_balance - :amount
				 WHERE id = :id
				   AND merchant_id = :merchantId
				   AND status = 'OPEN'
				   AND pending_balance >= :amount
				""")
			.bind("amount", amount)
			.bind("id", orderId)
			.bind("merchantId", merchantId)
			.fetch()
			.rowsUpdated()
			.map(rows -> rows == 1);
	}

	@Override
	public Mono<PaymentReport> insertReport(PaymentReport report) {
		DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
				INSERT INTO payment_report (payment_reference, collection_order_id, merchant_id, amount,
				                            status, rejection_reason, payload_hash, processed_at)
				VALUES (:paymentReference, :collectionOrderId, :merchantId, :amount,
				        :status, :rejectionReason, :payloadHash, :processedAt)
				RETURNING payment_reference, collection_order_id, merchant_id, amount,
				          status, rejection_reason, payload_hash, processed_at
				""")
			.bind("paymentReference", report.paymentReference())
			.bind("collectionOrderId", report.collectionOrderId())
			.bind("merchantId", report.merchantId())
			.bind("amount", report.amount())
			.bind("status", report.status().name())
			.bind("payloadHash", report.payloadHash())
			.bind("processedAt", OffsetDateTime.ofInstant(report.processedAt(), ZoneOffset.UTC));
		spec = (report.rejectionReason() == null) ? spec.bindNull("rejectionReason", String.class)
				: spec.bind("rejectionReason", report.rejectionReason().name());
		return spec.map(R2dbcPaymentReportRepository::toPaymentReport).one();
	}

	@Override
	public Mono<PaymentReport> findByReference(String paymentReference) {
		return databaseClient.sql("""
				SELECT payment_reference, collection_order_id, merchant_id, amount,
				       status, rejection_reason, payload_hash, processed_at
				  FROM payment_report
				 WHERE payment_reference = :paymentReference
				""")
			.bind("paymentReference", paymentReference)
			.map(R2dbcPaymentReportRepository::toPaymentReport)
			.one();
	}

	@Override
	public Flux<PaymentReport> findRecent(int limit) {
		// id breaks ties between reports processed in the same microsecond, keeping the order stable.
		return databaseClient.sql("""
				SELECT payment_reference, collection_order_id, merchant_id, amount,
				       status, rejection_reason, payload_hash, processed_at
				  FROM payment_report
				 ORDER BY processed_at DESC, id DESC
				 LIMIT :limit
				""")
			.bind("limit", limit)
			.map(R2dbcPaymentReportRepository::toPaymentReport)
			.all();
	}

	private static PaymentReport toPaymentReport(Readable row) {
		String rejectionReason = row.get("rejection_reason", String.class);
		return new PaymentReport(row.get("payment_reference", String.class),
				row.get("collection_order_id", String.class), row.get("merchant_id", String.class),
				row.get("amount", BigDecimal.class), ReportStatus.valueOf(row.get("status", String.class)),
				(rejectionReason != null) ? RejectionReason.valueOf(rejectionReason) : null,
				row.get("payload_hash", String.class), row.get("processed_at", OffsetDateTime.class).toInstant());
	}

}
