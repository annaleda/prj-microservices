import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { deleteProduct, getProducts } from '../api/catalogApi';
import { getInventory } from '../api/inventoryApi';
import { PencilIcon, PlusIcon, TrashIcon } from '../components/icons';
import { Product } from '../types/product';

export default function ProductListPage() {
  const [products, setProducts] = useState<Product[]>([]);
  // Le scorte vivono in un altro servizio: si leggono a parte, un prodotto
  // alla volta, e si tengono qui indicizzate per id.
  const [stock, setStock] = useState<Record<number, number | null>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadProducts = () => {
    setLoading(true);
    getProducts()
      .then(async (list) => {
        setProducts(list);
        // Il magazzino non ha un endpoint per leggere piu' prodotti in una
        // volta: con un catalogo grande questo diventerebbe un problema, e
        // la risposta sarebbe una lettura in blocco lato servizio, non un
        // ciclo piu' furbo qui.
        const entries = await Promise.all(
          list.map(async (p) => {
            try {
              const inventory = await getInventory(p.id);
              return [p.id, inventory ? inventory.quantityAvailable : null] as const;
            } catch {
              // Le scorte illeggibili non devono impedire di vedere il
              // catalogo: la colonna resta vuota.
              return [p.id, null] as const;
            }
          })
        );
        setStock(Object.fromEntries(entries));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadProducts();
  }, []);

  const handleDelete = async (id: number) => {
    if (!confirm('Eliminare questo prodotto?')) {
      return;
    }
    try {
      await deleteProduct(id);
      loadProducts();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <section>
      <div className="toolbar">
        <h1>Prodotti</h1>
        <Link
          to="/products/new"
          className="icon-button icon-button--primary"
          title="Nuovo prodotto"
          aria-label="Nuovo prodotto"
        >
          <PlusIcon />
        </Link>
      </div>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Caricamento...</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Nome</th>
              <th>Categoria</th>
              <th>SKU</th>
              <th>Prezzo</th>
              <th>Scorte</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {products.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>{p.categoryName}</td>
                <td>{p.sku}</td>
                <td>{p.price.toFixed(2)} &euro;</td>
                <td>
                  {stock[p.id] === null || stock[p.id] === undefined ? (
                    <span title="Il magazzino non ha ancora una riga per questo prodotto">&mdash;</span>
                  ) : stock[p.id] === 0 ? (
                    // Zero pezzi non e' un dettaglio: il prodotto e' in
                    // vetrina ma ogni ordine che lo contiene verra'
                    // annullato dalla saga.
                    <span className="stock stock--empty" title="Non ordinabile: ogni ordine verrebbe annullato">
                      0 &middot; non ordinabile
                    </span>
                  ) : (
                    stock[p.id]
                  )}
                </td>
                <td className="actions">
                  {/* Il testo non sparisce: e' nel `title`, che il browser
                      mostra passandoci sopra, e nell'`aria-label` per chi
                      l'icona non la vede. */}
                  <Link
                    to={`/products/${p.id}/edit`}
                    className="icon-button"
                    title={`Modifica ${p.name}`}
                    aria-label={`Modifica ${p.name}`}
                  >
                    <PencilIcon />
                  </Link>
                  <button
                    className="icon-button icon-button--danger"
                    onClick={() => handleDelete(p.id)}
                    title={`Elimina ${p.name}`}
                    aria-label={`Elimina ${p.name}`}
                  >
                    <TrashIcon />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
