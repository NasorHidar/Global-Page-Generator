/**
 * DynamicServicePage.jsx
 *
 * The single React component that drives the entire dynamic-rendering workflow:
 *
 *  1. On mount (or when `serviceId` changes) → fetches the page layout from
 *     GET /api/v1/layout/:serviceId and stores it as `layout`.
 *
 *  2. The layout's `components` array is rendered in sort-order.
 *     Currently, the only supported `componentType` is "DATA_TABLE"; the
 *     architecture makes adding new types trivial (see `COMPONENT_RENDERERS`).
 *
 *  3. The operator enters a National ID and submits the form → POST /api/v1/execute.
 *     The raw JSON response (potentially thousands of lines) is stored as `responseData`.
 *
 *  4. Each DATA_TABLE component reads its `properties.columns` array.
 *     Each column carries a `jsonPath` string (e.g. "$.person.address.line1").
 *     A lightweight, zero-dependency JSONPath evaluator (`extractByPath`) walks
 *     the response object using dot/bracket notation — covering the vast majority
 *     of real-world paths without pulling in a heavy library.
 *
 *  5. If a column specifies `"type": "date"`, the extracted value is formatted
 *     with Intl.DateTimeFormat; "currency" triggers Intl.NumberFormat.
 *
 * Architecture Note — why JSONPath on the frontend?
 * The backend stores the raw, unmodified API response. The column-level
 * JSONPath expressions (defined by administrators in the Component table) are
 * applied at render time. This means display logic can be reconfigured in the
 * database without redeployment.
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import './DynamicServicePage.css';

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight JSONPath evaluator
// Supports:  $.a.b.c   $['a']['b']   $.arr[0].name   (no filter predicates)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves a JSONPath expression against a plain JS object/array.
 * Returns the resolved value or `undefined` if the path doesn't exist.
 *
 * @param {object|array} obj  - root data object (the raw API response)
 * @param {string}       path - JSONPath starting with "$."
 * @returns {*}
 */
function extractByPath(obj, path) {
  if (!obj || !path) return undefined;

  // Normalise: strip leading "$." or "$"
  const normalised = path.replace(/^\$\.?/, '');
  if (!normalised) return obj;

  // Split on dots and bracket notations: a.b[0].c → ['a','b','0','c']
  const segments = normalised
    .replace(/\[(\d+)\]/g, '.$1')   // [0] → .0
    .replace(/\['(.+?)'\]/g, '.$1') // ['key'] → .key
    .split('.')
    .filter(Boolean);

  let current = obj;
  for (const seg of segments) {
    if (current == null) return undefined;
    current = current[seg];
  }
  return current;
}

// ─────────────────────────────────────────────────────────────────────────────
// Value formatters keyed by `type` from the Component properties
// ─────────────────────────────────────────────────────────────────────────────

const DATE_FMT = new Intl.DateTimeFormat('en-GB', {
  year: 'numeric', month: 'short', day: '2-digit',
});
const CURRENCY_FMT = new Intl.NumberFormat('en-US', {
  style: 'currency', currency: 'USD',
});

const FORMATTERS = {
  date:     (v) => (v ? DATE_FMT.format(new Date(v))           : '—'),
  currency: (v) => (v != null ? CURRENCY_FMT.format(Number(v)) : '—'),
  text:     (v) => (v != null ? String(v)                       : '—'),
};

function formatValue(value, type) {
  const fmt = FORMATTERS[type] ?? FORMATTERS.text;
  try {
    return fmt(value);
  } catch {
    return String(value ?? '—');
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Component: DataTable
// Renders one DATA_TABLE component from the layout config.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @param {{ columns: Array<{label:string, jsonPath:string, type:string}> }} properties
 * @param {object|null} responseData  - raw API response JSON
 * @param {string}      status        - 'idle' | 'loading' | 'success' | 'error'
 */
function DataTable({ properties, responseData, status }) {
  const columns = useMemo(
    () => (properties?.columns ?? []),
    [properties],
  );

  if (status === 'idle') {
    return (
      <div className="gpg-table-placeholder">
        <span className="gpg-placeholder-icon">🔍</span>
        <p>Enter a National ID above and click <strong>Search</strong> to load data.</p>
      </div>
    );
  }

  if (status === 'loading') {
    return (
      <div className="gpg-table-placeholder">
        <div className="gpg-spinner" aria-label="Loading..." />
        <p>Fetching data from upstream service…</p>
      </div>
    );
  }

  if (status === 'error' || !responseData) {
    return (
      <div className="gpg-table-placeholder gpg-error-state">
        <span className="gpg-placeholder-icon">⚠️</span>
        <p>Could not retrieve data. Please check the NID and try again.</p>
      </div>
    );
  }

  // Extract one row of values from the (potentially massive) response
  const row = columns.map((col) => ({
    label: col.label,
    value: extractByPath(responseData, col.jsonPath),
    type:  col.type ?? 'text',
  }));

  return (
    <div className="gpg-table-wrapper" role="region" aria-label="Result data">
      <table className="gpg-data-table">
        <thead>
          <tr>
            {row.map((cell) => (
              <th key={cell.label} scope="col">{cell.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            {row.map((cell) => (
              <td key={cell.label} data-label={cell.label}>
                {formatValue(cell.value, cell.type)}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Component: CardDisplay
// Renders a single summary "card" extracted from the upstream payload.
// Example properties:
//   {
//     "title":        "Profile Summary",
//     "subtitleJsonPath": "$.person.fullName",
//     "avatarJsonPath":   "$.person.photoUrl",
//     "fields": [
//       { "label": "Date of Birth", "jsonPath": "$.person.dob",        "type": "date"     },
//       { "label": "Father",        "jsonPath": "$.person.fatherName", "type": "text"     },
//       { "label": "Phone",         "jsonPath": "$.person.phone",      "type": "text"     },
//       { "label": "Salary",        "jsonPath": "$.person.salary",     "type": "currency" }
//     ]
//   }
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @param {{ title:string, subtitleJsonPath?:string, avatarJsonPath?:string,
 *           fields?: Array<{label:string, jsonPath:string, type?:string}> }} properties
 * @param {object|null} responseData  - raw API response JSON
 * @param {string}      status        - 'idle' | 'loading' | 'success' | 'error'
 */
function CardDisplay({ properties, responseData, status }) {
  const title = properties?.title ?? 'Details';

  // While the operator hasn't submitted the NID, show an empty placeholder
  // — a card with no content is confusing.
  if (status !== 'success' || !responseData) {
    return (
      <div className="gpg-card gpg-card--placeholder">
        <h3 className="gpg-card-title">{title}</h3>
        <p className="gpg-card-empty">
          {status === 'loading'
            ? 'Loading card…'
            : status === 'error'
              ? 'Could not load card.'
              : 'Submit a search to view details.'}
        </p>
      </div>
    );
  }

  // Each "field" descriptor carries a jsonPath pointing into responseData.
  // We walk the JSON via the zero-dependency extractByPath utility defined above.
  const subtitle = properties?.subtitleJsonPath
    ? extractByPath(responseData, properties.subtitleJsonPath)
    : null;
  const avatar   = properties?.avatarJsonPath
    ? extractByPath(responseData, properties.avatarJsonPath)
    : null;

  const rows = (properties?.fields ?? []).map((f) => ({
    label: f.label,
    value: extractByPath(responseData, f.jsonPath),
    type:  f.type ?? 'text',
  }));

  return (
    <article className="gpg-card" aria-labelledby={`card-${title}`}>
      <header className="gpg-card-header">
        {avatar && (
          <img
            className="gpg-card-avatar"
            src={String(avatar)}
            alt=""
            onError={(e) => { e.currentTarget.style.display = 'none'; }}
          />
        )}
        <div className="gpg-card-titles">
          <h3 id={`card-${title}`} className="gpg-card-title">{title}</h3>
          {subtitle != null && (
            <p className="gpg-card-subtitle">{String(subtitle)}</p>
          )}
        </div>
      </header>

      {rows.length > 0 && (
        <dl className="gpg-card-fields">
          {rows.map((row) => (
            <div className="gpg-card-field" key={row.label}>
              <dt className="gpg-card-label">{row.label}</dt>
              <dd className="gpg-card-value">{formatValue(row.value, row.type)}</dd>
            </div>
          ))}
        </dl>
      )}
    </article>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Component: BadgeDisplay
// Renders a small status pill — perfect for ACTIVE/INACTIVE flags.
// Example properties:
//   {
//     "label":      "Account Status",
//     "valueJsonPath": "$.status",        // string or boolean — coerced to text
//     "colorMap":   {                       // optional per-value CSS colour
//        "ACTIVE":   "green",
//        "INACTIVE": "grey",
//        "BLOCKED":  "red"
//     },
//     "defaultColor": "blue"
//   }
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps a colour name to a CSS class. Keeps the colour palette centrally
 * defined so designers can theme the whole app from one file.
 */
const BADGE_COLOR_CLASSES = {
  green: 'gpg-badge--green',
  red:   'gpg-badge--red',
  amber: 'gpg-badge--amber',
  blue:  'gpg-badge--blue',
  grey:  'gpg-badge--grey',
};

/**
 * @param {{ label:string, valueJsonPath:string,
 *           colorMap?: Record<string,string>, defaultColor?:string }} properties
 * @param {object|null} responseData
 * @param {string}      status
 */
function BadgeDisplay({ properties, responseData, status }) {
  const label = properties?.label ?? 'Status';

  if (status !== 'success' || !responseData) {
    return (
      <div className="gpg-badge-row">
        <span className="gpg-badge-label">{label}</span>
        <span className="gpg-badge gpg-badge--muted">—</span>
      </div>
    );
  }

  // Extract the value (could be a string, boolean, or number).
  const raw = extractByPath(responseData, properties?.valueJsonPath ?? '');
  const text = raw == null ? '—' : String(raw);

  // Pick a colour from the colour map, falling back to the default.
  const colorMap     = properties?.colorMap ?? {};
  const defaultColor = properties?.defaultColor ?? 'blue';
  const colorName    = colorMap[text] ?? defaultColor;
  const colorClass   = BADGE_COLOR_CLASSES[colorName] ?? BADGE_COLOR_CLASSES.blue;

  return (
    <div className="gpg-badge-row" role="status" aria-label={`${label}: ${text}`}>
      <span className="gpg-badge-label">{label}</span>
      <span className={`gpg-badge ${colorClass}`}>{text}</span>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Component renderer registry
// Adding a new componentType = add one entry here, no other changes needed.
// ─────────────────────────────────────────────────────────────────────────────

const COMPONENT_RENDERERS = {
  DATA_TABLE: DataTable,
  CARD:       CardDisplay,
  BADGE:      BadgeDisplay,
};

function DynamicComponent({ component, responseData, executionStatus }) {
  const Renderer = COMPONENT_RENDERERS[component.componentType];
  if (!Renderer) {
    return (
      <div className="gpg-unknown-component">
        <em>Unknown component type: {component.componentType}</em>
      </div>
    );
  }
  return (
    <section
      key={component.id}
      className="gpg-component-section"
      aria-labelledby={`comp-${component.id}-heading`}
    >
      <Renderer
        properties={component.properties}
        responseData={responseData}
        status={executionStatus}
      />
    </section>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches and caches the page layout for a given serviceId.
 * Re-fetches automatically when serviceId changes.
 * Forwards the Bearer token on the layout request (optional — layout is
 * permitted-all in SecurityConfig, but sending it is harmless and keeps
 * the fetch behaviour consistent with the execute call).
 */
function useLayout(serviceId, token) {
  const [layout,  setLayout]  = useState(null);
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);

  useEffect(() => {
    if (!serviceId) return;

    let cancelled = false;
    setLoading(true);
    setError(null);

    const headers = token ? { Authorization: `Bearer ${token}` } : {};

    fetch(`${API_BASE}/layout/${serviceId}`, { headers })
      .then((res) => {
        if (!res.ok) throw new Error(`Layout fetch failed: ${res.status}`);
        return res.json();
      })
      .then((data) => { if (!cancelled) setLayout(data); })
      .catch((err) => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [serviceId, token]);

  return { layout, loading, error };
}

// ─────────────────────────────────────────────────────────────────────────────
// Main exported component: DynamicServicePage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders the complete dynamic service page driven by backend configuration.
 *
 * @param {{ serviceId: number, token: string }} props
 *   - `serviceId` — determines which page layout and upstream service to use
 *   - `token`     — Bearer token forwarded on API calls
 */
export default function DynamicServicePage({ serviceId, token }) {
  // ── Layout ────────────────────────────────────────────────────────────────
  const { layout, loading: layoutLoading, error: layoutError } = useLayout(serviceId, token);

  // ── NID form state ────────────────────────────────────────────────────────
  const [nid,             setNid]             = useState('');
  const [executionStatus, setExecutionStatus] = useState('idle'); // idle | loading | success | error
  const [responseData,    setResponseData]    = useState(null);
  const [errorMessage,    setErrorMessage]    = useState('');
  const [requestId,       setRequestId]       = useState(null);

  // ── Execution handler ─────────────────────────────────────────────────────
  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      if (!nid.trim()) return;

      setExecutionStatus('loading');
      setResponseData(null);
      setErrorMessage('');
      setRequestId(null);

      try {
        const res = await fetch(`${API_BASE}/execute`, {
          method:  'POST',
          headers: {
            'Content-Type':  'application/json',
            'Authorization': `Bearer ${token}`,
          },
          body: JSON.stringify({ serviceId, nid: nid.trim() }),
        });

        if (!res.ok) {
          const problem = await res.json().catch(() => ({}));
          throw new Error(problem.detail ?? `Execution failed: HTTP ${res.status}`);
        }

        const { requestId: rid, responseData: data } = await res.json();
        setRequestId(rid);
        setResponseData(data);
        setExecutionStatus('success');
      } catch (err) {
        setErrorMessage(err.message);
        setExecutionStatus('error');
      }
    },
    [token, serviceId, nid],
  );

  const handleReset = useCallback(() => {
    setNid('');
    setExecutionStatus('idle');
    setResponseData(null);
    setErrorMessage('');
    setRequestId(null);
  }, []);

  // ── Render ────────────────────────────────────────────────────────────────

  if (layoutLoading) {
    return (
      <div className="gpg-page-loading">
        <div className="gpg-spinner gpg-spinner--large" aria-label="Loading page layout…" />
        <p>Loading page configuration…</p>
      </div>
    );
  }

  if (layoutError) {
    return (
      <div className="gpg-page-error">
        <h2>⚠️ Configuration Error</h2>
        <p>{layoutError}</p>
      </div>
    );
  }

  if (!layout) return null;

  return (
    <main className="gpg-page" aria-label={layout.pageTitle}>
      {/* ── Page header ────────────────────────────────────────────────── */}
      <header className="gpg-page-header">
        <h1 className="gpg-page-title">{layout.pageTitle}</h1>
        {requestId && (
          <span className="gpg-request-badge" title="Audit record ID">
            Record&nbsp;#{requestId}
          </span>
        )}
      </header>

      {/* ── NID search form ────────────────────────────────────────────── */}
      <div className="gpg-search-card">
        <form
          id="nid-search-form"
          className="gpg-search-form"
          onSubmit={handleSubmit}
          noValidate
          aria-label="NID search form"
        >
          <label htmlFor="nid-input" className="gpg-label">
            National ID (NID)
          </label>
          <div className="gpg-search-row">
            <input
              id="nid-input"
              type="text"
              className="gpg-input"
              value={nid}
              onChange={(e) => setNid(e.target.value)}
              placeholder="Enter National ID…"
              maxLength={64}
              required
              disabled={executionStatus === 'loading'}
              aria-describedby={errorMessage ? 'nid-error' : undefined}
              autoComplete="off"
              spellCheck={false}
            />
            <button
              id="nid-search-btn"
              type="submit"
              className="gpg-btn gpg-btn--primary"
              disabled={!nid.trim() || executionStatus === 'loading'}
            >
              {executionStatus === 'loading' ? (
                <><span className="gpg-spinner gpg-spinner--sm" aria-hidden="true" /> Searching…</>
              ) : (
                'Search'
              )}
            </button>
            {executionStatus !== 'idle' && (
              <button
                id="nid-reset-btn"
                type="button"
                className="gpg-btn gpg-btn--ghost"
                onClick={handleReset}
                disabled={executionStatus === 'loading'}
              >
                Clear
              </button>
            )}
          </div>

          {/* Inline validation / API error message */}
          {errorMessage && (
            <p id="nid-error" className="gpg-field-error" role="alert">
              {errorMessage}
            </p>
          )}
        </form>
      </div>

      {/* ── Dynamic component area ─────────────────────────────────────── */}
      <div className="gpg-components-area">
        {layout.components.map((component) => (
          <DynamicComponent
            key={component.id}
            component={component}
            responseData={responseData}
            executionStatus={executionStatus}
          />
        ))}
      </div>

      {/* ── Debug panel (development only) ────────────────────────────── */}
      {import.meta.env.DEV && responseData && (
        <details className="gpg-debug-panel">
          <summary>Raw API Response (dev only)</summary>
          <pre>{JSON.stringify(responseData, null, 2)}</pre>
        </details>
      )}
    </main>
  );
}
