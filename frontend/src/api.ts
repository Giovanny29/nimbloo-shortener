import { ApiError, type LinkResponse, type PagedLinkResponse } from "./types";

const BASE_URL = "/api/v1/links";

async function parseError(response: Response): Promise<ApiError> {
  let message = `Falha na requisição (HTTP ${response.status})`;
  let fieldErrors: Record<string, string> | undefined;
  try {
    const body = await response.json();
    if (typeof body?.message === "string") {
      message = body.message;
    }
    if (body?.fieldErrors && typeof body.fieldErrors === "object") {
      fieldErrors = body.fieldErrors;
    }
  } catch {}
  return new ApiError(response.status, message, fieldErrors);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    throw await parseError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function createLink(params: {
  url: string;
  alias?: string;
  expiresAt?: string;
}): Promise<LinkResponse> {
  const body: Record<string, string> = { url: params.url };
  if (params.alias?.trim()) {
    body.alias = params.alias.trim();
  }
  if (params.expiresAt) {
    body.expiresAt = params.expiresAt;
  }
  return request<LinkResponse>(BASE_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function listLinks(
  pageSize = 10,
  lastKey?: string,
): Promise<PagedLinkResponse> {
  const query = new URLSearchParams({ pageSize: String(pageSize) });
  if (lastKey) {
    query.set("lastKey", lastKey);
  }
  return request<PagedLinkResponse>(`${BASE_URL}?${query.toString()}`);
}

export function deleteLink(code: string): Promise<void> {
  return request<void>(`${BASE_URL}/${encodeURIComponent(code)}`, {
    method: "DELETE",
  });
}
