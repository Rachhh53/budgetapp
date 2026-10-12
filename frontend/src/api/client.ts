/**
 * The only way the UI talks to the backend. Every response is the same envelope,
 * `{ data, error, status }`; this unwraps it and turns failures into an `ApiError`
 * whose message is already written for a person to read.
 */

interface Envelope<T> {
  data: T | null;
  error: string | null;
  status: number;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Status 0 = the request never got an answer. */
const UNREACHABLE = new ApiError(0, "We couldn't reach Budget on your PC. Please check that the app is still open.");
const UNEXPECTED = "Something went wrong on our side. Your data is safe — please try again.";
const SESSION_PATH = "/api/session";

/** The backend sets this on the answer to any request that changed something (and can be undone). */
const CHANGED_HEADER = "X-Budget-Changed";

const changeListeners = new Set<() => void>();

/**
 * Calls `listener` after every request that changed data, whichever screen made it. This is
 * how the Undo toast appears after every change without each form having to ask for it.
 * Returns the function that stops listening.
 */
export function onDataChanged(listener: () => void): () => void {
  changeListeners.add(listener);
  return () => {
    changeListeners.delete(listener);
  };
}

/** Files to send as one upload, each under the form field the backend reads. */
export class Upload {
  readonly files: readonly File[];

  constructor(files: readonly File[]) {
    this.files = files;
  }
}

/** A file the backend sent to be saved, with the name it gave it. */
export interface Download {
  blob: Blob;
  fileName: string;
}

/** A file the backend put in this PC's Downloads folder itself, in place of sending it back. */
export interface Saved {
  fileName: string;
  folder: string;
}

/**
 * How a file asked for came: saved on this PC by the backend (the desktop window saves a file
 * handed to it without a word, and refuses the ones after the first), or sent back to be saved
 * by the browser, as it is to a phone.
 */
export type Handed = { saved: Saved; download?: undefined } | { saved: null; download: Download };

/** What to tell someone whose file was saved for them: where it is and what it is called. */
export function savedMessage(saved: Saved): string {
  return `Saved in ${saved.folder} as ${saved.fileName}.`;
}

/** Sends the request itself; what the answer means is for the caller. */
async function call(method: string, path: string, body?: unknown): Promise<Response> {
  try {
    if (body instanceof Upload) {
      const form = new FormData();
      body.files.forEach((file) => form.append("file", file, file.name));
      // No Content-Type here: the browser writes it, with the boundary between the files.
      return await fetch(path, { method, credentials: "same-origin", body: form });
    }
    return await fetch(path, {
      method,
      credentials: "same-origin",
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw UNREACHABLE;
  }
}

/** The error an answer that is not a success carries: the backend's own words when it sent the envelope. */
async function failure(response: Response): Promise<ApiError> {
  try {
    const envelope = (await response.json()) as Envelope<unknown>;
    return new ApiError(response.status, envelope.error ?? UNEXPECTED);
  } catch {
    return new ApiError(response.status, UNEXPECTED);
  }
}

/** Asks for a file. A success is the file itself; anything else is the usual envelope with its message. */
async function fetchFile(method: string, path: string, body?: unknown): Promise<Download> {
  const response = await call(method, path, body);
  if (!response.ok) {
    throw await failure(response);
  }
  // RFC 5987, as the backend writes it: filename*=UTF-8''Medical%202026.pdf
  const named = /filename\*=UTF-8''([^;]+)/i.exec(response.headers.get("Content-Disposition") ?? "");
  return { blob: await response.blob(), fileName: named?.[1] ? decodeURIComponent(named[1]) : "download" };
}

/** Asks for a file to be saved on this PC; the answer says it was, or is the file itself. */
async function fetchHanded(method: string, path: string, body?: unknown): Promise<Handed> {
  const response = await call(method, path, body);
  if (!response.ok) {
    throw await failure(response);
  }
  if (response.headers.get("Content-Type")?.includes("application/json")) {
    const envelope = (await response.json()) as { data: { saved: Saved | null } | null };
    if (envelope.data?.saved) {
      return { saved: envelope.data.saved };
    }
    throw new ApiError(response.status, UNEXPECTED);
  }
  const named = /filename\*=UTF-8''([^;]+)/i.exec(response.headers.get("Content-Disposition") ?? "");
  return { saved: null, download: { blob: await response.blob(), fileName: named?.[1] ? decodeURIComponent(named[1]) : "download" } };
}

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await call(method, path, body);

  let envelope: Envelope<T>;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    throw new ApiError(response.status, UNEXPECTED);
  }
  if (!response.ok || envelope.data === null) {
    throw new ApiError(response.status, envelope.error ?? UNEXPECTED);
  }
  if (response.headers.get(CHANGED_HEADER) !== null) {
    changeListeners.forEach((listener) => listener());
  }
  return envelope.data;
}

const lockListeners = new Set<() => void>();

/**
 * Calls `listener` when a request finds the app locked and a PIN is needed to open it again
 * (the session ran out while the window sat idle, say). Returns the function that stops listening.
 */
export function onLocked(listener: () => void): () => void {
  lockListeners.add(listener);
  return () => {
    lockListeners.delete(listener);
  };
}

/** Whether unlocking takes a PIN. Kept in step with the session status by the app frame. */
let pinRequired = false;

export function setPinRequired(required: boolean) {
  pinRequired = required;
}

/** How a request is made and its answer read: as the JSON envelope, or as a file. */
type Sender<T> = (method: string, path: string, body?: unknown) => Promise<T>;

/**
 * Sends a request, starting a session first if the app is locked.
 *
 * Without a PIN, unlocking needs no input, so a 401 is answered by quietly unlocking and
 * trying once more. With a PIN there is nothing to do quietly: the lock screen is told to
 * show, and the request fails as locked.
 */
async function request<T>(method: string, path: string, body?: unknown, how: Sender<T> = send): Promise<T> {
  try {
    return await how(method, path, body);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      throw error;
    }
    if (!pinRequired) {
      try {
        await send("POST", SESSION_PATH);
        return await how(method, path, body);
      } catch (retryError) {
        // 401 here means a PIN was set since this window last asked; anything else is its own problem.
        if (!(retryError instanceof ApiError) || retryError.status !== 401) {
          throw retryError;
        }
      }
    }
    lockListeners.forEach((listener) => listener());
    throw error;
  }
}

/**
 * The same requests without the unlock-and-retry: for the session and PIN routes themselves,
 * where a 401 is the answer ("that PIN didn't match"), not a sign that the app is locked.
 */
export const direct = {
  get: <T>(path: string) => send<T>("GET", path),
  post: <T>(path: string, body?: unknown) => send<T>("POST", path, body),
  delete: <T>(path: string, body?: unknown) => send<T>("DELETE", path, body),
};

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
  delete: <T>(path: string, body?: unknown) => request<T>("DELETE", path, body),
  /** Sends files, as the browser sends a form with files in it. */
  upload: <T>(path: string, files: readonly File[]) => request<T>("POST", path, new Upload(files)),
  /** Asks for a file to save: a GET, or a POST when `body` says what the file should hold. */
  download: (path: string, body?: unknown) => request<Download>(body === undefined ? "GET" : "POST", path, body, fetchFile),
  /** Asks for a file, saved on this PC when the backend can do that (see `Handed`). */
  handOver: (path: string, body: unknown) => request<Handed>("POST", path, body, fetchHanded),
};

/**
 * Hands a file to the browser to save, as if a download link had been clicked. The link is made,
 * clicked and thrown away here because the file came from a request that needed a body.
 */
export function saveFile({ blob, fileName }: Download) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  // Not at once: the browser has to have started reading the file first.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
