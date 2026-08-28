import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { deleteProduct, getProducts } from '../api/catalogApi';
import { Product } from '../types/product';

export default function ProductListPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadProducts = () => {
    setLoading(true);
    getProducts()
      .then(setProducts)
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
        <Link to="/products/new" className="button">
          + Nuovo prodotto
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
                <td className="actions">
                  <Link to={`/products/${p.id}/edit`}>Modifica</Link>
                  <button onClick={() => handleDelete(p.id)}>Elimina</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
