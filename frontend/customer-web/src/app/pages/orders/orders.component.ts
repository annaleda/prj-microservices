import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { cancellationLabel as labelForReason, OrderResponse, OrderStatus } from '../../models/order.model';
import { AuthService } from '../../services/auth.service';
import { OrderService } from '../../services/order.service';

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

  constructor(
    private readonly orderService: OrderService,
    readonly auth: AuthService
  ) {}

  ngOnInit(): void {
    if (!this.auth.isLoggedIn) {
      this.loading = false;
      return;
    }

    this.orderService.getOrders().subscribe({
      next: (orders) => {
        this.orders = orders;
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

  /** L'elenco mostra solo gli ordini di chi e' collegato: lo garantisce il backend. */
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

  /** Etichetta breve sotto lo stato degli ordini annullati. */
  cancellationLabel(order: OrderResponse): string {
    return labelForReason(order.cancellationReason);
  }

  itemCount(order: OrderResponse): number {
    return order.items.reduce((sum, item) => sum + item.quantity, 0);
  }
}
