package dev.learning.agent.tool.weather;

import dev.learning.agent.core.AgentMode;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunContexts;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.AssistantTurn;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.AgentTool;
import dev.learning.agent.tool.ToolRegistry;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolSource;
import dev.learning.agent.tool.ToolStatus;
import dev.learning.agent.tool.weather.WeatherModels.CurrentData;
import dev.learning.agent.tool.weather.WeatherModels.CurrentWeather;
import dev.learning.agent.tool.weather.WeatherModels.ForecastData;
import dev.learning.agent.tool.weather.WeatherModels.ForecastDay;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherToolTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T04:00:00Z"), ZoneOffset.UTC);
    static final GeoLocation BEIJING = new GeoLocation(1, "北京", "CN", "中国", "北京市", 39.9075, 116.39723, "Asia/Shanghai");

    static class Provider implements WeatherProvider {
        public List<ToolSource> sources() {
            return List.of(new ToolSource("Weather fixture", "https://open-meteo.com/"),
                new ToolSource("Locations fixture", "https://www.geonames.org/"));
        }

        List<GeoLocation> places = List.of(BEIJING);
        final List<String> searches = new ArrayList<>();
        int weatherCalls;
        String failure;
        boolean fallback;
        boolean wrongDates;

        public List<GeoLocation> search(LocationQuery query, String city, WeatherCall call) throws IOException {
            call.dispatched();
            searches.add(city);
            if (failure != null) {
                throw new WeatherFailure(failure, "controlled provider failure");
            }
            return fallback && city.endsWith("市") ? List.of() : places;
        }

        public CurrentData current(GeoLocation place, WeatherCall call) {
            call.dispatched();
            weatherCalls++;
            return new CurrentData("2026-11-01T12:00+08:00", 39.9, 116.4, 16.5, null, 30.0, 3, null,
                List.of("apparentTemperature", "windSpeed"));
        }

        public ForecastData forecast(GeoLocation place, int count, WeatherCall call) {
            call.dispatched();
            weatherCalls++;
            LocalDate start = LocalDate.now(CLOCK.withZone(ZoneId.of(place.timezone()))).plusDays(wrongDates ? 1 : 0);
            List<ForecastDay> days = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                days.add(new ForecastDay(start.plusDays(i).toString(), 3, "阴", 5, 16, null, 10.0));
            }
            return new ForecastData(39.9, 116.4, days, List.of("precipitationProbabilityMax"));
        }
    }

    private AgentTool tool(boolean forecast, Provider provider) {
        WeatherService service = new WeatherService(provider, CLOCK);
        return forecast ? new WeatherForecastTool(service) : new CurrentWeatherTool(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsTypedFactsUnitsAndAttribution(boolean forecast) throws Exception {
        Provider provider = new Provider();
        AgentTool tool = tool(forecast, provider);
        ToolResult result =
            tool.execute(tool.decode("{\"city\":\"北京\",\"country_code\":\"cn\",\"admin1\":\"北京市\"}"), null, null);
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.executed());
        assertTrue(result.hasValue());
        assertEquals(2, result.sources().size());
        var value = StrictJson.object(result.content());
        assertEquals("Asia/Shanghai", value.path("timezone").asText());
        assertEquals("°C", value.path("temperatureUnit").asText());
        assertEquals(2, value.path("httpRequests").asInt());
        if (forecast) {
            assertEquals(7, value.path("forecast").path("days").size());
            assertTrue(value.at("/forecast/days/0/precipitationProbabilityMax").isNull());
        } else {
            assertTrue(value.at("/current/apparentTemperature").isNull());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousCityNeverChoosesByPopulationOrOrder(boolean forecast) throws Exception {
        Provider provider = new Provider();
        provider.places = List.of(BEIJING, new GeoLocation(2, "北京", "CN", "中国", "重庆市", 30, 108, "Asia/Shanghai"));
        AgentTool tool = tool(forecast, provider);
        ToolResult result = tool.execute(tool.decode("{\"city\":\"北京\"}"), null, null);
        assertEquals(ToolStatus.NEEDS_INPUT, result.status());
        assertFalse(result.hasValue());
        assertTrue(result.executed());
        assertEquals(0, provider.weatherCalls);
        assertTrue(result.content().contains("重庆市"));
        assertTrue(result.content().contains("北京市"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noMatchesAreNotUsableWeather(boolean forecast) throws Exception {
        Provider provider = new Provider();
        provider.places = List.of();
        AgentTool tool = tool(forecast, provider);
        ToolResult result = tool.execute(tool.decode("{\"city\":\"不存在\"}"), null, null);
        assertEquals("LOCATION_NOT_FOUND", result.errorCode());
        assertFalse(result.hasValue());
        assertTrue(result.executed());
        assertEquals(0, provider.weatherCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerFailureIsBoundedAndNotRetried(boolean forecast) throws Exception {
        Provider provider = new Provider();
        provider.failure = "WEATHER_RATE_LIMITED";
        AgentTool tool = tool(forecast, provider);
        ToolResult result = tool.execute(tool.decode("{\"city\":\"北京\"}"), null, null);
        assertEquals("WEATHER_RATE_LIMITED", result.errorCode());
        assertTrue(result.executed());
        assertEquals(1, provider.searches.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledBeforeHttpDoesNotCountAsExecuted(boolean forecast) throws Exception {
        Provider provider = new Provider();
        AgentTool tool = tool(forecast, provider);
        RunContext context = RunContexts.create(UUID.randomUUID(), AgentMode.REACT,
            (r, t, c) -> new AssistantTurn("", List.of(), "stop"), new ToolRegistry(), e -> {
            }, (k, c, s, r) -> false);
        try {
            context.control().cancel("test cancellation");
            ToolResult result = tool.execute(tool.decode("{\"city\":\"北京\"}"), new RunScope("TEST", null, 0), context);
            assertEquals(ToolStatus.CANCELLED, result.status());
            assertFalse(result.executed());
            assertTrue(provider.searches.isEmpty());
        } finally {
            Thread.interrupted();
            context.control().close();
        }
    }

    @Test
    void strictParametersDoNotSendRequests() {
        Provider provider = new Provider();
        AgentTool current = tool(false, provider);
        AgentTool forecast = tool(true, provider);
        for (String bad : List.of("\"7\"", "0", "8", "7.5", "null")) {
            assertThrows(IOException.class, () -> forecast.decode("{\"city\":\"北京\",\"days\":" + bad + "}"));
        }
        for (String args : List.of("{\"city\":\"北京\",\"days\":1}", "{\"city\":\"北京市，中国\"}",
            "{\"city\":\"北京\",\"country_code\":null}", "{\"city\":\"北京\",\"extra\":1}",
            "{\"city\":\"北京\",\"city\":\"上海\"}")) {
            assertThrows(IOException.class, () -> current.decode(args));
        }
        assertTrue(provider.searches.isEmpty());
    }

    @Test
    void controlledSuffixFallbackAndAdminFilter() throws Exception {
        Provider provider = new Provider();
        provider.fallback = true;
        AgentTool tool = tool(true, provider);
        ToolResult result = tool.execute(
            tool.decode("{\"city\":\"北京市\",\"country_code\":\"CN\",\"admin1\":\"北京市\",\"days\":1}"), null, null);
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(List.of("北京市", "北京"), provider.searches);
        assertEquals("北京", StrictJson.object(result.content()).path("searchedCity").asText());
    }

    @Test
    void prefixMatchRequiresConfirmationEvenWithOneCandidate() throws Exception {
        Provider provider = new Provider();
        AgentTool tool = tool(true, provider);
        ToolResult result = tool.execute(tool.decode("{\"city\":\"北\"}"), null, null);
        assertEquals(ToolStatus.NEEDS_INPUT, result.status());
        assertEquals(0, provider.weatherCalls);
    }

    @Test
    void localDateUsesCityZoneAndDateMismatchFails() throws Exception {
        Provider provider = new Provider();
        provider.places = List.of(new GeoLocation(3, "纽约", "US", "美国", "纽约州", 40.7, -74, "America/New_York"));
        WeatherService service =
            new WeatherService(provider, Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC));
        var control = new dev.learning.agent.core.runtime.DefaultRunControl(java.time.Duration.ofSeconds(30));
        try {
            CurrentWeather current = service.current(new LocationQuery("纽约", "US", null), new WeatherCall(control));
            assertEquals("2026-10-31", current.localDate());
        } finally {
            control.close();
        }
        provider.places = List.of(BEIJING);
        provider.wrongDates = true;
        AgentTool tool = tool(true, provider);
        assertEquals("WEATHER_INVALID_RESPONSE",
            tool.execute(tool.decode("{\"city\":\"北京\"}"), null, null).errorCode());
    }
}
