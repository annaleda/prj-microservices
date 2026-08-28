INSERT INTO categories (name, description)
SELECT 'Electronics', 'Dispositivi elettronici e accessori'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Electronics');

INSERT INTO categories (name, description)
SELECT 'Books', 'Libri e pubblicazioni'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Books');

INSERT INTO categories (name, description)
SELECT 'Clothing', 'Abbigliamento e accessori moda'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Clothing');
