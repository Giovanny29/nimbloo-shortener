import { useState } from "react";
import { createLink } from "../api";
import type { LinkResponse } from "../types";

interface CreateLinkFormProps {
  onCreated: (link: LinkResponse) => void;
}

export default function CreateLinkForm({ onCreated }: CreateLinkFormProps) {
  const [url, setUrl] = useState("");
  const [alias, setAlias] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<LinkResponse | null>(null);
  const [copied, setCopied] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setCreated(null);
    setCopied(false);
    setSubmitting(true);

    try {
      const link = await createLink({
        url,
        alias: alias || undefined,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined
      });
      setCreated(link);
      onCreated(link);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Falha ao criar o link.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleCopy = async () => {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.shortUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // clipboard indisponível: o usuário ainda pode copiar manualmente
      setCopied(false);
    }
  };

  return (
    <section className="card">
      <h2>Novo link curto</h2>
      <form className="link-form" onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="url">URL original *</label>
          <input
            id="url"
            type="text"
            required
            placeholder="https://exemplo.com/pagina-com-parametros?utm=..."
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            maxLength={2048}
          />
        </div>

        <div className="field">
          <label htmlFor="alias">Alias (opcional)</label>
          <input
            id="alias"
            type="text"
            placeholder="meu-link"
            value={alias}
            onChange={(e) => setAlias(e.target.value)}
            pattern="[a-zA-Z0-9_-]{3,30}"
            title="3 a 30 caracteres: letras, números, hífen ou underline"
          />
          <span className="hint">3 a 30 caracteres: letras, números, hífen ou underline.</span>
        </div>

        <div className="field">
          <label htmlFor="expiresAt">Expira em (opcional)</label>
          <input
            id="expiresAt"
            type="datetime-local"
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
          />
          <span className="hint">Sem data, o link não expira.</span>
        </div>

        {error && <div className="alert alert-error" role="alert">{error}</div>}

        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? "Criando..." : "Criar link"}
          </button>
        </div>
      </form>

      {created && (
        <div className="result-box" style={{ marginTop: 16 }}>
          <div>
            <strong>Link criado:</strong>{" "}
            <a href={created.shortUrl} target="_blank" rel="noreferrer">
              {created.shortUrl}
            </a>
          </div>
          <button type="button" className={`btn ${copied ? "btn-ghost" : "btn-secondary"}`} onClick={handleCopy}>
            {copied ? "Copiado!" : "Copiar"}
          </button>
        </div>
      )}
    </section>
  );
}