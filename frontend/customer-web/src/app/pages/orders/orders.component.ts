import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { OrderItemResponse, OrderResponse, OrderStatus } from '../../models/order.model';
import { Product } from '../../models/product.model';
import { AuthService } from '../../services/auth.service';
import { CatalogService } from '../../services/catalog.service';
import { OrderService } from '../../services/order.service';
import { ReorderService } from '../../services/reorder.service';

@Component({
  selector: 'app-orders',
  templateUrl: './orders.component.html',
  styleUrls: ['./orders.component.scss'],
})
export class OrdersComponent implements OnInit {
  orders: OrderResponse[] = [];
  loading = true;
  /** Valorizzato quando la lista non e' leggibile (sessione scaduta, servizio giu'). */
  error: string | null = null;

  /** Esito dell'ultimo riordino, da mostrare in cima alla pagina. */
  notice: string | null = null;
  reordering: number | null = null;

  /**
   * Immagini del catalogo, indicizzate per id.
   *
   * Servono solo come ripiego per gli **ordini vecchi**: da quando
   * l'immagine viene conservata nella riga d'ordine, l'ordine se la porta
   * dietro e non serve chiederla a nessuno. Gli ordini fatti prima non
   * ce l'hanno, e per quelli si guarda se il prodotto e' ancora a
   * catalogo. Una richiesta sola per l'intera pagina, non una per riga.
   */
  private images = new Map<number, string | null>();
  private brokenImages = new Set<number>();

  constructor(
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

    forkJoin({
      orders: this.orderService.getOrders(),
      products: this.catalogService.getProducts(),
    }).subscribe({
      next: ({ orders, products }) => {
        this.images = new Map<number, string | null>(
          products.map((p: Product) => [p.id, p.imageUrl])
        );
        // Gli ordini annullati non compaiono nello storico: un acquisto che
        // non e' andato a buon fine non e' un ordine da ricordare, e
        // lasciarlo in elenco fa sembrare comprato qualcosa che non lo e'.
        // Restano visibili al personale interno, dalla console admin.
        this.orders = orders.filter((order) => order.status !== 'CANCELLED');
        this.loading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.error =
          err.status === 401
            ? 'La sessione e\u0027 scaduta: accedi di nuovo per vedere i tuoi ordini.'
            : 'Non e\u0027 stato possibile caricare gli ordini.';
      },
    });
  }

  /**
   * Primo articolo dell'ordine: e' quello che si mostra in elenco.
   * Puo' mancare — un ordine senza articoli non dovrebbe esistere, ma il
   * template non deve rompersi se capita.
   */
  firstItem(order: OrderResponse): OrderItemResponse | undefined {
    return order.items[0];
  }

  photo(order: OrderResponse): string | null {
    const item = this.firstItem(order);
    if (!item || this.brokenImages.has(item.productId)) {
      return null;
    }
    // L'immagine dell'ordine viene prima: e' quella del momento in cui e'
    // stato comprato, e c'e' anche se il prodotto non e' piu' a catalogo.
    return item.imageUrl ?? this.images.get(item.productId) ?? null;
  }

  onImageError(order: OrderResponse): void {
    const item = this.firstItem(order);
    if (item) {
      this.brokenImages.add(item.productId);
    }
  }

  /** Stessa banda colorata del catalogo, per gli articoli senza foto. */
  thumbnail(order: OrderResponse): string {
    const name = this.firstItem(order)?.productName ?? '';
    const hue = [...name].reduce((acc, char) => acc + char.charCodeAt(0), 0) % 360;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 38) % 360} 68% 46%))`;
  }

  /** Rimette nel carrello gli articoli dell'ordine e porta al checkout. */
  reorder(order: OrderResponse): void {
    this.notice = null;
    this.reordering = order.id;

    this.reorderService.reorder(order).subscribe({
      next: (outcome) => {
        this.reordering = null;
        if (outcome.added === 0) {
          this.notice = `Nessun articolo dell'ordine #${order.id} e' ancora disponibile.`;
          return;
        }
        if (outcome.unavailable.length) {
          // Si va comunque al carrello, ma dicendo cosa manca: scoprirlo
          // alla cassa sarebbe peggio.
          this.notice = `Articoli non piu' disponibili e non aggiunti: ${outcome.unavailable.join(', ')}.`;
        }
        this.router.navigate(['/checkout']);
      },
      error: () => {
        this.reordering = null;
        this.notice = 'Non e\u0027 stato possibile riordinare: catalogo non raggiungibile.';
      },
    });
  }

  /**
   * L'elenco mostra solo gli ordini di chi e' collegato (lo garantisce il
   * backend) e, fra quelli, solo quelli non annullati.
   */
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

  itemCount(order: OrderResponse): number {
    return order.items.reduce((sum, item) => sum + item.quantity, 0);
  }
}
