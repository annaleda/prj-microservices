import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EMPTY, Observable, catchError, switchMap } from 'rxjs';
import { Product } from '../../models/product.model';
import { CartService } from '../../services/cart.service';
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
    private readonly catalogService: CatalogService,
    private readonly cartService: CartService
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

  /** Diventa true se l'immagine non si carica: si ripiega sul colore. */
  private imageBroken = false;

  showPhoto(product: Product): boolean {
    return !!product.imageUrl && !this.imageBroken;
  }

  onImageError(): void {
    this.imageBroken = true;
  }

  /** Stessa banda colorata della lista, cosi' il prodotto si riconosce. */
  thumbnail(product: Product): string {
    const hue = [...product.name].reduce((acc, char) => acc + char.charCodeAt(0), 0) % 360;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 38) % 360} 68% 46%))`;
  }

  addToCart(product: Product): void {
    this.cartService.add(product);
  }
}
