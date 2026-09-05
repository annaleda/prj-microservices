import { Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { OrderResponse } from '../../models/order.model';
import { AuthService } from '../../services/auth.service';
import { CartService } from '../../services/cart.service';
import { OrderService } from '../../services/order.service';

@Component({
  selector: 'app-checkout',
  templateUrl: './checkout.component.html',
  styleUrls: ['./checkout.component.scss'],
})
export class CheckoutComponent {
  items$ = this.cartService.items$;

  submitting = false;
  /** L'ordine e' stato creato e si attende l'esito della saga. */
  awaitingOutcome = false;
  error: string | null = null;

  order: OrderResponse | null = null;

  constructor(
    private readonly cartService: CartService,
    private readonly orderService: OrderService,
    readonly auth: AuthService
  ) {}

  get total(): number {
    return this.cartService.snapshot.reduce((sum, item) => sum + item.quantity * item.unitPrice, 0);
  }

  /**
   * Unica azione del checkout: creare l'ordine.
   *
   * Riserva delle scorte e pagamento non si chiedono piu' da qui: sono
   * passi della saga orchestrata dall'Integration Service, innescata
   * dall'evento order.created. Il frontend si limita ad attenderne
   * l'esito.
   */
  placeOrder(): void {
    this.error = null;
    this.submitting = true;

    this.orderService
      .createOrder({ items: this.cartService.snapshot })
      .subscribe({
        next: (order) => {
          this.order = order;
          this.submitting = false;
          this.awaitOutcome(order.id);
        },
        error: (err: HttpErrorResponse) => {
          this.error = err.error?.message ?? "Errore durante la creazione dell'ordine";
          this.submitting = false;
        },
      });
  }

  private awaitOutcome(orderId: number): void {
    this.awaitingOutcome = true;
    this.orderService.awaitSagaOutcome(orderId).subscribe({
      next: (order) => {
        this.order = order;
        this.awaitingOutcome = false;
        if (order.status === 'CONFIRMED') {
          this.cartService.clear();
        }
      },
      error: () => {
        this.awaitingOutcome = false;
        this.error =
          "L'ordine e' stato creato ma non e' ancora arrivato l'esito. " +
          'Ricarica la pagina tra poco per vederne lo stato aggiornato.';
      },
    });
  }
}
