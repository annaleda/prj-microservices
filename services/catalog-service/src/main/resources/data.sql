INSERT INTO categories (name, description)
SELECT 'Electronics', 'Dispositivi elettronici e accessori'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Electronics');

INSERT INTO categories (name, description)
SELECT 'Books', 'Libri e pubblicazioni'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Books');

INSERT INTO categories (name, description)
SELECT 'Clothing', 'Abbigliamento e accessori moda'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Clothing');

-- Prodotti dimostrativi. Le foto arrivano da un servizio pubblico di
-- immagini a tema (loremflickr, variante /all perche' i tag devono
-- corrispondere tutti): il progetto non gestisce file binari,
-- il prodotto memorizza solo l'indirizzo dell'immagine.

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Laptop 14"', 'Ultrabook da 14 pollici, 16 GB di RAM, SSD da 512 GB', 999.99, 'SKU-LAPTOP-14', 'https://loremflickr.com/600/400/laptop,computer/all', c.id, now(), now()
FROM categories c WHERE c.name = 'Electronics'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-LAPTOP-14');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Mouse wireless', 'Mouse ergonomico senza fili, ricevitore USB-C', 29.90, 'SKU-MOUSE-01', 'https://loremflickr.com/600/400/computermouse', c.id, now(), now()
FROM categories c WHERE c.name = 'Electronics'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-MOUSE-01');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Tastiera meccanica', 'Tastiera meccanica retroilluminata, layout italiano', 89.00, 'SKU-KEYB-01', 'https://loremflickr.com/600/400/mechanicalkeyboard', c.id, now(), now()
FROM categories c WHERE c.name = 'Electronics'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-KEYB-01');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Cuffie over-ear', 'Cuffie con cancellazione attiva del rumore', 149.00, 'SKU-HEAD-01', 'https://loremflickr.com/600/400/headphones,audio/all', c.id, now(), now()
FROM categories c WHERE c.name = 'Electronics'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-HEAD-01');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Monitor 27"', 'Monitor QHD da 27 pollici, pannello IPS', 279.00, 'SKU-MON-27', 'https://loremflickr.com/600/400/monitor,computer/all', c.id, now(), now()
FROM categories c WHERE c.name = 'Electronics'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-MON-27');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Il nome della rosa', 'Umberto Eco - edizione tascabile', 13.50, 'SKU-BOOK-01', 'https://loremflickr.com/600/400/books,reading/all', c.id, now(), now()
FROM categories c WHERE c.name = 'Books'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-BOOK-01');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Atlante illustrato', 'Grande atlante geografico illustrato', 34.00, 'SKU-BOOK-02', 'https://loremflickr.com/600/400/atlas,map/all', c.id, now(), now()
FROM categories c WHERE c.name = 'Books'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-BOOK-02');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Felpa con cappuccio', 'Felpa in cotone biologico, unisex', 49.90, 'SKU-CLOT-01', 'https://loremflickr.com/600/400/hoodie', c.id, now(), now()
FROM categories c WHERE c.name = 'Clothing'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-CLOT-01');

INSERT INTO products (name, description, price, sku, image_url, category_id, created_at, updated_at)
SELECT 'Zaino da citta''', 'Zaino impermeabile con scomparto per laptop', 69.00, 'SKU-CLOT-02', 'https://loremflickr.com/600/400/backpack', c.id, now(), now()
FROM categories c WHERE c.name = 'Clothing'
  AND NOT EXISTS (SELECT 1 FROM products WHERE sku = 'SKU-CLOT-02');
