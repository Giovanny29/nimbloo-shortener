import { useState } from "react";
import { createLink } from "../api";
import { ApiError, type LinkResponse } from "../types";

interface CreateLinkFormProps {
  onCreated: (link: LinkResponse) => void;
}

type FieldErrors = {
  url: string | null;
  alias: string | null;
  expiresAt: string | null;
};

const EMPTY_FIELD_ERRORS: FieldErrors = {
  url: null,
  alias: null,
  expiresAt: null,
};

function fieldFromMessage(message: string): keyof FieldErrors | null {
  const lowered = message.toLowerCase();
  if (lowered.startsWith("url") || lowered.includes("url")) {
    return "url";
  }
  if (lowered.startsWith("alias") || lowered.includes("alias")) {
    return "alias";
  }
  if (lowered.includes("expira") || lowered.includes("data")) {
    return "expiresAt";
  }
  return null;
}

export default function CreateLinkForm({ onCreated }: CreateLinkFormProps) {
  const [url, setUrl] = useState("");
  const [alias, setAlias] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] =
    useState<FieldErrors>(EMPTY_FIELD_ERRORS);
  const [created, setCreated] = useState<LinkResponse | null>(null);
  const [copied, setCopied] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setFieldErrors(EMPTY_FIELD_ERRORS);
    setCreated(null);
    setCopied(false);

    if (expiresAt) {
      const selected = new Date(expiresAt).getTime();
      if (Number.isNaN(selected) || selected <= Date.now()) {
        setFieldErrors({
          ...EMPTY_FIELD_ERRORS,
          expiresAt: "A data de expiração deve ser uma data no futuro.",
        });
        return;
      }
    }

    setSubmitting(true);
    try {
      const link = await createLink({
        url,
        alias: alias || undefined,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined,
      });
      setCreated(link);
      onCreated(link);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) {
        setFieldErrors({
          url: err.fieldErrors.url ?? null,
          alias: err.fieldErrors.alias ?? null,
          expiresAt: err.fieldErrors.expiresAt ?? null,
        });
      } else {
        const message =
          err instanceof Error ? err.message : "Falha ao criar o link.";
        const field = fieldFromMessage(message);
        if (field) {
          setFieldErrors((prev) => ({ ...prev, [field]: message }));
        } else {
          setError(message);
        }
      }
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
        <div className={`field ${fieldErrors.url ? "field-error" : ""}`}>
          <label htmlFor="url">URL original *</label>
          <input
            id="url"
            type="text"
            required
            placeholder="https://exemplo.com/pagina-com-parametros?utm=..."
            value={url}
            onChange={(e) => {
              setUrl(e.target.value);
              setFieldErrors((prev) => ({ ...prev, url: null }));
            }}
            maxLength={2048}
            aria-invalid={fieldErrors.url !== null}
          />
          {fieldErrors.url ? (
            <span className="hint error-hint">{fieldErrors.url}</span>
          ) : (
            <span className="hint">
              Deve começar com http:// ou https:// (máx. 2048 caracteres).
            </span>
          )}
        </div>

        <div className={`field ${fieldErrors.alias ? "field-error" : ""}`}>
          <label htmlFor="alias">Alias (opcional)</label>
          <input
            id="alias"
            type="text"
            placeholder="meu-link"
            value={alias}
            onChange={(e) => {
              setAlias(e.target.value);
              setFieldErrors((prev) => ({ ...prev, alias: null }));
            }}
            pattern="[a-zA-Z0-9_-]{3,30}"
            title="3 a 30 caracteres: letras, números, hífen ou underline"
            aria-invalid={fieldErrors.alias !== null}
          />
          {fieldErrors.alias ? (
            <span className="hint error-hint">{fieldErrors.alias}</span>
          ) : (
            <span className="hint">
              3 a 30 caracteres: letras, números, hífen ou underline.
            </span>
          )}
        </div>

        <div className={`field ${fieldErrors.expiresAt ? "field-error" : ""}`}>
          <label htmlFor="expiresAt">Expira em (opcional)</label>
          <input
            id="expiresAt"
            type="datetime-local"
            value={expiresAt}
            onChange={(e) => {
              setExpiresAt(e.target.value);
              setFieldErrors((prev) => ({ ...prev, expiresAt: null }));
            }}
            aria-invalid={fieldErrors.expiresAt !== null}
          />
          {fieldErrors.expiresAt ? (
            <span className="hint error-hint">{fieldErrors.expiresAt}</span>
          ) : (
            <span className="hint">Sem data, o link não expira.</span>
          )}
        </div>

        {error && (
          <div className="alert alert-error" role="alert">
            {error}
          </div>
        )}

        <div className="form-actions">
          <button
            type="submit"
            className="btn btn-primary"
            disabled={submitting}
          >
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
          <button
            type="button"
            className={`btn ${copied ? "btn-ghost" : "btn-secondary"}`}
            onClick={handleCopy}
          >
            {copied ? "Copiado!" : "Copiar"}
          </button>
        </div>
      )}
    </section>
  );
}
