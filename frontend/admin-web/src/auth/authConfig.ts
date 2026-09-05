import { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';

/**
 * Unica configurazione che non puo' essere relativa: le chiamate alle API
 * usano path come `/api/...` e funzionano ovunque, ma il browser deve
 * sapere dove sta Keycloak per fare il redirect di login.
 *
 * Il valore e' quello dello sviluppo locale (docker-compose); in un deploy
 * reale andrebbe sostituito con l'hostname pubblico dell'identity provider.
 */
export const authConfig: AuthProviderProps = {
  authority: 'http://localhost:8180/realms/polyglot-commerce',
  client_id: 'admin-web',
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code',
  scope: 'openid profile email',
  // Senza questo il login si perde a ogni ricaricamento della pagina.
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  // Toglie ?code=...&state=... dalla barra degli indirizzi dopo il login.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};

/** Ruoli di realm contenuti nell'access token (Keycloak: realm_access.roles). */
export function rolesFromAccessToken(accessToken?: string): string[] {
  if (!accessToken) {
    return [];
  }
  try {
    const payload = accessToken.split('.')[1];
    const decoded = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    return decoded?.realm_access?.roles ?? [];
  } catch {
    return [];
  }
}
