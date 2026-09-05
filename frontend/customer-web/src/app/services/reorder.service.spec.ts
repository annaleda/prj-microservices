import { of, throwError } from 'rxjs';
import { CartService } from './cart.service';
import { ReorderService } from './reorder.service';
import { OrderResponse } from '../models/order.model';
import { Product } from '../models/product.model';

function product(id: number, name: string, price: number): Product {
  return {
    id,
    name,
    description: null,
    price,
    sku: `SKU-${id}`,
    imageUrl: `https://example.com/${id}.jpg`,
    categoryId: 1,
    categoryName: 'Electronics',
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };
}

function order(items: Array<[number, string, number, number]>): OrderResponse {
  return {
    id: 1,
    customerEmail: 'customer@example.com',
    status: 'CONFIRMED',
    totalAmount: 0,
    items: items.map(([productId, productName, quantity, unitPrice]) => ({
      productId,
      productName,
      quantity,
      unitPrice,
      lineTotal: quantity * unitPrice,
    })),
    cancellationReason: null,
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };
}

describe('ReorderService', () => {
  let cart: CartService;

  beforeEach(() => {
    localStorage.clear();
    cart = new CartService();
  });
  afterAll(() => localStorage.clear());

  function serviceWith(products: Product[]): ReorderService {
    return new ReorderService({ getProducts: () => of(products) } as never, cart);
  }

  it('rimette nel carrello gli articoli con le quantita dell ordine', (done) => {
    const service = serviceWith([product(5, 'Mouse', 29.9), product(9, 'Libro', 13.5)]);

    service.reorder(order([[5, 'Mouse', 2, 29.9], [9, 'Libro', 1, 13.5]])).subscribe((outcome) => {
      expect(outcome.added).toBe(2);
      expect(outcome.unavailable).toEqual([]);
      expect(cart.snapshot.map((i) => [i.productId, i.quantity])).toEqual([[5, 2], [9, 1]]);
      done();
    });
  });

  it('usa il prezzo di oggi e non quello congelato nell ordine', (done) => {
    // Un ordine conserva il prezzo del giorno in cui e' stato fatto.
    // Rimetterlo nel carrello a quel prezzo farebbe comprare a una cifra
    // che non e' piu' quella di vendita.
    const service = serviceWith([product(5, 'Mouse', 34.9)]);

    service.reorder(order([[5, 'Mouse', 1, 29.9]])).subscribe(() => {
      expect(cart.snapshot[0].unitPrice).toBe(34.9);
      done();
    });
  });

  it('salta i prodotti non piu a catalogo e li segnala', (done) => {
    const service = serviceWith([product(5, 'Mouse', 29.9)]);

    service.reorder(order([[5, 'Mouse', 1, 29.9], [99, 'Prodotto sparito', 1, 10]])).subscribe(
      (outcome) => {
        expect(outcome.added).toBe(1);
        expect(outcome.unavailable).toEqual(['Prodotto sparito']);
        expect(cart.snapshot.length).toBe(1);
        done();
      }
    );
  });

  it('somma a cio che e gia nel carrello invece di sostituirlo', (done) => {
    const mouse = product(5, 'Mouse', 29.9);
    cart.add(mouse);
    const service = serviceWith([mouse]);

    service.reorder(order([[5, 'Mouse', 2, 29.9]])).subscribe(() => {
      expect(cart.snapshot[0].quantity).toBe(3);
      done();
    });
  });

  it('se il catalogo non risponde, il riordino fallisce senza toccare il carrello', (done) => {
    const service = new ReorderService(
      { getProducts: () => throwError(() => new Error('catalogo giu')) } as never,
      cart
    );

    service.reorder(order([[5, 'Mouse', 1, 29.9]])).subscribe({
      error: () => {
        expect(cart.snapshot.length).toBe(0);
        done();
      },
    });
  });
});
