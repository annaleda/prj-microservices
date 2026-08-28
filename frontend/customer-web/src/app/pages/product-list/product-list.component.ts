import { Component, OnInit } from '@angular/core';
import { BehaviorSubject, Observable, combineLatest, map, shareReplay, startWith } from 'rxjs';
import { Category } from '../../models/category.model';
import { Product } from '../../models/product.model';
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

  constructor(private readonly catalogService: CatalogService) {}

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
}
