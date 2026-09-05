import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createProduct,
  getCategories,
  getProduct,
  updateProduct,
  uploadProductImage,
} from '../api/catalogApi';
import { getInventory, setStock } from '../api/inventoryApi';
import { Category } from '../types/category';
import { ProductInput } from '../types/product';

const emptyForm: ProductInput = {
  name: '',
  description: '',
  price: 0,
  sku: '',
  imageUrl: '',
  categoryId: 0,
};

export default function ProductFormPage() {
  const { id } = useParams();
  const isEdit = Boolean(id);
  const navigate = useNavigate();

  const [form, setForm] = useState<ProductInput>(emptyForm);
  // Le scorte non appartengono al prodotto: stanno nell'Inventory Service,
  // che e' un altro servizio con un altro database. Stanno nello stesso
  // form perche' e' il momento in cui servono, ma restano uno stato a
  // parte e si salvano con una chiamata propria.
  // Due modi di dare un'immagine a un prodotto: un indirizzo esterno
  // (quello che il progetto ha sempre usato, es. loremflickr) oppure un
  // file caricato sull'object storage. Sono alternativi perche' il
  // prodotto ha un solo campo immagine: l'ultimo che si sceglie vince.
  const [imageMode, setImageMode] = useState<'url' | 'upload'>('url');
  const [file, setFile] = useState<File | null>(null);
  const [filePreview, setFilePreview] = useState<string | null>(null);
  const [previewFailed, setPreviewFailed] = useState(false);
  const [stock, setStockValue] = useState<number>(0);
  const [reservedUnits, setReservedUnits] = useState(0);
  const [categories, setCategories] = useState<Category[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!file) {
      setFilePreview(null);
      return;
    }
    const objectUrl = URL.createObjectURL(file);
    setFilePreview(objectUrl);
    // Un object URL tiene il file in memoria finche' non lo si revoca.
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);

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
      .then((product) => {
        setForm({
          name: product.name,
          description: product.description ?? '',
          price: product.price,
          sku: product.sku,
          imageUrl: product.imageUrl ?? '',
          categoryId: product.categoryId ?? 0,
        });
        // Un'immagine caricata ha per indirizzo il nostro endpoint, una
        // esterna un URL assoluto. Aprire il form nella modalita' giusta
        // evita di far ripartire da "indirizzo esterno" chi aveva
        // caricato un file.
        if (product.imageUrl?.startsWith('/api/products/')) {
          setImageMode('upload');
        }
      })
      .catch((e: Error) => setError(e.message));

    getInventory(Number(id))
      .then((inventory) => {
        if (inventory) {
          setStockValue(inventory.quantityAvailable);
          setReservedUnits(inventory.quantityReserved);
        }
      })
      .catch((e: Error) => setError(e.message));
  }, [id, isEdit]);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const product = isEdit ? await updateProduct(Number(id), form) : await createProduct(form);

      // Il file si carica dopo: viene indicizzato sull'id del prodotto,
      // che prima di salvarlo non esiste. Se fallisce, il prodotto resta
      // con l'immagine che aveva.
      if (imageMode === 'upload' && file) {
        try {
          await uploadProductImage(product.id, file);
        } catch (e) {
          setError(
            `Prodotto salvato, ma l'immagine non e' stata caricata: ${(e as Error).message}`
          );
          return;
        }
      }

      // Secondo passo, verso un altro servizio: puo' fallire da solo, e in
      // quel caso il prodotto esiste comunque. Meglio dirlo che far
      // credere che non sia stato salvato niente — l'unica alternativa
      // sarebbe una transazione distribuita fra due database.
      try {
        await setStock(product.id, stock);
      } catch (e) {
        setError(
          `Prodotto salvato, ma le scorte non sono state aggiornate: ${(e as Error).message}. ` +
            "Riapri il prodotto e riprova: finche' le scorte restano a zero, " +
            "ogni ordine che lo contiene verra' annullato."
        );
        return;
      }

      navigate('/');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  // In caricamento si mostra il file scelto, altrimenti l'immagine che il
  // prodotto ha gia'. Un indirizzo esterno puo' sempre rompersi: se non si
  // carica, meglio nessuna anteprima che un riquadro rotto.
  const previewSource = (imageMode === 'upload' && filePreview) || form.imageUrl || null;
  const preview = previewFailed ? null : previewSource;

  useEffect(() => setPreviewFailed(false), [previewSource]);

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

        <fieldset className="image-field">
          <legend>Immagine</legend>

          <div className="image-field__choice">
            <label className="image-field__option">
              <input
                type="radio"
                name="imageMode"
                checked={imageMode === 'url'}
                onChange={() => setImageMode('url')}
              />
              Indirizzo esterno
            </label>
            <label className="image-field__option">
              <input
                type="radio"
                name="imageMode"
                checked={imageMode === 'upload'}
                onChange={() => setImageMode('upload')}
              />
              Carica un file
            </label>
          </div>

          {imageMode === 'url' ? (
            <label>
              URL immagine
              <input
                type="url"
                placeholder="https://…"
                value={form.imageUrl}
                onChange={(e) => setForm({ ...form, imageUrl: e.target.value })}
              />
              <small>
                L&#39;immagine resta sul sito di origine: se quel sito la rimuove, il prodotto
                resta senza foto.
              </small>
            </label>
          ) : (
            <label>
              File immagine
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <small>
                Massimo 5 MB. Il file viene salvato sull&#39;object storage e servito dal
                catalogo; l&#39;immagine attuale resta finche&#39; il caricamento non riesce.
              </small>
            </label>
          )}

          {preview && (
            <div className="image-field__preview">
              <img src={preview} alt="Anteprima" onError={() => setPreviewFailed(true)} />
            </div>
          )}
        </fieldset>

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
          Scorte disponibili
          <input
            required
            type="number"
            step="1"
            min="0"
            value={stock}
            onChange={(e) => setStockValue(Number(e.target.value))}
          />
          <small>
            Un prodotto a zero pezzi non e&#39; ordinabile: la saga rifiuta la riserva e annulla
            l&#39;ordine.
            {reservedUnits > 0 && ` ${reservedUnits} gia' impegnate da ordini in corso, non incluse qui.`}
          </small>
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
