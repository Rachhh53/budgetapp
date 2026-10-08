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

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw UNREACHABLE;
  }

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

/**
 * Sends a request, starting a session first if the app is locked.
 *
 * Until the PIN exists (Phase 6) unlocking needs no input, so a 401 is answered by quietly
 * unlocking and trying once more. Phase 6 replaces this with the PIN screen.
 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  try {
    return await send<T>(method, path, body);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      await send("POST", SESSION_PATH);
      return send<T>(method, path, body);
    }
    throw error;
  }
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
  delete: <T>(path: string, body?: unknown) => request<T>("DELETE", path, body),
};
