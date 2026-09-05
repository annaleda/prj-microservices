import { authHeaders } from './authToken';
import { Category } from '../types/category';
import { Product, ProductInput } from '../types/product';

const BASE_URL = '/api';

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Richiesta fallita (${response.status}): ${body || response.statusText}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function getProducts(): Promise<Product[]> {
  return fetch(`${BASE_URL}/products`, { headers: authHeaders() }).then((r) =>
    handleResponse<Product[]>(r)
  );
}

export function getProduct(id: number): Promise<Product> {
  return fetch(`${BASE_URL}/products/${id}`, { headers: authHeaders() }).then((r) =>
    handleResponse<Product>(r)
  );
}

export function createProduct(input: ProductInput): Promise<Product> {
  return fetch(`${BASE_URL}/products`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(input),
  }).then((r) => handleResponse<Product>(r));
}

export function updateProduct(id: number, input: ProductInput): Promise<Product> {
  return fetch(`${BASE_URL}/products/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(input),
  }).then((r) => handleResponse<Product>(r));
}

export function deleteProduct(id: number): Promise<void> {
  return fetch(`${BASE_URL}/products/${id}`, { method: 'DELETE', headers: authHeaders() }).then((r) =>
    handleResponse<void>(r)
  );
}

export function getCategories(): Promise<Category[]> {
  return fetch(`${BASE_URL}/categories`, { headers: authHeaders() }).then((r) =>
    handleResponse<Category[]>(r)
  );
}
