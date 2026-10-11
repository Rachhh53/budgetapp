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

/**
 * Sends a request, starting a session first if the app is locked.
 *
 * Without a PIN, unlocking needs no input, so a 401 is answered by quietly unlocking and
 * trying once more. With a PIN there is nothing to do quietly: the lock screen is told to
 * show, and the request fails as locked.
 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  try {
    return await send<T>(method, path, body);
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      throw error;
    }
    if (!pinRequired) {
      try {
        await send("POST", SESSION_PATH);
        return await send<T>(method, path, body);
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
};
