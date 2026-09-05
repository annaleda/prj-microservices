import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { getOrders } from '../api/ordersApi';
import { CancellationReason, Order } from '../types/order';

/** Etichetta italiana del motivo di annullamento deciso dalla saga. */
function reasonLabel(reason: CancellationReason | null): string {
  switch (reason) {
    case 'INVENTORY_REJECTED':
      return 'Scorte insufficienti';
    case 'PAYMENT_FAILED':
      return 'Pagamento rifiutato';
    case 'SAGA_STATE_LOST':
      return 'Problema tecnico';
    default:
      return 'Motivo non registrato';
  }
}

function statusLabel(order: Order): string {
  switch (order.status) {
    case 'CONFIRMED':
      return 'Confermato';
    case 'CANCELLED':
      return 'Annullato';
    default:
      return 'In lavorazione';
  }
}

/**
 * Riepilogo ordini per il personale interno.
 *
 * E' l'unico posto in cui si vedono gli ordini **non andati a buon fine**:
 * il cliente non li ha piu' nel proprio storico, ma qui servono, con il
 * cliente e gli articoli accanto al motivo — un ordine perso per
 * "Scorte insufficienti" dice esattamente quale prodotto rifornire.
 */
export default function OrderListPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [onlyFailed, setOnlyFailed] = useState(false);

  useEffect(() => {
    getOrders()
      .then(setOrders)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  const failedCount = useMemo(
    () => orders.filter((o) => o.status === 'CANCELLED').length,
    [orders]
  );

  const visible = onlyFailed ? orders.filter((o) => o.status === 'CANCELLED') : orders;

  return (
    <section>
      <div className="toolbar">
        <h1>Ordini</h1>
        <label className="filter">
          <input
            type="checkbox"
            checked={onlyFailed}
            onChange={(e) => setOnlyFailed(e.target.checked)}
          />
          Solo non riusciti ({failedCount})
        </label>
      </div>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Caricamento...</p>
      ) : visible.length === 0 ? (
        <p>{onlyFailed ? 'Nessun ordine non riuscito.' : 'Nessun ordine.'}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Ordine</th>
              <th>Data</th>
              <th>Cliente</th>
              <th>Articoli</th>
              <th>Totale</th>
              <th>Stato</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((order) => (
              <tr key={order.id}>
                <td>#{order.id}</td>
                <td>{new Date(order.createdAt).toLocaleString('it-IT')}</td>
                <td>
                  {/* Chi ha provato a comprare: senza, un ordine perso e' un
                      numero e basta, e non si puo' avvisare nessuno. */}
                  {order.customerEmail}
                </td>
                <td>
                  {order.items.map((item) => (
                    <div key={item.productId} className="order-item">
                      <span className="order-item__qty">{item.quantity}&times;</span>{' '}
                      {item.productName}
                    </div>
                  ))}
                </td>
                <td className="order-total">{order.totalAmount.toFixed(2)} &euro;</td>
                <td>
                  <span className={`status status--${order.status.toLowerCase()}`}>
                    {statusLabel(order)}
                  </span>
                  {order.status === 'CANCELLED' && (
                    <span className="status__reason">{reasonLabel(order.cancellationReason)}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {failedCount > 0 && !onlyFailed && (
        <p className="hint">
          Gli ordini annullati per scorte insufficienti indicano prodotti da rifornire:
          le scorte si dichiarano dalla <Link to="/">scheda del prodotto</Link>.
        </p>
      )}
    </section>
  );
}
