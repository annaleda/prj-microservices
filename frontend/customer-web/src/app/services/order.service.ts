import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, timer } from 'rxjs';
import { filter, switchMap, take, timeout } from 'rxjs/operators';
import { OrderRequest, OrderResponse } from '../models/order.model';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly baseUrl = '/api/orders';

  constructor(private readonly http: HttpClient) {}

  createOrder(request: OrderRequest): Observable<OrderResponse> {
    return this.http.post<OrderResponse>(this.baseUrl, request);
  }

  getOrder(id: number): Observable<OrderResponse> {
    return this.http.get<OrderResponse>(`${this.baseUrl}/${id}`);
  }

  /**
   * L'ordine nasce CREATED e viene poi confermato o annullato dalla saga
   * (Integration Service), in modo asincrono: il frontend non ha un evento
   * da ascoltare, quindi rilegge l'ordine finche' non cambia stato.
   */
  awaitSagaOutcome(id: number, timeoutMs = 20000): Observable<OrderResponse> {
    return timer(0, 1000).pipe(
      switchMap(() => this.getOrder(id)),
      filter((order) => order.status !== 'CREATED'),
      take(1),
      timeout(timeoutMs)
    );
  }
}
