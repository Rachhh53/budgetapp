package app.budget.server.api;

/**
 * A problem we want to tell the person about. The message goes to the screen as written,
 * so keep it kind, plain, and free of blame or internal detail.
 */
public final class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "Budget is locked. Please unlock it to continue.");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException notFound() {
        return new ApiException(404, "We couldn't find that.");
    }

    /** The request is fine in itself, but something else that exists stands in its way. */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
