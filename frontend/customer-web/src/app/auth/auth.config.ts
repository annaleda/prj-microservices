import { AuthConfig } from 'angular-oauth2-oidc';

/**
 * Unica configurazione dell'app che non puo' essere relativa: le chiamate
 * alle API usano path come `/api/...` e funzionano ovunque, ma il browser
 * deve sapere dove sta Keycloak per fare il redirect di login.
 *
 * Il valore qui e' quello dello sviluppo locale (docker-compose). In un
 * deploy reale andrebbe sostituito con l'hostname pubblico dell'identity
 * provider.
 */
export const authConfig: AuthConfig = {
  issuer: 'http://localhost:8180/realms/polyglot-commerce',
  clientId: 'customer-web',
  redirectUri: window.location.origin + '/',
  postLogoutRedirectUri: window.location.origin + '/',
  responseType: 'code',
  scope: 'openid profile email',
  // Client pubblico (gira nel browser, non puo' custodire un segreto):
  // il flusso authorization code viene protetto con PKCE.
  useSilentRefresh: false,
  showDebugInformation: false,
  requireHttps: false,
};
