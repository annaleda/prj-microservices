import { Injectable } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { authConfig } from '../auth/auth.config';
import { CartService } from './cart.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(
    private readonly oauthService: OAuthService,
    private readonly cartService: CartService
  ) {}

  /**
   * Configura il client OIDC e completa un eventuale login in corso (il
   * ritorno da Keycloak con il codice nell'URL).
   *
   * Se l'identity provider non risponde l'app parte lo stesso, solo da
   * disconnessa: il catalogo e' pubblico e deve restare sfogliabile.
   */
  async init(): Promise<void> {
    this.oauthService.configure(authConfig);
    try {
      await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    } catch (error) {
      console.warn('Identity provider non raggiungibile: si continua senza login', error);
    }
  }

  login(): void {
    this.oauthService.initCodeFlow();
  }

  /**
   * Porta alla registrazione di Keycloak.
   *
   * E' lo stesso flusso del login (authorization code + PKCE) su un
   * endpoint diverso - `/registrations` al posto di `/auth` - con gli
   * stessi parametri e lo stesso ritorno: chi si registra si ritrova
   * nell'applicazione gia' collegato. La modifica a `loginUrl` non e'
   * permanente: la pagina viene abbandonata subito dopo, e al prossimo
   * avvio il valore torna quello del documento di discovery.
   */
  register(): void {
    // loginUrl arriva dal documento di discovery; se non e' stato
    // caricato (identity provider irraggiungibile all'avvio) si ricava
    // dall'issuer configurato.
    const loginUrl = this.oauthService.loginUrl || `${authConfig.issuer}/protocol/openid-connect/auth`;
    this.oauthService.loginUrl = loginUrl.replace(/\/auth$/, '/registrations');
    this.oauthService.initCodeFlow();
  }

  logout(): void {
    // Il carrello sopravvive al redirect di login perche' e' salvato nel
    // browser: senza svuotarlo qui, chi usa lo stesso computer dopo
    // troverebbe la spesa di qualcun altro.
    this.cartService.clear();
    this.oauthService.logOut();
  }

  get isLoggedIn(): boolean {
    return this.oauthService.hasValidAccessToken();
  }

  get username(): string | null {
    const claims = this.oauthService.getIdentityClaims() as { preferred_username?: string } | null;
    return claims?.preferred_username ?? null;
  }
}
