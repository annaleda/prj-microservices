export interface PaymentRequest {
  orderId: number;
  amount: number;
  method: string;
}

export type PaymentStatus = 'COMPLETED' | 'FAILED';

export interface PaymentResponse {
  id: number;
  orderId: number;
  amount: number;
  method: string;
  status: PaymentStatus;
  createdAt: string;
}
