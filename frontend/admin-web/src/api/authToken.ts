/**
 * Ponte fra il contesto React di autenticazione e il client HTTP, che e'
 * fatto di funzioni normali e non puo' usare hook: App aggiorna qui il
 * token corrente, `catalogApi` lo legge al momento della chiamata.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function authHeaders(): Record<string, string> {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}
