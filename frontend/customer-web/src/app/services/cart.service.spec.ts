import { CartService } from './cart.service';
import { Product } from '../models/product.model';

const mouse: Product = {
  id: 1,
  name: 'Wireless Mouse',
  description: 'Ergonomic mouse',
  price: 29.9,
  sku: 'SKU-001',
  imageUrl: null,
  categoryId: 1,
  categoryName: 'Electronics',
  createdAt: '2026-09-05T00:00:00Z',
  updatedAt: '2026-09-05T00:00:00Z',
};

describe('CartService', () => {
  beforeEach(() => localStorage.clear());
  afterAll(() => localStorage.clear());

  it('accumula piu' + "'" + ' unita dello stesso prodotto', () => {
    const cart = new CartService();
    cart.add(mouse);
    cart.add(mouse);

    expect(cart.snapshot.length).toBe(1);
    expect(cart.snapshot[0].quantity).toBe(2);
  });

  it('sopravvive al ricaricamento della pagina causato dal login', () => {
    const cart = new CartService();
    cart.add(mouse);

    // Il login con Keycloak e' un redirect: l'applicazione riparte da
    // zero. Una nuova istanza e' esattamente cio' che accade al ritorno.
    const afterLoginRedirect = new CartService();

    expect(afterLoginRedirect.snapshot).toEqual(cart.snapshot);
    expect(afterLoginRedirect.snapshot[0].productName).toBe('Wireless Mouse');
  });

  it('una volta svuotato non riappare al ricaricamento', () => {
    const cart = new CartService();
    cart.add(mouse);
    cart.clear();

    expect(new CartService().snapshot).toEqual([]);
  });

  it('riparte da vuoto se in memoria c e' + "'" + ' spazzatura', () => {
    localStorage.setItem('polyglot-commerce.cart', 'non-e-json');

    expect(new CartService().snapshot).toEqual([]);
  });
});
