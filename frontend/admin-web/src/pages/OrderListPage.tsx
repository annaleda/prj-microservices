import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { setStock } from '../api/inventoryApi';
import { deleteOrder, getOrders } from '../api/ordersApi';
import { PlusIcon, TrashIcon } from '../components/icons';
import { CancellationReason, Order, OrderItem } from '../types/order';

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
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Articolo per cui e' aperto il campo del rifornimento. */
  const [restocking, setRestocking] = useState<{ orderId: number; productId: number } | null>(null);
  const [quantity, setQuantity] = useState(0);

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

  const handleDelete = async (order: Order) => {
    if (!confirm(`Eliminare l'ordine #${order.id}? L'operazione non si annulla.`)) {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      await deleteOrder(order.id);
      setOrders((previous) => previous.filter((o) => o.id !== order.id));
      setNotice(`Ordine #${order.id} eliminato.`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const openRestock = (order: Order, item: OrderItem) => {
    setNotice(null);
    setRestocking({ orderId: order.id, productId: item.productId });
    // Parte dalla quantita' che era stata ordinata: e' il minimo che
    // servirebbe per non far fallire di nuovo lo stesso ordine.
    setQuantity(item.quantity);
  };

  const confirmRestock = async (item: OrderItem) => {
    setBusy(true);
    setNotice(null);
    try {
      const updated = await setStock(item.productId, quantity);
      setRestocking(null);
      setNotice(`${item.productName}: scorte disponibili impostate a ${updated.quantityAvailable}.`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

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
      {notice && <p className="notice">{notice}</p>}

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
              <th />
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
                      {/* Il rifornimento si offre dove serve: un ordine perso
                          per scorte esaurite dice esattamente quale prodotto
                          rimettere a magazzino. */}
                      {order.status === 'CANCELLED' &&
                        order.cancellationReason === 'INVENTORY_REJECTED' &&
                        (restocking?.orderId === order.id &&
                        restocking?.productId === item.productId ? (
                          <span className="restock">
                            <input
                              type="number"
                              min={0}
                              value={quantity}
                              onChange={(e) => setQuantity(Number(e.target.value))}
                            />
                            <button
                              className="icon-button icon-button--primary"
                              disabled={busy}
                              onClick={() => void confirmRestock(item)}
                              title="Conferma le scorte"
                              aria-label="Conferma le scorte"
                            >
                              <PlusIcon />
                            </button>
                            <button className="link-button" onClick={() => setRestocking(null)}>
                              annulla
                            </button>
                          </span>
                        ) : (
                          <button
                            className="link-button"
                            onClick={() => openRestock(order, item)}
                            title={`Rifornisci ${item.productName}`}
                          >
                            rifornisci
                          </button>
                        ))}
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
                <td className="actions">
                  {/* Solo gli ordini annullati si eliminano: uno confermato
                      e' la traccia di una vendita avvenuta. */}
                  {order.status === 'CANCELLED' && (
                    <button
                      className="icon-button icon-button--danger"
                      disabled={busy}
                      onClick={() => void handleDelete(order)}
                      title={`Elimina l'ordine #${order.id}`}
                      aria-label={`Elimina l'ordine #${order.id}`}
                    >
                      <TrashIcon />
                    </button>
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
