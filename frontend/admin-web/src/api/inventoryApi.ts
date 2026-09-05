import { authHeaders } from './authToken';

const BASE_URL = '/api/inventory';

export interface Inventory {
  productId: number;
  quantityAvailable: number;
  quantityReserved: number;
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Richiesta fallita (${response.status}): ${body || response.statusText}`);
  }
  return (await response.json()) as T;
}

/**
 * Scorte di un prodotto, oppure `null` se il magazzino non ne ha ancora
 * una riga.
 *
 * Il 404 non e' un errore da mostrare: capita sui prodotti creati prima
 * che il Catalog Service annunciasse i prodotti nuovi con
 * `product.created`, e la risposta giusta e' trattarli come "scorte non
 * ancora dichiarate", non far fallire la pagina.
 */
export async function getInventory(productId: number): Promise<Inventory | null> {
  const response = await fetch(`${BASE_URL}/${productId}`, { headers: authHeaders() });
  if (response.status === 404) {
    return null;
  }
  return handleResponse<Inventory>(response);
}

/**
 * Dichiara le unita' disponibili. Si manda il totale, non una variazione:
 * e' cio' che fa chi conta quello che ha sullo scaffale, ed evita che una
 * richiesta ripetuta raddoppi le scorte. Le unita' gia' riservate da
 * ordini in corso non vengono toccate.
 */
export function setStock(productId: number, quantityAvailable: number): Promise<Inventory> {
  return fetch(`${BASE_URL}/${productId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ quantityAvailable }),
  }).then((r) => handleResponse<Inventory>(r));
}
