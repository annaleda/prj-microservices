import { Component, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { OrderResponse, OrderStatus } from '../../models/order.model';
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
        // Gli ordini annullati non compaiono nello storico: un acquisto che
        // non e' andato a buon fine non e' un ordine da ricordare, e
        // lasciarlo in elenco fa sembrare comprato qualcosa che non lo e'.
        //
        // Il cliente l'esito lo vede comunque, al momento del checkout e
        // con il motivo per esteso: qui si toglie il residuo, non
        // l'informazione. L'ordine resta nel database e resta visibile al
        // personale interno, che deve poterci ragionare sopra.
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
