import { Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { OrderResponse } from '../../models/order.model';
import { PaymentResponse } from '../../models/payment.model';
import { CartService } from '../../services/cart.service';
import { OrderService } from '../../services/order.service';
import { PaymentService } from '../../services/payment.service';

@Component({
  selector: 'app-checkout',
  templateUrl: './checkout.component.html',
  styleUrls: ['./checkout.component.scss'],
})
export class CheckoutComponent {
  items$ = this.cartService.items$;

  customerEmail = '';
  paymentMethod = 'CARD';
  submitting = false;
  error: string | null = null;

  order: OrderResponse | null = null;
  payment: PaymentResponse | null = null;

  constructor(
    private readonly cartService: CartService,
    private readonly orderService: OrderService,
    private readonly paymentService: PaymentService
  ) {}

  get total(): number {
    return this.cartService.snapshot.reduce((sum, item) => sum + item.quantity * item.unitPrice, 0);
  }

  placeOrder(): void {
    this.error = null;
    this.submitting = true;
    this.orderService
      .createOrder({ customerEmail: this.customerEmail, items: this.cartService.snapshot })
      .subscribe({
        next: (order) => {
          this.order = order;
          this.submitting = false;
        },
        error: (err: HttpErrorResponse) => {
          this.error = err.error?.message ?? "Errore durante la creazione dell'ordine";
          this.submitting = false;
        },
      });
  }

  pay(): void {
    if (!this.order) {
      return;
    }
    this.error = null;
    this.submitting = true;
    this.paymentService
      .pay({ orderId: this.order.id, amount: this.order.totalAmount, method: this.paymentMethod })
      .subscribe({
        next: (payment) => {
          this.payment = payment;
          this.submitting = false;
          if (payment.status === 'COMPLETED') {
            this.cartService.clear();
          }
        },
        error: (err: HttpErrorResponse) => {
          this.error = err.error?.message ?? 'Errore durante il pagamento';
          this.submitting = false;
        },
      });
  }
}
