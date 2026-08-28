import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { CartItem } from '../models/cart-item.model';
import { Product } from '../models/product.model';

@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly itemsSubject = new BehaviorSubject<CartItem[]>([]);
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
      });
    }
    this.itemsSubject.next(items);
  }

  remove(productId: number): void {
    this.itemsSubject.next(this.itemsSubject.value.filter((item) => item.productId !== productId));
  }

  clear(): void {
    this.itemsSubject.next([]);
  }
}
