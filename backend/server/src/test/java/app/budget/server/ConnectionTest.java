package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * One connection carrying several requests, the way a browser and the tests' client send them. A
 * body can arrive just after its headers on a busy machine; the connection must stay usable after
 * a route that never reads one (Undo), and after a refusal, which reads none either.
 */
class ConnectionTest extends ApiTestBase {

    @Test
    void staysOpenAfterARouteThatTakesNoBody() throws Exception {
        assertThat(lateBodyThenHealth(cookie)).containsExactly("HTTP/1.1 200 OK", "HTTP/1.1 200 OK");
    }

    @Test
    void staysOpenAfterARefusal() throws Exception {
        assertThat(lateBodyThenHealth(null)).containsExactly("HTTP/1.1 401 Unauthorized", "HTTP/1.1 200 OK");
    }

    /** Sends Undo with its body a moment after its headers, then a second request; returns both status lines. */
    private String[] lateBodyThenHealth(String cookie) throws Exception {
        URI address = server.uri("/");
        String host = address.getHost() + ":" + address.getPort();
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            out.write(("POST /api/undo HTTP/1.1\r\nHost: " + host + "\r\n"
                    + (cookie == null ? "" : "Cookie: " + cookie + "\r\n")
                    + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(300);
            out.write("{}".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = readResponse(in);

            out.write(("GET /api/health HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String[] {first, readResponse(in)};
        }
    }

    /** The status line, having read past the headers and body; marked when the server says it will close. */
    private static String readResponse(BufferedReader in) throws Exception {
        String status = in.readLine();
        int length = 0;
        boolean closes = false;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
            closes |= lower.equals("connection: close");
        }
        in.skip(length);
        return status + (closes ? " (closing)" : "");
    }
}
