import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { CartItem } from '../models/cart-item.model';
import { Product } from '../models/product.model';

const STORAGE_KEY = 'polyglot-commerce.cart';

/**
 * Carrello del cliente.
 *
 * E' persistito in localStorage e non solo in memoria per una ragione
 * precisa: il login con Keycloak e' un redirect vero e proprio, la
 * pagina lascia l'applicazione e torna ricaricata. Con il carrello in
 * sola memoria, chi arriva alla cassa da disconnesso e accede si
 * ritroverebbe il carrello vuoto — cioe' perderebbe proprio quello che
 * stava cercando di comprare.
 */
@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly itemsSubject = new BehaviorSubject<CartItem[]>(CartService.read());
  readonly items$ = this.itemsSubject.asObservable();

  get snapshot(): CartItem[] {
    return this.itemsSubject.value;
  }

  add(product: Product): void {
    const items = this.itemsSubject.value.map((item) => ({ ...item }));
    const existing = items.find((item) => item.productId === product.id);
    if (existing) {
      existing.quantity += 1;
    } else {
      items.push({
        productId: product.id,
        productName: product.name,
        quantity: 1,
        unitPrice: product.price,
        imageUrl: product.imageUrl,
      });
    }
    this.update(items);
  }

  remove(productId: number): void {
    this.update(this.itemsSubject.value.filter((item) => item.productId !== productId));
  }

  updateQuantity(productId: number, quantity: number): void {
  if (quantity <= 0) {
    this.remove(productId);
    return;
  }

  const items = this.itemsSubject.value.map((item) =>
    item.productId === productId
      ? { ...item, quantity }
      : { ...item }
  );

  this.update(items);
}

  clear(): void {
    this.update([]);
  }

  private update(items: CartItem[]): void {
    this.itemsSubject.next(items);
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      // Spazio esaurito o storage negato (finestra anonima, impostazioni
      // del browser): il carrello continua a funzionare in memoria, non
      // sopravvivera' al login. Meglio che rompere l'applicazione.
    }
  }

  private static read(): CartItem[] {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      const parsed = stored ? JSON.parse(stored) : [];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
}
