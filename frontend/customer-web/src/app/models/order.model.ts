export interface OrderItemInput {
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
}

/** L'intestatario non si dichiara piu': lo ricava il backend dal token. */
export interface OrderRequest {
  items: OrderItemInput[];
}

export interface OrderItemResponse extends OrderItemInput {
  lineTotal: number;
}

export type OrderStatus = 'CREATED' | 'CONFIRMED' | 'CANCELLED';

export interface OrderResponse {
  id: number;
  customerEmail: string;
  status: OrderStatus;
  totalAmount: number;
  items: OrderItemResponse[];
  createdAt: string;
  updatedAt: string;
}
