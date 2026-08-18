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

export class ApiError extends Error {
  status: number;
  fieldErrors?: Record<string, string>;

  constructor(
    status: number,
    message: string,
    fieldErrors?: Record<string, string>,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}