import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { OrderItemResponse, OrderResponse, OrderStatus, cancellationMessage } from '../../models/order.model';
import { Product } from '../../models/product.model';
import { AuthService } from '../../services/auth.service';
import { CatalogService } from '../../services/catalog.service';
import { OrderService } from '../../services/order.service';
import { ReorderService } from '../../services/reorder.service';

/**
 * Dettaglio di un ordine gia' fatto: tutti gli articoli, non solo il
 * primo, con le quantita' e i prezzi ai quali sono stati comprati.
 *
 * I prezzi sono quelli **dell'ordine** e non quelli attuali del catalogo:
 * e' una ricevuta, deve dire quanto e' stato pagato. Il riordino invece
 * usa i prezzi di oggi, perche' quello e' un acquisto nuovo.
 */
@Component({
  selector: 'app-order-detail',
  templateUrl: './order-detail.component.html',
  styleUrls: ['./order-detail.component.scss'],
})
export class OrderDetailComponent implements OnInit {
  order: OrderResponse | null = null;
  loading = true;
  error: string | null = null;
  notice: string | null = null;
  reordering = false;

  private images = new Map<number, string | null>();
  private brokenImages = new Set<number>();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly orderService: OrderService,
    private readonly catalogService: CatalogService,
    private readonly reorderService: ReorderService,
    private readonly router: Router,
    readonly auth: AuthService
  ) {}

  ngOnInit(): void {
    if (!this.auth.isLoggedIn) {
      this.loading = false;
      return;
    }

    this.route.paramMap
      .pipe(
        switchMap((params) =>
          forkJoin({
            order: this.orderService.getOrder(Number(params.get('id'))),
            products: this.catalogService.getProducts(),
          })
        )
      )
      .subscribe({
        next: ({ order, products }) => {
          this.order = order;
          this.images = new Map<number, string | null>(
            products.map((p: Product) => [p.id, p.imageUrl])
          );
          this.loading = false;
        },
        error: (err: HttpErrorResponse) => {
          this.loading = false;
          this.error =
            err.status === 404
              ? 'Ordine non trovato.'
              : err.status === 403
              ? 'Questo ordine non e\u0027 tuo.'
              : err.status === 401
              ? 'La sessione e\u0027 scaduta: accedi di nuovo.'
              : 'Non e\u0027 stato possibile caricare l\u0027ordine.';
        },
      });
  }

  /**
   * Immagine dell'articolo: prima quella conservata nell'ordine (e' quella
   * del momento dell'acquisto e resta anche se il prodotto sparisce dal
   * catalogo), poi come ripiego quella del catalogo per gli ordini fatti
   * prima che venisse conservata, infine la banda colorata.
   */
  photo(item: OrderItemResponse): string | null {
    if (this.brokenImages.has(item.productId)) {
      return null;
    }
    return item.imageUrl ?? this.images.get(item.productId) ?? null;
  }

  onImageError(item: OrderItemResponse): void {
    this.brokenImages.add(item.productId);
  }

  thumbnail(item: OrderItemResponse): string {
    const hue = [...item.productName].reduce((acc, char) => acc + char.charCodeAt(0), 0) % 360;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 38) % 360} 68% 46%))`;
  }

  statusLabel(status: OrderStatus): string {
    switch (status) {
      case 'CONFIRMED':
        return 'Confermato';
      case 'CANCELLED':
        return 'Annullato';
      default:
        return 'In lavorazione';
    }
  }

  get cancellationMessage(): string {
    return cancellationMessage(this.order?.cancellationReason);
  }

  itemCount(): number {
    return this.order?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0;
  }

  reorder(): void {
    if (!this.order) {
      return;
    }
    this.notice = null;
    this.reordering = true;

    this.reorderService.reorder(this.order).subscribe({
      next: (outcome) => {
        this.reordering = false;
        if (outcome.added === 0) {
          this.notice = 'Nessun articolo di questo ordine e\u0027 ancora disponibile.';
          return;
        }
        if (outcome.unavailable.length) {
          this.notice = `Articoli non piu' disponibili e non aggiunti: ${outcome.unavailable.join(', ')}.`;
        }
        this.router.navigate(['/checkout']);
      },
      error: () => {
        this.reordering = false;
        this.notice = 'Non e\u0027 stato possibile riordinare: catalogo non raggiungibile.';
      },
    });
  }
}
