import { useCallback, useEffect, useState } from "react";
import Swal from "sweetalert2";
import { deleteLink, listLinks } from "../api";
import type { LinkResponse, LinkStatus } from "../types";

const PAGE_SIZE = 10;

type StatusFilter = "ALL" | LinkStatus;

const STATUS_LABEL: Record<LinkStatus, string> = {
  ACTIVE: "Ativo",
  EXPIRED: "Expirado",
  DISABLED: "Desativado",
};

function statusBadgeClass(status: LinkStatus): string {
  if (status === "ACTIVE") return "badge badge-active";
  if (status === "EXPIRED") return "badge badge-expired";
  return "badge badge-disabled";
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleDateString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });
}

interface LinkListProps {
  refreshKey?: number;
}

export default function LinkList({ refreshKey = 0 }: LinkListProps) {
  const [items, setItems] = useState<LinkResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [lastKey, setLastKey] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [deletingCode, setDeletingCode] = useState<string | null>(null);
  const [copiedCode, setCopiedCode] = useState<string | null>(null);

  const visibleItems =
    statusFilter === "ALL"
      ? items
      : items.filter((link) => link.status === statusFilter);

  const handleCopy = async (link: LinkResponse) => {
    try {
      await navigator.clipboard.writeText(link.shortUrl);
      setCopiedCode(link.code);
      setTimeout(
        () =>
          setCopiedCode((current) => (current === link.code ? null : current)),
        2000,
      );
    } catch {
      setCopiedCode(null);
    }
  };

  const handleDelete = async (link: LinkResponse) => {
    const confirmation = await Swal.fire({
      title: `Deletar o link /${link.code}?`,
      html: `O link <strong>/${link.code}</strong> será desativado e não poderá mais redirecionar.`,
      icon: "warning",
      showCancelButton: true,
      confirmButtonText: "Sim, deletar",
      cancelButtonText: "Não",
      reverseButtons: true,
      customClass: {
        confirmButton: "swal-btn-danger",
        cancelButton: "swal-btn-neutral",
      },
    });

    if (!confirmation.isConfirmed) {
      return;
    }

    setDeletingCode(link.code);
    try {
      await deleteLink(link.code);
      await loadFirstPage();
      await Swal.fire({
        title: "Link deletado",
        html: `O link <strong>/${link.code}</strong> foi deletado.`,
        icon: "success",
        confirmButtonText: "OK",
        customClass: { confirmButton: "swal-btn-brand" },
      });
    } catch (err) {
      await Swal.fire({
        title: "Erro ao deletar",
        text: err instanceof Error ? err.message : "Falha ao deletar o link.",
        icon: "error",
        confirmButtonText: "OK",
        customClass: { confirmButton: "swal-btn-brand" },
      });
    } finally {
      setDeletingCode(null);
    }
  };

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await listLinks(PAGE_SIZE);
      setItems(page.items);
      setHasMore(page.hasMore);
      setLastKey(page.lastEvaluatedKey);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Falha ao listar os links.",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage, refreshKey]);

  const loadMore = async () => {
    if (!lastKey || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await listLinks(PAGE_SIZE, lastKey);
      setItems((prev) => [...prev, ...page.items]);
      setHasMore(page.hasMore);
      setLastKey(page.lastEvaluatedKey);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Falha ao carregar mais links.",
      );
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <section className="card">
      <h2>Links criados</h2>

      {loading && (
        <div className="page-loader" role="status">
          <span className="spinner" aria-hidden="true" />
          Carregando links...
        </div>
      )}

      {!loading && error && (
        <div style={{ display: "grid", gap: 12 }}>
          <div className="alert alert-error" role="alert">
            {error}
          </div>
          <div>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => void loadFirstPage()}
            >
              Tentar novamente
            </button>
          </div>
        </div>
      )}

      {!loading && !error && items.length === 0 && (
        <div className="alert alert-empty">
          {hasMore ? (
            <>
              Carregando mais resultados...
              <div style={{ marginTop: 12 }}>
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={() => void loadMore()}
                  disabled={loadingMore}
                >
                  {loadingMore ? "Carregando..." : "Carregar mais"}
                </button>
              </div>
            </>
          ) : (
            "Nenhum link criado ainda — crie o primeiro acima."
          )}
        </div>
      )}

      {!loading && !error && items.length > 0 && (
        <div className="filter-bar">
          <label htmlFor="status-filter">Filtrar por status</label>
          <select
            id="status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
          >
            <option value="ALL">Todos</option>
            <option value="ACTIVE">Ativo</option>
            <option value="EXPIRED">Expirado</option>
            <option value="DISABLED">Desativado</option>
          </select>
        </div>
      )}

      {!loading && !error && items.length > 0 && visibleItems.length === 0 && (
        <div className="alert alert-empty">
          Nenhum link com o status {STATUS_LABEL[statusFilter as LinkStatus]} —
          tente outro filtro.
          {hasMore && (
            <div style={{ marginTop: 12 }}>
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => void loadMore()}
                disabled={loadingMore}
              >
                {loadingMore
                  ? "Carregando..."
                  : "Carregar mais (pode haver links em páginas seguintes)"}
              </button>
            </div>
          )}
        </div>
      )}

      {!loading && !error && items.length > 0 && visibleItems.length > 0 && (
        <>
          <div className="link-table-wrap">
            <table className="link-table">
              <thead>
                <tr>
                  <th>Código</th>
                  <th>Destino</th>
                  <th>Cliques</th>
                  <th>Status</th>
                  <th>Criado em</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {visibleItems.map((link) => (
                  <tr key={link.code}>
                    <td className="code-cell">
                      <a
                        href={`/${link.code}`}
                        target="_blank"
                        rel="noreferrer"
                        title="Abrir o link curto"
                      >
                        {link.code}
                      </a>
                    </td>
                    <td>
                      <span className="dest" title={link.originalUrl}>
                        {link.originalUrl}
                      </span>
                    </td>
                    <td>{link.clickCount}</td>
                    <td>
                      <span className={statusBadgeClass(link.status)}>
                        {STATUS_LABEL[link.status]}
                      </span>
                    </td>
                    <td>{formatDate(link.createdAt)}</td>
                    <td>
                      <div className="row-actions">
                        <button
                          type="button"
                          className="btn-copy"
                          onClick={() => void handleCopy(link)}
                          title={`Copiar ${link.shortUrl}`}
                        >
                          {copiedCode === link.code ? "Copiado!" : "Copiar"}
                        </button>
                        <button
                          type="button"
                          className={
                            link.status === "DISABLED"
                              ? "btn-delete btn-delete-disabled"
                              : "btn-delete"
                          }
                          onClick={() => void handleDelete(link)}
                          disabled={
                            link.status === "DISABLED" || deletingCode !== null
                          }
                          title={
                            link.status === "DISABLED"
                              ? "Link já desativado"
                              : `Deletar /${link.code}`
                          }
                        >
                          {deletingCode === link.code
                            ? "Deletando..."
                            : "Deletar"}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {!loading && !error && items.length > 0 && (
        <div className="list-actions">
          {hasMore && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => void loadMore()}
              disabled={loadingMore}
            >
              {loadingMore ? "Carregando..." : "Carregar mais"}
            </button>
          )}
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => void loadFirstPage()}
          >
            Atualizar
          </button>
        </div>
      )}
    </section>
  );
}
