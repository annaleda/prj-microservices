import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';
import { CheckoutComponent } from './checkout.component';
import { CancellationReason, OrderResponse } from '../../models/order.model';
import { Product } from '../../models/product.model';
import { AuthService } from '../../services/auth.service';
import { CartService } from '../../services/cart.service';
import { OrderService } from '../../services/order.service';

/**
 * Cio' che il checkout dice quando la saga annulla l'ordine.
 *
 * Prima diceva sempre "scorte non disponibili oppure pagamento rifiutato":
 * due cause con rimedi opposti (togliere un articolo / riprovare a pagare),
 * indistinguibili per chi le legge. Questi test fissano il fatto che ogni
 * motivo ha il suo messaggio, e che uno sconosciuto non produce una pagina
 * vuota ma il vecchio testo generico.
 */
describe('CheckoutComponent, esito annullato', () => {
  const authStub = { isLoggedIn: true, username: 'customer', login: () => undefined };

  const webcam: Product = {
    id: 13,
    name: 'Webcam 4K',
    description: 'Webcam con microfono integrato',
    price: 89,
    sku: 'SKU-CAM-01',
    imageUrl: null,
    categoryId: 1,
    categoryName: 'Electronics',
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };

  function cancelledOrder(reason: CancellationReason | null): OrderResponse {
    return {
      id: 42,
      customerEmail: 'customer@example.com',
      status: 'CANCELLED',
      totalAmount: 89,
      items: [{ productId: 13, productName: 'Webcam 4K', quantity: 1, unitPrice: 89, lineTotal: 89 }],
      cancellationReason: reason,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    };
  }

  /** Carrello con un articolo, ordine creato e poi annullato dalla saga. */
  function checkoutCancelledFor(reason: CancellationReason | null): ComponentFixture<CheckoutComponent> {
    const order = cancelledOrder(reason);
    const orderServiceStub = {
      createOrder: () => of({ ...order, status: 'CREATED' as const, cancellationReason: null }),
      awaitSagaOutcome: () => of(order),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CommonModule, RouterTestingModule],
      declarations: [CheckoutComponent],
      providers: [
        CartService,
        { provide: AuthService, useValue: authStub },
        { provide: OrderService, useValue: orderServiceStub },
      ],
    });

    TestBed.inject(CartService).add(webcam);

    const fixture = TestBed.createComponent(CheckoutComponent);
    fixture.componentInstance.placeOrder();
    fixture.detectChanges();
    return fixture;
  }

  function errorText(fixture: ComponentFixture<CheckoutComponent>): string {
    return (fixture.nativeElement as HTMLElement).querySelector('.checkout__error')?.textContent ?? '';
  }

  beforeEach(() => localStorage.clear());
  afterAll(() => localStorage.clear());

  it('per le scorte insufficienti dice di ridurre le quantita', () => {
    const text = errorText(checkoutCancelledFor('INVENTORY_REJECTED'));

    expect(text).toContain('non sono disponibili a magazzino');
    expect(text).toContain('ridurre');
    expect(text).not.toContain('pagamento');
  });

  it('per il pagamento rifiutato dice che si puo' + "'" + ' riprovare', () => {
    const text = errorText(checkoutCancelledFor('PAYMENT_FAILED'));

    expect(text).toContain('pagamento');
    expect(text).toContain('riprovare');
    expect(text).not.toContain('magazzino');
  });

  it('senza motivo registrato resta il messaggio generico', () => {
    // Gli ordini annullati prima che il codice esistesse: meglio il testo
    // vago di prima che una riga vuota o un motivo inventato.
    const text = errorText(checkoutCancelledFor(null));

    expect(text).toContain('Scorte non disponibili oppure pagamento rifiutato');
  });

  it('il comando di rimozione resta descritto anche senza testo', () => {
    // Sostituita la scritta "Rimuovi" con un cestino: un pulsante di sola
    // icona senza nome accessibile e' muto per chi non la vede, e senza
    // `title` non dice nulla nemmeno passandoci sopra col mouse.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CommonModule, RouterTestingModule],
      declarations: [CheckoutComponent],
      providers: [
        CartService,
        { provide: AuthService, useValue: authStub },
        { provide: OrderService, useValue: { createOrder: () => of(null), awaitSagaOutcome: () => of(null) } },
      ],
    });
    TestBed.inject(CartService).add(webcam);

    const fixture = TestBed.createComponent(CheckoutComponent);
    fixture.detectChanges();

    const remove = (fixture.nativeElement as HTMLElement).querySelector('.checkout__remove');
    expect(remove).toBeTruthy();
    expect(remove!.querySelector('svg')).toBeTruthy();
    expect(remove!.getAttribute('title')).toContain('Webcam 4K');
    expect(remove!.getAttribute('aria-label')).toContain('Webcam 4K');
  });

  it('lascia il carrello intatto, cosi' + "'" + ' si puo' + "'" + ' correggere e riprovare', () => {
    checkoutCancelledFor('PAYMENT_FAILED');

    // Il carrello si svuota solo su CONFIRMED: chi ha subito un rifiuto
    // deve ritrovare la sua spesa.
    expect(TestBed.inject(CartService).snapshot.length).toBe(1);
  });
});
