import { Component, OnInit } from '@angular/core';
import { BehaviorSubject, Observable, combineLatest, map, shareReplay, startWith } from 'rxjs';
import { Category } from '../../models/category.model';
import { Product } from '../../models/product.model';
import { CartService } from '../../services/cart.service';
import { CatalogService } from '../../services/catalog.service';

@Component({
  selector: 'app-product-list',
  templateUrl: './product-list.component.html',
  styleUrls: ['./product-list.component.scss'],
})
export class ProductListComponent implements OnInit {
  categories$!: Observable<Category[]>;
  filteredProducts$!: Observable<Product[]>;
  loadError = false;

  private readonly selectedCategoryId$ = new BehaviorSubject<number | null>(null);
  private readonly products$ = new BehaviorSubject<Product[]>([]);

  constructor(
    private readonly catalogService: CatalogService,
    private readonly cartService: CartService
  ) {}

  ngOnInit(): void {
    this.categories$ = this.catalogService.getCategories().pipe(
      shareReplay(1)
    );

    this.catalogService.getProducts().subscribe({
      next: (products) => this.products$.next(products),
      error: () => (this.loadError = true),
    });

    this.filteredProducts$ = combineLatest([
      this.products$,
      this.selectedCategoryId$.pipe(startWith(null)),
    ]).pipe(
      map(([products, categoryId]) =>
        categoryId === null
          ? products
          : products.filter((p) => p.categoryId === categoryId)
      )
    );
  }

  onCategoryChange(categoryId: string): void {
    this.selectedCategoryId$.next(categoryId === '' ? null : Number(categoryId));
  }

  /** Prodotti la cui immagine non e' stata caricata: si ripiega sul colore. */
  private readonly brokenImages = new Set<number>();

  showPhoto(product: Product): boolean {
    return !!product.imageUrl && !this.brokenImages.has(product.id);
  }

  onImageError(product: Product): void {
    this.brokenImages.add(product.id);
  }

  /**
   * Colore della banda al posto della foto: derivato dal nome, cosi' lo
   * stesso prodotto ha sempre la stessa tinta e prodotti diversi si
   * distinguono, senza dover salvare nulla.
   */
  thumbnail(product: Product): string {
    const hue = [...product.name].reduce((acc, char) => acc + char.charCodeAt(0), 0) % 360;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 38) % 360} 68% 46%))`;
  }

  addToCart(product: Product, event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.cartService.add(product);
  }
}
