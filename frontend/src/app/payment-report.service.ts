import { HttpClient, HttpErrorResponse, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { PaymentReport, PaymentReportRequest, PaymentResult, ValidationError } from './payment-report.model';

const BASE_URL = '/api/payment-reports';

/** Subset of the backend ProblemDetail bodies (RFC 9457) the UI needs. */
interface ProblemDetail {
  detail?: string;
  paymentReference?: string;
  errors?: { field?: string; parameter?: string; message: string }[];
}

@Injectable({ providedIn: 'root' })
export class PaymentReportService {
  private readonly http = inject(HttpClient);

  /** Never errors: every HTTP outcome is mapped to a PaymentResult variant. */
  create(request: PaymentReportRequest): Observable<PaymentResult> {
    return this.http.post<PaymentReport>(BASE_URL, request, { observe: 'response' }).pipe(
      map((response) => toResult(response)),
      catchError((error: HttpErrorResponse) => of(toErrorResult(error))),
    );
  }

  getByReference(paymentReference: string): Observable<PaymentReport> {
    return this.http.get<PaymentReport>(`${BASE_URL}/${encodeURIComponent(paymentReference)}`);
  }

  listRecent(limit = 20): Observable<PaymentReport[]> {
    return this.http.get<PaymentReport[]>(BASE_URL, { params: new HttpParams().set('limit', limit) });
  }
}

function toResult(response: HttpResponse<PaymentReport>): PaymentResult {
  const report = response.body as PaymentReport;
  if (response.headers.get('Idempotent-Replayed') === 'true') {
    return { kind: 'replayed', report };
  }
  if (response.status === 201) {
    return { kind: 'applied', report };
  }
  return report.status === 'REJECTED' ? { kind: 'rejected', report } : { kind: 'applied', report };
}

function toErrorResult(error: HttpErrorResponse): PaymentResult {
  const problem = (typeof error.error === 'object' && error.error !== null ? error.error : {}) as ProblemDetail;
  switch (error.status) {
    case 409:
      return {
        kind: 'conflict',
        paymentReference: problem.paymentReference ?? '',
        message: problem.detail ?? 'La referencia ya fue procesada con datos diferentes.',
      };
    case 400:
      return {
        kind: 'invalid',
        message: problem.detail ?? 'La solicitud no es válida.',
        errors: (problem.errors ?? []).map(
          (e): ValidationError => ({ name: e.field ?? e.parameter ?? '', message: e.message }),
        ),
      };
    case 0:
      return { kind: 'error', message: 'No se pudo conectar con el servidor.' };
    default:
      return { kind: 'error', message: problem.detail ?? `Error inesperado del servidor (HTTP ${error.status}).` };
  }
}
