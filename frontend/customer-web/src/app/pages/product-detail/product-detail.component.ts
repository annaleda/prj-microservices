import { Component, HostListener, OnInit } from '@angular/core';
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

  /**
   * Immagine aperta a tutto schermo.
   *
   * Un popup e non un ingrandimento al passaggio del mouse: lo zoom su
   * hover mostra una porzione alla volta dentro una cornice piccola,
   * mentre qui si vede l'immagine intera e grande, che e' cio' che serve
   * per guardare un prodotto.
   */
  lightboxOpen = false;

  /** Dentro il popup si puo' ingrandire ancora, per guardare un dettaglio. */
  lightboxZoomed = false;
  /**
   * Punto su cui si ingrandisce. Segue il puntatore: uno zoom sempre
   * centrato costringe a indovinare cosa si sta guardando, mentre cosi' si
   * ingrandisce il dettaglio che si sta indicando e muovendo il mouse si
   * scorre l'immagine.
   */
  lightboxOrigin = 'center center';

  openLightbox(): void {
    this.lightboxOpen = true;
  }

  closeLightbox(): void {
    this.lightboxOpen = false;
    // Alla riapertura si riparte dall'immagine intera: ritrovarla
    // ingrandita su un punto scelto tempo prima disorienta.
    this.lightboxZoomed = false;
  }

  /** Clic sull'immagine del popup: ingrandisce o torna indietro. */
  toggleLightboxZoom(event: MouseEvent): void {
    // Non deve arrivare al fondo, che chiuderebbe il popup.
    event.stopPropagation();
    this.lightboxOrigin = this.originFrom(event);
    this.lightboxZoomed = !this.lightboxZoomed;
  }

  /** Mentre e' ingrandita, il movimento del mouse sposta l'inquadratura. */
  onLightboxMove(event: MouseEvent): void {
    if (this.lightboxZoomed) {
      this.lightboxOrigin = this.originFrom(event);
    }
  }

  private originFrom(event: MouseEvent): string {
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const x = ((event.clientX - box.left) / box.width) * 100;
    const y = ((event.clientY - box.top) / box.height) * 100;
    return `${x}% ${y}%`;
  }

  // Esc chiude: e' il gesto che chiunque prova per primo davanti a una
  // finestra sovrapposta.
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeLightbox();
  }

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
