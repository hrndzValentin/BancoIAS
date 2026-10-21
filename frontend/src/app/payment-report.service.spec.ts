import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { PaymentReport, PaymentReportRequest, PaymentResult } from './payment-report.model';
import { PaymentReportService } from './payment-report.service';

const REQUEST: PaymentReportRequest = {
  paymentReference: 'PSE-REF-001',
  collectionOrderId: 'ORD-PSE-1001',
  merchantId: 'COM-10',
  amount: 600000,
};

const APPLIED: PaymentReport = {
  paymentReference: 'PSE-REF-001',
  collectionOrderId: 'ORD-PSE-1001',
  merchantId: 'COM-10',
  amount: 600000,
  status: 'APPLIED',
  rejectionReason: null,
  processedAt: '2026-10-20T15:30:00.123456Z',
};

const REJECTED: PaymentReport = { ...APPLIED, status: 'REJECTED', rejectionReason: 'INSUFFICIENT_BALANCE' };

describe('PaymentReportService', () => {
  let service: PaymentReportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PaymentReportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(): PaymentResult[] {
    const results: PaymentResult[] = [];
    service.create(REQUEST).subscribe((result) => results.push(result));
    return results;
  }

  function expectPost() {
    const req = http.expectOne('/api/payment-reports');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(REQUEST);
    return req;
  }

  it('maps 201 to applied', () => {
    const results = create();
    expectPost().flush(APPLIED, { status: 201, statusText: 'Created' });
    expect(results).toEqual([{ kind: 'applied', report: APPLIED }]);
  });

  it('maps 200 with a REJECTED report to rejected', () => {
    const results = create();
    expectPost().flush(REJECTED, { status: 200, statusText: 'OK' });
    expect(results).toEqual([{ kind: 'rejected', report: REJECTED }]);
  });

  it('maps 200 with Idempotent-Replayed header to replayed with the original report', () => {
    const results = create();
    expectPost().flush(APPLIED, { status: 200, statusText: 'OK', headers: { 'Idempotent-Replayed': 'true' } });
    expect(results).toEqual([{ kind: 'replayed', report: APPLIED }]);
  });

  it('maps a replayed rejection to replayed, not rejected', () => {
    const results = create();
    expectPost().flush(REJECTED, { status: 200, statusText: 'OK', headers: { 'Idempotent-Replayed': 'true' } });
    expect(results).toEqual([{ kind: 'replayed', report: REJECTED }]);
  });

  it('maps 409 to conflict with the ProblemDetail data', () => {
    const results = create();
    expectPost().flush(
      { status: 409, title: 'Payment reference conflict', detail: 'Already processed', paymentReference: 'PSE-REF-001' },
      { status: 409, statusText: 'Conflict' },
    );
    expect(results).toEqual([{ kind: 'conflict', paymentReference: 'PSE-REF-001', message: 'Already processed' }]);
  });

  it('maps 400 to invalid with field details', () => {
    const results = create();
    expectPost().flush(
      {
        status: 400,
        title: 'Invalid request',
        detail: 'The request body has invalid fields.',
        errors: [{ field: 'amount', message: 'must not be null' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(results).toEqual([
      {
        kind: 'invalid',
        message: 'The request body has invalid fields.',
        errors: [{ name: 'amount', message: 'must not be null' }],
      },
    ]);
  });

  it('maps 400 without errors (malformed input) to invalid with an empty list', () => {
    const results = create();
    expectPost().flush({ status: 400, detail: 'Failed to read HTTP message' }, { status: 400, statusText: 'Bad Request' });
    expect(results).toEqual([{ kind: 'invalid', message: 'Failed to read HTTP message', errors: [] }]);
  });

  it('maps other statuses to error', () => {
    const results = create();
    expectPost().flush('boom', { status: 500, statusText: 'Internal Server Error' });
    expect(results).toEqual([{ kind: 'error', message: 'Error inesperado del servidor (HTTP 500).' }]);
  });

  it('maps a network failure to error', () => {
    const results = create();
    expectPost().error(new ProgressEvent('error'));
    expect(results).toEqual([{ kind: 'error', message: 'No se pudo conectar con el servidor.' }]);
  });

  it('gets a report by encoded reference', () => {
    let report: PaymentReport | undefined;
    service.getByReference('REF 1/2').subscribe((r) => (report = r));
    const req = http.expectOne('/api/payment-reports/REF%201%2F2');
    expect(req.request.method).toBe('GET');
    req.flush(APPLIED);
    expect(report).toEqual(APPLIED);
  });

  it('lists recent reports with the given limit', () => {
    let reports: PaymentReport[] | undefined;
    service.listRecent(5).subscribe((r) => (reports = r));
    const req = http.expectOne((r) => r.url === '/api/payment-reports' && r.params.get('limit') === '5');
    expect(req.request.method).toBe('GET');
    req.flush([APPLIED, REJECTED]);
    expect(reports).toEqual([APPLIED, REJECTED]);
  });
});
