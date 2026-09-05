import { CommonModule } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';
import { OrdersComponent } from './orders.component';
import { OrderResponse, OrderStatus } from '../../models/order.model';
import { AuthService } from '../../services/auth.service';
import { CatalogService } from '../../services/catalog.service';
import { OrderService } from '../../services/order.service';
import { ReorderService } from '../../services/reorder.service';

/**
 * Lo storico non mostra gli ordini annullati: un acquisto non andato a
 * buon fine non e' un ordine da ricordare, e in elenco farebbe sembrare
 * comprato qualcosa che non lo e'. E' una regola che si perde facilmente
 * al primo refactoring della pagina, quindi vale un test.
 */
describe('OrdersComponent', () => {
  const authStub = { isLoggedIn: true, username: 'customer', login: () => undefined };

  function order(id: number, status: OrderStatus): OrderResponse {
    return {
      id,
      customerEmail: 'customer@example.com',
      status,
      totalAmount: 10,
      items: [{ productId: 1, productName: 'Mouse', quantity: 1, unitPrice: 10, lineTotal: 10 }],
      cancellationReason: status === 'CANCELLED' ? 'PAYMENT_FAILED' : null,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    };
  }

  function renderWith(orders: OrderResponse[]): OrdersComponent {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CommonModule, RouterTestingModule],
      declarations: [OrdersComponent],
      providers: [
        { provide: AuthService, useValue: authStub },
        { provide: OrderService, useValue: { getOrders: () => of(orders) } },
        // La pagina chiede al catalogo le immagini dei prodotti (ripiego
        // per gli ordini vecchi, che non se le portano dietro): qui non
        // servono, ma il componente non deve dipendere da HttpClient nei
        // test per una cosa accessoria.
        { provide: CatalogService, useValue: { getProducts: () => of([]) } },
        { provide: ReorderService, useValue: { reorder: () => of({ added: 0, unavailable: [] }) } },
      ],
    });
    const fixture = TestBed.createComponent(OrdersComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('non elenca gli ordini annullati', () => {
    const component = renderWith([
      order(1, 'CONFIRMED'),
      order(2, 'CANCELLED'),
      order(3, 'CREATED'),
    ]);

    expect(component.orders.map((o) => o.id)).toEqual([1, 3]);
  });

  it('con soli ordini annullati lo storico risulta vuoto', () => {
    const component = renderWith([order(1, 'CANCELLED'), order(2, 'CANCELLED')]);

    expect(component.orders.length).toBe(0);
  });

  it('lascia passare quelli ancora in lavorazione', () => {
    // Un ordine appena creato e' in CREATED per qualche secondo, il tempo
    // che la saga si concluda: nasconderlo lo farebbe sparire proprio
    // mentre il cliente lo cerca.
    const component = renderWith([order(7, 'CREATED')]);

    expect(component.orders.map((o) => o.id)).toEqual([7]);
  });
});
