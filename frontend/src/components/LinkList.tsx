import { useCallback, useEffect, useState } from "react";
import { listLinks } from "../api";
import type { LinkResponse, LinkStatus } from "../types";

const PAGE_SIZE = 10;

type StatusFilter = "ALL" | LinkStatus;

const STATUS_LABEL: Record<LinkStatus, string> = {
  ACTIVE: "Ativo",
  EXPIRED: "Expirado",
  DISABLED: "Desativado"
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
  return date.toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric" });
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

  const visibleItems =
    statusFilter === "ALL" ? items : items.filter((link) => link.status === statusFilter);

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await listLinks(PAGE_SIZE);
      setItems(page.items);
      setHasMore(page.hasMore);
      setLastKey(page.lastEvaluatedKey);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Falha ao listar os links.");
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
      setError(err instanceof Error ? err.message : "Falha ao carregar mais links.");
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
            <button type="button" className="btn btn-secondary" onClick={() => void loadFirstPage()}>
              Tentar novamente
            </button>
          </div>
        </div>
      )}

      {!loading && !error && items.length === 0 && (
        <div className="alert alert-empty">
          Nenhum link criado ainda — crie o primeiro acima.
        </div>
      )}

      {!loading && !error && items.length > 0 && visibleItems.length === 0 && (
        <div className="alert alert-empty">
          Nenhum link com o status {STATUS_LABEL[statusFilter as LinkStatus]} — tente outro filtro.
        </div>
      )}

      {!loading && !error && items.length > 0 && visibleItems.length > 0 && (
        <>
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

          <div className="link-table-wrap">
            <table className="link-table">
              <thead>
                <tr>
                  <th>Código</th>
                  <th>Destino</th>
                  <th>Cliques</th>
                  <th>Status</th>
                  <th>Criado em</th>
                </tr>
              </thead>
              <tbody>
                {visibleItems.map((link) => (
                  <tr key={link.code}>
                    <td className="code-cell">{link.code}</td>
                    <td>
                      <span className="dest" title={link.originalUrl}>
                        {link.originalUrl}
                      </span>
                    </td>
                    <td>{link.clickCount}</td>
                    <td>
                      <span className={statusBadgeClass(link.status)}>{STATUS_LABEL[link.status]}</span>
                    </td>
                    <td>{formatDate(link.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="list-actions">
            {hasMore && (
              <button type="button" className="btn btn-ghost" onClick={() => void loadMore()} disabled={loadingMore}>
                {loadingMore ? "Carregando..." : "Carregar mais"}
              </button>
            )}
            <button type="button" className="btn btn-ghost" onClick={() => void loadFirstPage()}>
              Atualizar
            </button>
          </div>
        </>
      )}
    </section>
  );
}