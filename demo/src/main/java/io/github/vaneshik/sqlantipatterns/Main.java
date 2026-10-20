package io.github.vaneshik.sqlantipatterns;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;

public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SQL_BYTES = 16_384;
    private static final Map<String, String> ASSETS = Map.ofEntries(
            Map.entry("/", "text/html; charset=utf-8"),
            Map.entry("/app.js", "text/javascript; charset=utf-8"),
            Map.entry("/style.css", "text/css; charset=utf-8"),
            Map.entry("/examples/distinct-join.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/exists.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/without-key.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/redundant-distinct.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/distinct-group-by.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/group-filter.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/null-comparison.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/null-filter.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/left-join-filter.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/cartesian-product.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/is-null.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/join-in-where.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/having-count.sql", "text/plain; charset=utf-8"),
            Map.entry("/examples/left-join-is-null.sql", "text/plain; charset=utf-8"));

    public static void main(String[] args) throws IOException {
        if (args.length > 2) {
            throw new IllegalArgumentException("Использование: java -jar sql-demo.jar [port] [bind-address]");
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        HttpServer server = createServer(new InetSocketAddress(host, port));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        server.start();
        System.out.printf("SQL demo: http://%s:%d%n", host, server.getAddress().getPort());
    }

    static HttpServer createServer(InetSocketAddress address) throws IOException {
        HttpServer server = HttpServer.create(address, 32);
        server.createContext("/", Main::handle);
        return server;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            boolean analyze = path.equals("/api/analyze");
            if (!analyze && !ASSETS.containsKey(path)) {
                sendJson(exchange, 404, Map.of("error", "Страница не найдена."));
                return;
            }
            String method = analyze ? "POST" : "GET";
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.getResponseHeaders().set("Allow", method);
                sendJson(exchange, 405, Map.of("error", "Для этого адреса нужен " + method + "."));
                return;
            }
            if (!analyze) {
                String resource = path.startsWith("/examples/") ? path
                        : path.equals("/") ? "/web/index.html" : "/web" + path;
                try (var stream = Main.class.getResourceAsStream(resource)) {
                    if (stream == null) {
                        throw new IOException("Missing resource: " + resource);
                    }
                    send(exchange, 200, ASSETS.get(path), stream.readAllBytes());
                }
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_SQL_BYTES + 1);
            if (body.length > MAX_SQL_BYTES) {
                sendJson(exchange, 413, Map.of("error", "Запрос слишком длинный: максимум 16 КБ."));
                return;
            }
            String sql = new String(body, StandardCharsets.UTF_8);
            if (sql.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Введите SQL-запрос."));
                return;
            }
            try {
                sendJson(exchange, 200, new Analyzer().analyze(sql));
            } catch (SqlParseException | ValidationException | RelConversionException e) {
                sendJson(exchange, 422, Map.of("error", e.getMessage()));
            } catch (RuntimeException e) {
                e.printStackTrace(System.err);
                sendJson(exchange, 500, Map.of("error", "Calcite не смог обработать запрос."));
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy",
                "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
