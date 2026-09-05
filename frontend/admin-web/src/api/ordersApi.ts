import { authHeaders } from './authToken';
import { Order } from '../types/order';

const BASE_URL = '/api/orders';

/**
 * Tutti gli ordini.
 *
 * A deciderlo e' il token: l'Order Service restituisce al personale
 * interno l'elenco completo e a un cliente i soli ordini propri. Questa
 * console richiede il ruolo ADMIN, quindi qui arriva tutto — inclusi gli
 * ordini annullati, che il cliente nel suo storico non vede piu'.
 */
export async function getOrders(): Promise<Order[]> {
  const response = await fetch(BASE_URL, { headers: authHeaders() });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Richiesta fallita (${response.status}): ${body || response.statusText}`);
  }
  return (await response.json()) as Order[];
}
