import { Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { CartItem } from '../../models/cart-item.model';
import { cancellationMessage as messageForReason, OrderResponse } from '../../models/order.model';
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

  /**
   * Perche' l'ordine e' stato annullato, in italiano.
   *
   * Prima il checkout diceva solo "scorte non disponibili oppure pagamento
   * rifiutato": due cause diverse, con rimedi diversi, indistinguibili.
   */
  get cancellationMessage(): string {
    return messageForReason(this.order?.cancellationReason);
  }

  /** Prodotti la cui immagine non si e' caricata: si ripiega sulla banda. */
  private readonly brokenImages = new Set<number>();

  showPhoto(item: CartItem): boolean {
    return !!item.imageUrl && !this.brokenImages.has(item.productId);
  }

  onImageError(item: CartItem): void {
    this.brokenImages.add(item.productId);
  }

  /** Stessa banda colorata di catalogo e dettaglio, derivata dal nome. */
  thumbnail(item: CartItem): string {
    const hue = [...item.productName].reduce((acc, char) => acc + char.charCodeAt(0), 0) % 360;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 38) % 360} 68% 46%))`;
  }

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

    // L'immagine serve solo a disegnare il carrello: all'Order Service si
    // manda cio' che l'ordine deve contenere, non lo stato del frontend.
    const items = this.cartService.snapshot.map(({ productId, productName, quantity, unitPrice }) => ({
      productId,
      productName,
      quantity,
      unitPrice,
    }));

    this.orderService
      .createOrder({ items })
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

  changeQuantity(productId: number, quantity: number): void {
    this.cartService.updateQuantity(productId, quantity);
  }

  removeItem(productId: number): void {
    this.cartService.remove(productId);
  }
}
