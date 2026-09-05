import { Injectable } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { authConfig } from '../auth/auth.config';

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(private readonly oauthService: OAuthService) {}

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

  logout(): void {
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
