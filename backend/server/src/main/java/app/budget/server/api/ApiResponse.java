package app.budget.server.api;

import io.javalin.http.Context;

/**
 * The one shape every API response takes: {@code { data, error, status }}.
 * Exactly one of {@code data} and {@code error} is set.
 */
public record ApiResponse<T>(T data, String error, int status) {

    public static <T> void ok(Context ctx, T data) {
        send(ctx, 200, data, null);
    }

    public static <T> void send(Context ctx, int status, T data) {
        send(ctx, status, data, null);
    }

    public static void fail(Context ctx, int status, String message) {
        send(ctx, status, null, message);
    }

    private static <T> void send(Context ctx, int status, T data, String error) {
        ctx.status(status).json(new ApiResponse<>(data, error, status));
    }
}
