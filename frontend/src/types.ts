export type LinkStatus = "ACTIVE" | "EXPIRED" | "DISABLED";

export interface LinkResponse {
  code: string;
  originalUrl: string;
  shortUrl: string;
  clickCount: number;
  active: boolean;
  createdAt: string | null;
  expiresAt: string | null;
  status: LinkStatus;
}

export interface PagedLinkResponse {
  items: LinkResponse[];
  lastEvaluatedKey: string | null;
  pageSize: number;
  hasMore: boolean;
}

export interface ApiError {
  status: number;
  message: string;
}