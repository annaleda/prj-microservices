import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { createProduct, getCategories, getProduct, updateProduct } from '../api/catalogApi';
import { Category } from '../types/category';
import { ProductInput } from '../types/product';

const emptyForm: ProductInput = {
  name: '',
  description: '',
  price: 0,
  sku: '',
  categoryId: 0,
};

export default function ProductFormPage() {
  const { id } = useParams();
  const isEdit = Boolean(id);
  const navigate = useNavigate();

  const [form, setForm] = useState<ProductInput>(emptyForm);
  const [categories, setCategories] = useState<Category[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    getCategories()
      .then(setCategories)
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    if (!isEdit) {
      return;
    }
    getProduct(Number(id))
      .then((product) =>
        setForm({
          name: product.name,
          description: product.description ?? '',
          price: product.price,
          sku: product.sku,
          categoryId: product.categoryId ?? 0,
        })
      )
      .catch((e: Error) => setError(e.message));
  }, [id, isEdit]);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      if (isEdit) {
        await updateProduct(Number(id), form);
      } else {
        await createProduct(form);
      }
      navigate('/');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section>
      <h1>{isEdit ? 'Modifica prodotto' : 'Nuovo prodotto'}</h1>

      {error && <p className="error">{error}</p>}

      <form onSubmit={handleSubmit} className="form">
        <label>
          Nome
          <input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>

        <label>
          Descrizione
          <textarea
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </label>

        <label>
          SKU
          <input required value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
        </label>

        <label>
          Prezzo (&euro;)
          <input
            required
            type="number"
            step="0.01"
            min="0"
            value={form.price}
            onChange={(e) => setForm({ ...form, price: Number(e.target.value) })}
          />
        </label>

        <label>
          Categoria
          <select
            required
            value={form.categoryId}
            onChange={(e) => setForm({ ...form, categoryId: Number(e.target.value) })}
          >
            <option value={0} disabled>
              Seleziona una categoria
            </option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>

        <div className="form-actions">
          <button type="submit" disabled={submitting}>
            {isEdit ? 'Salva' : 'Crea'}
          </button>
          <button type="button" onClick={() => navigate('/')}>
            Annulla
          </button>
        </div>
      </form>
    </section>
  );
}
