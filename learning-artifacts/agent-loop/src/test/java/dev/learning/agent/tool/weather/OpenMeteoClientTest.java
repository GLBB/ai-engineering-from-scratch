package dev.learning.agent.tool.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.learning.agent.core.runtime.DefaultRunControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenMeteoClientTest {
    private HttpServer server;
    private DefaultRunControl control;
    private OpenMeteoClient client;
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private static final String CURRENT = """
            {"latitude":39.9,"longitude":116.4,"timezone":"Asia/Shanghai","utc_offset_seconds":28800,
            "current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","wind_speed_10m":"km/h"},
            "current":{"time":"2026-11-01T12:00","temperature_2m":16,"apparent_temperature":null,"relative_humidity_2m":30,"weather_code":3,"wind_speed_10m":12}}
            """;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.get().equals("limited") ? 429 : 200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/forecast");
        client = new OpenMeteoClient(new ObjectMapper(), endpoint, endpoint);
        control = new DefaultRunControl(Duration.ofSeconds(10)); reply.set(CURRENT);
    }
    @AfterEach void close() { control.close(); server.stop(0); }
    @Test void sendsFixedCoordinatesAndParsesUnitsTimeAndMissingValues() throws Exception {
        var data = client.current(WeatherToolTest.BEIJING, new WeatherCall(control));
        assertEquals("2026-11-01T12:00+08:00", data.dataTime()); assertNull(data.apparentTemperature());
        assertTrue(data.missingFields().contains("apparentTemperature"));
        assertTrue(query.get().contains("timezone=Asia%2FShanghai")); assertTrue(query.get().contains("current="));
    }
    @Test void forecastArraysAndNullOptionalValuesAreValidated() throws Exception {
        reply.set("""
                {"latitude":39.9,"longitude":116.4,"timezone":"Asia/Shanghai",
                "daily_units":{"temperature_2m_min":"°C","temperature_2m_max":"°C","precipitation_probability_max":"%","wind_speed_10m_max":"km/h"},
                "daily":{"time":["2026-11-01"],"weather_code":[3],"temperature_2m_min":[5],"temperature_2m_max":[16],"precipitation_probability_max":[null],"wind_speed_10m_max":[12]}}
                """);
        var forecast = client.forecast(WeatherToolTest.BEIJING, 1, new WeatherCall(control));
        assertNull(forecast.days().get(0).precipitationProbabilityMax());
        assertThrows(WeatherFailure.class, () -> client.forecast(WeatherToolTest.BEIJING, 2, new WeatherCall(control)));
    }
    @Test void wrongUnitsAndMissingCriticalFieldsFail() throws Exception {
        for (String invalid : new String[]{CURRENT.replace("°C", "°F"), CURRENT.replace("\"temperature_2m\":16", "\"temperature_2m\":null"),
                CURRENT.replace("\"temperature_2m\":16", "\"temperature_2m\":\"16\"")}) {
            reply.set(invalid);
            assertEquals("WEATHER_INVALID_RESPONSE", assertThrows(WeatherFailure.class,
                    () -> client.current(WeatherToolTest.BEIJING, new WeatherCall(control))).code());
        }
    }
    @Test void rateLimitsAreNotRetried() {
        reply.set("limited"); WeatherCall call = new WeatherCall(control);
        assertEquals("WEATHER_RATE_LIMITED", assertThrows(WeatherFailure.class, () -> client.current(WeatherToolTest.BEIJING, call)).code());
        assertEquals(1, call.requests());
    }
    @Test void oversizedBodyIsRejectedBeforeParsing() {
        reply.set("x".repeat(262_145));
        assertEquals("WEATHER_INVALID_RESPONSE", assertThrows(WeatherFailure.class,
                () -> client.current(WeatherToolTest.BEIJING, new WeatherCall(control))).code());
    }
    @Test void cancellationInterruptsWaitingForBody() throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0); headers.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        URI slow = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        var provider = new OpenMeteoClient(new ObjectMapper(), slow, slow);
        var result = CompletableFuture.runAsync(() -> assertThrows(InterruptedException.class,
                () -> provider.current(WeatherToolTest.BEIJING, new WeatherCall(control))));
        try { assertTrue(headers.await(2, TimeUnit.SECONDS)); control.cancel("test"); result.get(2, TimeUnit.SECONDS); }
        finally { release.countDown(); }
    }
    @Test void deadlineAlsoCoversAResponseBodyThatNeverCompletes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(' '); body.flush();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        URI slow = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow-body");
        var provider = new OpenMeteoClient(new ObjectMapper(), slow, slow);
        try {
            WeatherFailure failure = assertThrows(WeatherFailure.class, () -> provider.current(WeatherToolTest.BEIJING, new WeatherCall(control, Duration.ofSeconds(1))));
            assertEquals("WEATHER_TIMEOUT", failure.code());
        } finally { release.countDown(); }
    }
    @Test void toolDeadlineRejectsBeforeDispatch() {
        WeatherCall call = new WeatherCall(control, Duration.ZERO);
        assertEquals("WEATHER_TIMEOUT", assertThrows(WeatherFailure.class, () -> client.current(WeatherToolTest.BEIJING, call)).code());
        assertEquals(0, call.requests());
    }
}
