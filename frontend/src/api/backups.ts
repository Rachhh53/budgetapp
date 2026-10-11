import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { reloadEverything } from "./session";
import { SETTINGS_KEY } from "./settings";

export interface Backup {
  /** The file's name in the backup folder; what a restore names. */
  name: string;
  /** When it was made, on this PC's clock: `2026-10-01T07:02:00`. */
  createdAt: string;
  sizeBytes: number;
}

export interface BackupStatus {
  /** Where backups are saved. */
  folder: string;
  /** True until a folder has been chosen: backups are then inside Budget's own data. */
  usingDefaultFolder: boolean;
  keepCount: number;
  /** Newest first. */
  backups: Backup[];
  lastBackupAt: string | null;
  /** True when today's automatic backup could not be saved. */
  lastDailyBackupFailed: boolean;
}

/** One level of this PC's folders, for the "Choose folder" dialog. */
export interface FolderListing {
  /** The folder listed; null at the top, where the drives are. */
  path: string | null;
  /** The folder above; null when that is the top. */
  parent: string | null;
  folders: { name: string; path: string }[];
  /** Whether backups could be saved here. */
  writable: boolean;
}

export const BACKUPS_KEY = ["backups"] as const;
const FOLDERS_KEY = ["folders"] as const;

/** The phrase the backend insists on, so a stray request can never replace anything. */
const RESTORE_CONFIRMATION = "restore-backup";

export function useBackups() {
  return useQuery({ queryKey: BACKUPS_KEY, queryFn: () => api.get<BackupStatus>("/api/backups") });
}

export function useBackUpNow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<BackupStatus>("/api/backups", {}),
    onSuccess: (status) => queryClient.setQueryData(BACKUPS_KEY, status),
  });
}

/** Saves the folder backups go to; `null` goes back to Budget's own. */
export function useChooseBackupFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (path: string | null) => api.put<BackupStatus>("/api/backups/folder", { path }),
    onSuccess: (status) => {
      queryClient.setQueryData(BACKUPS_KEY, status);
      // The folder is one of the settings too.
      return queryClient.invalidateQueries({ queryKey: SETTINGS_KEY });
    },
  });
}

/** Replaces everything with a backup. Only call after the person has confirmed. */
export function useRestoreBackup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      api.post<{ restored: boolean }>("/api/backups/restore", { name, confirm: RESTORE_CONFIRMATION }),
    onSuccess: () => reloadEverything(queryClient),
  });
}

/** The folders inside `path`; `null` asks for the top (the usual places and the drives). */
export function useFolders(path: string | null) {
  return useQuery({
    queryKey: [...FOLDERS_KEY, path],
    queryFn: () => api.get<FolderListing>(path === null ? "/api/folders" : `/api/folders?path=${encodeURIComponent(path)}`),
    // Folders change outside the app; always ask again when a folder is opened.
    staleTime: 0,
    gcTime: 0,
  });
}

/** Makes a folder and answers with its (empty) listing, ready to be opened. */
export function useCreateFolder() {
  return useMutation({
    mutationFn: (folder: { parent: string; name: string }) => api.post<FolderListing>("/api/folders", folder),
  });
}
