import { Component } from '@angular/core';
import { map } from 'rxjs';
import { AuthService } from './services/auth.service';
import { CartService } from './services/cart.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {
  title = 'customer-web';

  cartCount$ = this.cartService.items$.pipe(
    map((items) => items.reduce((sum, item) => sum + item.quantity, 0))
  );

  constructor(
    private readonly cartService: CartService,
    readonly auth: AuthService
  ) {}
}
