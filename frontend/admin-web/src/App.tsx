import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import './App.css';
import ProductFormPage from './pages/ProductFormPage';
import ProductListPage from './pages/ProductListPage';

function App() {
  return (
    <BrowserRouter>
      <header className="app-header">
        <Link to="/" className="app-header__brand">
          Polyglot Commerce &mdash; Admin
        </Link>
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
