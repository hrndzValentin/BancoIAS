import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('loads and renders recent reports on start', async () => {
    const fixture = TestBed.createComponent(App);
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url === '/api/payment-reports' && r.params.get('limit') === '20')
      .flush([
        {
          paymentReference: 'PSE-REF-001',
          collectionOrderId: 'ORD-PSE-1001',
          merchantId: 'COM-10',
          amount: 600000,
          status: 'REJECTED',
          rejectionReason: 'INSUFFICIENT_BALANCE',
          processedAt: '2026-10-20T15:30:00Z',
        },
      ]);
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('Recaudos PSE');
    const cells = Array.from(compiled.querySelectorAll('tbody td')).map((td) => td.textContent?.trim());
    expect(cells).toContain('PSE-REF-001');
    expect(cells).toContain('Rechazado');
    expect(cells).toContain('El monto supera el saldo pendiente de la orden');
  });
});
