export interface Product {
  id: number;
  name: string;
  description: string | null;
  price: number;
  sku: string;
  imageUrl: string | null;
  categoryId: number | null;
  categoryName: string | null;
  createdAt: string;
  updatedAt: string;
}
