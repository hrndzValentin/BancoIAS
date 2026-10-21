import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';

import {
  PaymentReport,
  PaymentResult,
  REJECTION_REASON_LABELS,
  REPORT_STATUS_LABELS,
  RejectionReason,
} from './payment-report.model';
import { PaymentReportService } from './payment-report.service';

const RECENT_LIMIT = 20;

const copFormat = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'COP',
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

const dateFormat = new Intl.DateTimeFormat('es-CO', { dateStyle: 'short', timeStyle: 'medium' });

@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly service = inject(PaymentReportService);

  protected readonly seedOrders = [
    { id: 'ORD-PSE-1001', label: 'COM-10 · OPEN · saldo inicial 1.000.000' },
    { id: 'ORD-PSE-1002', label: 'COM-10 · BLOCKED · saldo inicial 800.000' },
    { id: 'ORD-PSE-2001', label: 'COM-20 · OPEN · saldo inicial 2.000.000' },
  ];

  // amount only requires a value: amount <= 0 is a business rejection the UI must be able to demonstrate.
  protected readonly form = inject(FormBuilder).nonNullable.group({
    paymentReference: ['', [Validators.required, Validators.maxLength(50)]],
    collectionOrderId: ['', [Validators.required, Validators.maxLength(30)]],
    merchantId: ['COM-10', [Validators.required, Validators.maxLength(30)]],
    amount: new FormControl<number | null>(null, Validators.required),
  });

  protected readonly submitting = signal(false);
  protected readonly result = signal<PaymentResult | null>(null);
  protected readonly recent = signal<PaymentReport[]>([]);
  protected readonly recentError = signal<string | null>(null);

  protected readonly recentRows = computed(() =>
    this.recent().map((report) => ({
      reference: report.paymentReference,
      order: report.collectionOrderId,
      amount: this.formatCop(report.amount),
      status: REPORT_STATUS_LABELS[report.status],
      applied: report.status === 'APPLIED',
      reason: this.reasonLabel(report.rejectionReason),
      processedAt: dateFormat.format(new Date(report.processedAt)),
    })),
  );

  constructor() {
    this.loadRecent();
  }

  protected generateReference(): void {
    this.form.controls.paymentReference.setValue(`PSE-REF-${Date.now()}`);
  }

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.result.set(null);
    this.service
      .create({
        paymentReference: value.paymentReference,
        collectionOrderId: value.collectionOrderId,
        merchantId: value.merchantId,
        amount: value.amount as number,
      })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe((result) => {
        this.result.set(result);
        this.loadRecent();
      });
  }

  protected showError(name: keyof typeof this.form.controls, error: string): boolean {
    const control = this.form.controls[name];
    return control.touched && control.hasError(error);
  }

  protected formatCop(amount: number): string {
    return copFormat.format(amount);
  }

  protected reasonLabel(reason: RejectionReason | null): string {
    return reason ? REJECTION_REASON_LABELS[reason] : '';
  }

  private loadRecent(): void {
    this.service.listRecent(RECENT_LIMIT).subscribe({
      next: (reports) => {
        this.recent.set(reports);
        this.recentError.set(null);
      },
      error: () => this.recentError.set('No se pudieron cargar los reportes recientes.'),
    });
  }
}
