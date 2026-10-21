export type ReportStatus = 'APPLIED' | 'REJECTED';

export type RejectionReason =
  | 'INVALID_AMOUNT'
  | 'ORDER_NOT_FOUND'
  | 'MERCHANT_MISMATCH'
  | 'ORDER_NOT_ENABLED'
  | 'INSUFFICIENT_BALANCE';

export interface PaymentReportRequest {
  paymentReference: string;
  collectionOrderId: string;
  merchantId: string;
  amount: number;
}

export interface PaymentReport {
  paymentReference: string;
  collectionOrderId: string;
  merchantId: string;
  amount: number;
  status: ReportStatus;
  rejectionReason: RejectionReason | null;
  processedAt: string;
}

/** A field or parameter error from a 400 ProblemDetail. */
export interface ValidationError {
  name: string;
  message: string;
}

/** Outcome of POST /api/payment-reports, already translated from HTTP status and headers. */
export type PaymentResult =
  | { kind: 'applied'; report: PaymentReport }
  | { kind: 'rejected'; report: PaymentReport }
  | { kind: 'replayed'; report: PaymentReport }
  | { kind: 'conflict'; paymentReference: string; message: string }
  | { kind: 'invalid'; message: string; errors: ValidationError[] }
  | { kind: 'error'; message: string };

export const REJECTION_REASON_LABELS: Record<RejectionReason, string> = {
  INVALID_AMOUNT: 'El monto debe ser mayor que cero',
  ORDER_NOT_FOUND: 'La orden de recaudo no existe',
  MERCHANT_MISMATCH: 'La orden no pertenece al comercio informado',
  ORDER_NOT_ENABLED: 'La orden no está habilitada para recibir pagos',
  INSUFFICIENT_BALANCE: 'El monto supera el saldo pendiente de la orden',
};

export const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  APPLIED: 'Aplicado',
  REJECTED: 'Rechazado',
};
