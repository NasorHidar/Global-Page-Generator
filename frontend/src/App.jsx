/**
 * App.jsx — Root application shell for the Global Page Generator.
 *
 * Responsibilities:
 *  1. Provide an authentication token input (kept minimal — real IdP integration
 *     would replace this with a proper login flow).
 *  2. Fetch the list of available services from GET /api/v1/services.
 *  3. Manage the currently selected service in local state.
 *  4. Mount / unmount <DynamicServicePage> as the selection changes.
 *
 * State topology (all local — no global state manager needed at this scale):
 *
 *   App
 *   ├── token          (string)   — Bearer token sent with every API request
 *   ├── services       (array)    — fetched once from /api/v1/services
 *   ├── selectedId     (number)   — currently chosen serviceId
 *   └── <DynamicServicePage serviceId={selectedId} token={token} />
 */

import React, { useState, useEffect, useCallback } from 'react';
import DynamicServicePage from './components/DynamicServicePage';
import './App.css';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

// ─────────────────────────────────────────────────────────────────────────────
// Custom hook: useServices
// Fetches the service list once on mount (or when the token changes).
// ─────────────────────────────────────────────────────────────────────────────

function useServices(token) {
  const [services, setServices] = useState([]);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    const headers = token ? { Authorization: `Bearer ${token}` } : {};

    fetch(`${API_BASE}/services`, { headers })
      .then((res) => {
        if (!res.ok) throw new Error(`Services fetch failed: HTTP ${res.status}`);
        return res.json();
      })
      .then((data) => { if (!cancelled) setServices(data); })
      .catch((err) => { if (!cancelled) setError(err.message); })
      .finally(()  => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [token]);

  return { services, loading, error };
}

// ─────────────────────────────────────────────────────────────────────────────
// Component: TokenInput
// Minimal Bearer-token entry form. Replace with OAuth / OIDC redirect in prod.
// ─────────────────────────────────────────────────────────────────────────────

function TokenInput({ onTokenSubmit }) {
  const [draft, setDraft] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (draft.trim()) onTokenSubmit(draft.trim());
  };

  return (
    <div className="gpg-token-gate">
      <div className="gpg-token-card">
        <div className="gpg-token-logo">
          <span className="gpg-logo-icon">⬡</span>
          <h1 className="gpg-logo-title">Global Page Generator</h1>
        </div>
        <p className="gpg-token-subtitle">
          Enter your access token to continue
        </p>
        <form onSubmit={handleSubmit} className="gpg-token-form">
          <input
            id="token-input"
            type="password"
            className="gpg-input gpg-token-field"
            placeholder="Paste Bearer token…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            autoComplete="current-password"
            required
          />
          <button
            id="token-submit-btn"
            type="submit"
            className="gpg-btn gpg-btn--primary gpg-btn--full"
            disabled={!draft.trim()}
          >
            Authenticate →
          </button>
        </form>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Component: ServiceSelector
// ─────────────────────────────────────────────────────────────────────────────

function ServiceSelector({ services, selectedId, onSelect, loading, error }) {
  if (loading) {
    return (
      <div className="gpg-selector-row">
        <div className="gpg-spinner gpg-spinner--sm" aria-label="Loading services…" />
        <span className="gpg-muted">Loading available services…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="gpg-selector-row gpg-selector-error">
        ⚠️ Could not load services: {error}
      </div>
    );
  }

  return (
    <div className="gpg-selector-row">
      <label htmlFor="service-select" className="gpg-label gpg-selector-label">
        Service
      </label>
      <div className="gpg-select-wrapper">
        <select
          id="service-select"
          className="gpg-select"
          value={selectedId ?? ''}
          onChange={(e) => onSelect(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">— Select a service —</option>
          {services.map((svc) => (
            <option key={svc.id} value={svc.id}>
              {svc.serviceName}
            </option>
          ))}
        </select>
        <span className="gpg-select-arrow" aria-hidden="true">▾</span>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Root: App
// ─────────────────────────────────────────────────────────────────────────────

export default function App() {
  const [token,      setToken]      = useState(() =>
    // Persist token in sessionStorage so a page refresh doesn't force re-login
    sessionStorage.getItem('gpg_token') ?? ''
  );
  const [selectedId, setSelectedId] = useState(null);

  const { services, loading: servicesLoading, error: servicesError } = useServices(token);

  const handleTokenSubmit = useCallback((t) => {
    sessionStorage.setItem('gpg_token', t);
    setToken(t);
    setSelectedId(null);   // Reset service selection on re-authentication
  }, []);

  const handleServiceSelect = useCallback((id) => {
    setSelectedId(id);
  }, []);

  const handleLogout = useCallback(() => {
    sessionStorage.removeItem('gpg_token');
    setToken('');
    setSelectedId(null);
  }, []);

  // ── Not authenticated ────────────────────────────────────────────────────
  if (!token) {
    return <TokenInput onTokenSubmit={handleTokenSubmit} />;
  }

  // ── Authenticated shell ──────────────────────────────────────────────────
  return (
    <div className="gpg-app">

      {/* ── Top navigation bar ────────────────────────────────────────── */}
      <nav className="gpg-navbar">
        <div className="gpg-navbar-brand">
          <span className="gpg-logo-icon gpg-logo-icon--sm">⬡</span>
          <span className="gpg-navbar-title">Global Page Generator</span>
        </div>

        <div className="gpg-navbar-controls">
          <ServiceSelector
            services={services}
            selectedId={selectedId}
            onSelect={handleServiceSelect}
            loading={servicesLoading}
            error={servicesError}
          />
          <button
            id="logout-btn"
            type="button"
            className="gpg-btn gpg-btn--ghost gpg-btn--sm"
            onClick={handleLogout}
            title="Sign out"
          >
            Sign out
          </button>
        </div>
      </nav>

      {/* ── Main content area ─────────────────────────────────────────── */}
      <div className="gpg-main">
        {selectedId ? (
          /*
           * Key on selectedId so React fully unmounts and remounts DynamicServicePage
           * whenever the service changes — clearing all stale state (NID input,
           * response data, execution status) automatically.
           */
          <DynamicServicePage
            key={selectedId}
            serviceId={selectedId}
            token={token}
          />
        ) : (
          <div className="gpg-welcome">
            <div className="gpg-welcome-inner">
              <span className="gpg-welcome-icon">⬡</span>
              <h2 className="gpg-welcome-heading">Welcome</h2>
              <p className="gpg-welcome-body">
                Select a service from the dropdown above to load its page.
              </p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
