import { HttpClientModule } from '@angular/common/http';
import { APP_INITIALIZER, NgModule } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BrowserModule } from '@angular/platform-browser';
import { OAuthModule } from 'angular-oauth2-oidc';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { CheckoutComponent } from './pages/checkout/checkout.component';
import { OrderDetailComponent } from './pages/order-detail/order-detail.component';
import { OrdersComponent } from './pages/orders/orders.component';
import { ProductDetailComponent } from './pages/product-detail/product-detail.component';
import { ProductListComponent } from './pages/product-list/product-list.component';
import { AuthService } from './services/auth.service';

@NgModule({
  declarations: [
    AppComponent,
    ProductListComponent,
    ProductDetailComponent,
    CheckoutComponent,
    OrdersComponent,
    OrderDetailComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    FormsModule,
    // resourceServer: la libreria aggiunge da sola l'header Authorization
    // alle chiamate verso /api quando c'e' un token valido, evitando un
    // interceptor scritto a mano.
    OAuthModule.forRoot({
      resourceServer: {
        allowedUrls: ['/api'],
        sendAccessToken: true
      }
    })
  ],
  providers: [
    {
      provide: APP_INITIALIZER,
      useFactory: (auth: AuthService) => () => auth.init(),
      deps: [AuthService],
      multi: true
    }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
