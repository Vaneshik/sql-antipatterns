package io.github.vaneshik.sqlantipatterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer server;
    private static HttpClient client;
    private static URI base;

    @BeforeAll
    static void start() throws Exception {
        server = Main.createServer(new InetSocketAddress("127.0.0.1", 0));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.stop(0);
    }

    @Test
    void servesTheExampleAndAnalyzesItThroughHttp() throws Exception {
        var example = client.send(HttpRequest.newBuilder(base.resolve("/examples/distinct-join.sql"))
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, example.statusCode());
        var response = analyze(example.body());
        assertEquals(200, response.statusCode());
        var result = JSON.readTree(response.body());
        assertEquals("FOUND", result.path("status").asText());
        assertEquals("LogicalAggregate", result.path("tree").path("type").asText());
        assertEquals(4, result.path("matchedNodeIds").size());
        assertTrue(result.path("explanation").asText().contains("размножать"));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELEC id FROM customer", "SELECT id FROM unknown_table",
            "SELECT id FROM customer; SELECT id FROM orders", "DELETE FROM customer",
            "UPDATE customer SET name = 'test'", "INSERT INTO customer VALUES (1, 'test')"
    })
    void rejectsInvalidSqlWithoutReturningAPlan(String sql) throws Exception {
        var response = analyze(sql);
        assertEquals(422, response.statusCode());
        var result = JSON.readTree(response.body());
        assertFalse(result.path("error").asText().isBlank());
        assertFalse(result.has("tree"));
    }

    @Test
    void rejectsEmptyAndOversizedRequests() throws Exception {
        assertEquals(400, analyze(" \n\t").statusCode());
        assertEquals(413, analyze("я".repeat(8_193)).statusCode());
    }

    @Test
    void servesPackagedAssetsWithTheirContentTypes() throws Exception {
        for (String path : new String[] {"/", "/style.css", "/app.js"}) {
            var response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            String type = switch (path) {
                case "/" -> "text/html";
                case "/style.css" -> "text/css";
                default -> "text/javascript";
            };
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(type));
            assertFalse(response.body().isBlank());
        }
    }

    @Test
    void routesOnlyExactPathsAndSupportedMethods() throws Exception {
        var wrongMethod = client.send(HttpRequest.newBuilder(base.resolve("/api/analyze")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
        for (String path : new String[] {"/api/analyze/extra", "/pom.xml", "/examples/missing.sql"}) {
            var response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }
    }

    private HttpResponse<String> analyze(String sql) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve("/api/analyze"))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
