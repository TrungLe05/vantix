// src/types/common.ts
export interface ApiResponse<T> {
  code: number;
  message?: string;
  result?: T;
}

export interface PaginatedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}