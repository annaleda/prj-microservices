import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EMPTY, Observable, catchError, switchMap } from 'rxjs';
import { Product } from '../../models/product.model';
import { CatalogService } from '../../services/catalog.service';

@Component({
  selector: 'app-product-detail',
  templateUrl: './product-detail.component.html',
  styleUrls: ['./product-detail.component.scss'],
})
export class ProductDetailComponent implements OnInit {
  product$!: Observable<Product>;
  loadError = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly catalogService: CatalogService
  ) {}

  ngOnInit(): void {
    this.product$ = this.route.paramMap.pipe(
      switchMap((params) => {
        const id = Number(params.get('id'));
        return this.catalogService.getProduct(id).pipe(
          catchError(() => {
            this.loadError = true;
            return EMPTY;
          })
        );
      })
    );
  }
}
