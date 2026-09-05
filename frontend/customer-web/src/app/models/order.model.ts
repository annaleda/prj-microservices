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

/**
 * Perche' la saga ha annullato l'ordine. Il codice arriva dall'Integration
 * Service (evento order.cancelled) e viene registrato dall'Order Service.
 *
 * Gli ordini annullati prima che questo codice esistesse non ne hanno:
 * per quelli resta il messaggio generico.
 */
export type CancellationReason = 'INVENTORY_REJECTED' | 'PAYMENT_FAILED' | 'SAGA_STATE_LOST';

export interface OrderResponse {
  id: number;
  customerEmail: string;
  status: OrderStatus;
  totalAmount: number;
  items: OrderItemResponse[];
  cancellationReason: CancellationReason | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Traduzione del motivo per il cliente.
 *
 * Il dettaglio tecnico (quale prodotto, quante unita' mancavano) resta nei
 * log e nell'evento: qui serve solo che chi ha ordinato capisca se puo'
 * riprovare, e con quale correzione.
 */
export function cancellationMessage(reason: CancellationReason | null | undefined): string {
  switch (reason) {
    case 'INVENTORY_REJECTED':
      return "Uno o piÃ¹ articoli non sono disponibili a magazzino nella quantitÃ  richiesta. Prova a ridurre le quantitÃ  o a rimuovere l'articolo.";
    case 'PAYMENT_FAILED':
      return "Il pagamento Ã¨ stato rifiutato. Gli articoli sono stati rimessi a disposizione: puoi riprovare.";
    case 'SAGA_STATE_LOST':
      return "Si Ã¨ verificato un problema tecnico durante l'elaborazione e l'ordine Ã¨ stato annullato per sicurezza. Nessun importo Ã¨ stato addebitato: puoi riprovare.";
    default:
      return "Scorte non disponibili oppure pagamento rifiutato.";
  }
}

/**
 * Versione breve, per lo storico ordini: in una cella di tabella serve
 * l'etichetta, non la spiegazione. Il testo esteso di
 * {@link cancellationMessage} resta al checkout, dove il cliente sta per
 * decidere se e come riprovare.
 */
export function cancellationLabel(reason: CancellationReason | null | undefined): string {
  switch (reason) {
    case 'INVENTORY_REJECTED':
      return 'Scorte insufficienti';
    case 'PAYMENT_FAILED':
      return 'Pagamento rifiutato';
    case 'SAGA_STATE_LOST':
      return 'Problema tecnico';
    default:
      return '';
  }
}
