import { useEffect } from 'react';
import { useAuth } from 'react-oidc-context';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import './App.css';
import { setAccessToken } from './api/authToken';
import { rolesFromAccessToken } from './auth/authConfig';
import ProductFormPage from './pages/ProductFormPage';
import ProductListPage from './pages/ProductListPage';

function App() {
  const auth = useAuth();
  const roles = rolesFromAccessToken(auth.user?.access_token);
  const isAdmin = roles.includes('ADMIN');

  // Il client HTTP non e' un componente e non puo' usare hook: il token
  // corrente gli viene passato qui a ogni cambio di stato del login.
  useEffect(() => {
    setAccessToken(auth.user?.access_token ?? null);
  }, [auth.user]);

  if (auth.isLoading) {
    return <p className="app-main">Verifica della sessione in corso…</p>;
  }

  if (!auth.isAuthenticated) {
    return (
      <main className="app-main">
        <h1>Polyglot Commerce &mdash; Admin</h1>
        <p>Questa console richiede un account con ruolo ADMIN.</p>
        <button onClick={() => void auth.signinRedirect()}>Accedi</button>
        {auth.error && <p className="error">Errore di autenticazione: {auth.error.message}</p>}
      </main>
    );
  }

  if (!isAdmin) {
    // Autenticato ma senza i permessi: senza questo blocco l'utente
    // vedrebbe la console e riceverebbe 403 su ogni operazione.
    return (
      <main className="app-main">
        <h1>Accesso non consentito</h1>
        <p>
          L'utente <strong>{auth.user?.profile.preferred_username}</strong> non ha il ruolo ADMIN.
        </p>
        <button onClick={() => void auth.signoutRedirect()}>Esci</button>
      </main>
    );
  }

  return (
    <BrowserRouter>
      <header className="app-header">
        <Link to="/" className="app-header__brand">
          Polyglot Commerce &mdash; Admin
        </Link>
        <span className="app-header__auth">
          {auth.user?.profile.preferred_username}{' '}
          <button onClick={() => void auth.signoutRedirect()}>Esci</button>
        </span>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<ProductListPage />} />
          <Route path="/products/new" element={<ProductFormPage />} />
          <Route path="/products/:id/edit" element={<ProductFormPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  );
}

export default App;
