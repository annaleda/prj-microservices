import { Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';
import { OrderResponse } from '../models/order.model';
import { Product } from '../models/product.model';
import { CartService } from './cart.service';
import { CatalogService } from './catalog.service';

/** Esito di un riordino: cosa e' finito nel carrello e cosa no. */
export interface ReorderOutcome {
  added: number;
  /** Prodotti dell'ordine che non sono piu' a catalogo. */
  unavailable: string[];
}

/**
 * Rimette nel carrello gli articoli di un ordine gia' fatto.
 *
 * Usa i dati **attuali** del catalogo e non quelli congelati nell'ordine:
 * un ordine conserva il prezzo del giorno in cui e' stato fatto, e
 * rimetterlo nel carrello a quel prezzo significherebbe far comprare a una
 * cifra che non e' piu' quella di vendita. Un prodotto tolto dal catalogo
 * non si puo' riordinare, e chi riordina deve saperlo.
 */
@Injectable({ providedIn: 'root' })
export class ReorderService {
  constructor(
    private readonly catalogService: CatalogService,
    private readonly cartService: CartService
  ) {}

  reorder(order: OrderResponse): Observable<ReorderOutcome> {
    return this.catalogService.getProducts().pipe(
      map((products) => {
        const byId = new Map<number, Product>(products.map((p) => [p.id, p]));
        const outcome: ReorderOutcome = { added: 0, unavailable: [] };

        for (const item of order.items) {
          const product = byId.get(item.productId);
          if (!product) {
            outcome.unavailable.push(item.productName);
            continue;
          }
          this.cartService.addQuantity(product, item.quantity);
          outcome.added += 1;
        }

        return outcome;
      })
    );
  }
}
