export type OrderStatus = 'CREATED' | 'CONFIRMED' | 'CANCELLED';

/** Perche' la saga ha annullato l'ordine (Integration Service). */
export type CancellationReason = 'INVENTORY_REJECTED' | 'PAYMENT_FAILED' | 'SAGA_STATE_LOST';

export interface OrderItem {
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

export interface Order {
  id: number;
  customerEmail: string;
  status: OrderStatus;
  totalAmount: number;
  items: OrderItem[];
  /** Valorizzato solo sugli ordini annullati dalla saga. */
  cancellationReason: CancellationReason | null;
  createdAt: string;
  updatedAt: string;
}
