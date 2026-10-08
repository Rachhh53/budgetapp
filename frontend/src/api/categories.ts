import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";

/** One per item. A category with a `parentId` is a sub-category. */
export interface Category {
  id: number;
  name: string;
  color: string;
  parentId: number | null;
  /** Recurring items filed under it, its sub-categories included. */
  itemCount: number;
  /** What those items add up to over a year. */
  perYearCents: number;
}

export interface CategoryInput {
  name: string;
  color: string;
  parentId: number | null;
}

/** Any number per item, for building reports. */
export interface Tag {
  id: number;
  name: string;
  color: string;
  itemCount: number;
  perYearCents: number;
}

export interface TagInput {
  name: string;
  color: string;
}

export const CATEGORIES_KEY = ["categories"] as const;
export const TAGS_KEY = ["tags"] as const;

export function useCategories() {
  return useQuery({ queryKey: CATEGORIES_KEY, queryFn: () => api.get<Category[]>("/api/categories") });
}

export function useTags() {
  return useQuery({ queryKey: TAGS_KEY, queryFn: () => api.get<Tag[]>("/api/tags") });
}

/** Creates a category, or changes the one with `id`. */
export function useSaveCategory(id?: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CategoryInput) =>
      id === undefined ? api.post<Category>("/api/categories", input) : api.put<Category>(`/api/categories/${id}`, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_KEY }),
  });
}

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<{ deleted: boolean }>(`/api/categories/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_KEY }),
  });
}

/** Creates a tag, or changes the one with `id`. */
export function useSaveTag(id?: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TagInput) =>
      id === undefined ? api.post<Tag>("/api/tags", input) : api.put<Tag>(`/api/tags/${id}`, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAGS_KEY }),
  });
}

export function useDeleteTag() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<{ deleted: boolean }>(`/api/tags/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TAGS_KEY }),
  });
}
