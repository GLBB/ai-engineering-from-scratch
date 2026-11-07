package dev.learning.agent.tool.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.learning.agent.tool.weather.WeatherModels.CurrentData;
import dev.learning.agent.tool.weather.WeatherModels.ForecastData;
import dev.learning.agent.tool.weather.WeatherModels.ForecastDay;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;

import dev.learning.agent.tool.ToolSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Open-Meteo HTTP/DTO boundary. No model, browser, plan, or evidence dependencies. API contracts:
 * https://open-meteo.com/en/docs and /en/docs/geocoding-api. A future completes only after the bounded body, so the
 * timeout covers body reads.
 */
public final class OpenMeteoClient implements WeatherProvider {
    private final dev.learning.agent.core.runtime.ExternalObserver observer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final URI geocoding;
    private final URI forecast;

    public OpenMeteoClient(ObjectMapper json) {
        this(json, dev.learning.agent.core.runtime.ExternalObserver.NOOP);
    }

    public OpenMeteoClient(ObjectMapper json, dev.learning.agent.core.runtime.ExternalObserver observer) {
        this(json, URI.create("https://geocoding-api.open-meteo.com/v1/search"),
            URI.create("https://api.open-meteo.com/v1/forecast"), observer);
    }

    // Loopback fixtures use these endpoints; production never reads endpoints from tool arguments.
    OpenMeteoClient(ObjectMapper json, URI geocoding, URI forecast) {
        this(json, geocoding, forecast, dev.learning.agent.core.runtime.ExternalObserver.NOOP);
    }

    OpenMeteoClient(ObjectMapper json, URI geocoding, URI forecast,
        dev.learning.agent.core.runtime.ExternalObserver observer) {
        this.observer = observer;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        for (LogicalType type : List.of(LogicalType.Float, LogicalType.Integer, LogicalType.Boolean)) {
            this.json.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        }
        this.geocoding = geocoding;
        this.forecast = forecast;
    }

    public List<ToolSource> sources() {
        return List.of(new ToolSource("Weather data by Open-Meteo", "https://open-meteo.com/"),
            new ToolSource("Location data by GeoNames", "https://www.geonames.org/"));
    }

    public List<GeoLocation> search(LocationQuery query, String city, WeatherCall call)
        throws IOException, InterruptedException {
        var params = new LinkedHashMap<String, String>();
        params.put("name", query.admin1() == null ? city : city + ", " + query.admin1());
        params.put("language", "zh");
        params.put("count", "20");
        if (query.countryCode() != null) {
            params.put("countryCode", query.countryCode());
        }
        Lookup body = get(geocoding, params, Lookup.class, call);
        if (Boolean.TRUE.equals(body.error())) {
            throw invalid("地理编码返回错误");
        }
        List<GeoLocation> result = new ArrayList<>();
        if (body.results() == null) {
            return List.of();
        }
        if (body.results().size() > 20) {
            throw invalid("地点候选过多");
        }
        for (Place place : body.results()) {
            if (place == null || place.id() == null || place.id() <= 0 || blank(place.name())
                || blank(place.countryCode()) || blank(place.country()) || blank(place.timezone())) {
                throw invalid("地点信息不完整");
            }
            coordinates(place.latitude(), place.longitude());
            try {
                ZoneId.of(place.timezone());
            } catch (RuntimeException e) {
                throw invalid("地点时区无效");
            }
            result.add(new GeoLocation(place.id(), place.name(), place.countryCode(), place.country(), place.admin1(),
                place.latitude(), place.longitude(), place.timezone()));
        }
        return List.copyOf(result);
    }

    public CurrentData current(GeoLocation location, WeatherCall call) throws IOException, InterruptedException {
        var params = coordinates(location);
        params.put("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m");
        Weather body = get(forecast, params, Weather.class, call);
        validate(body, location);
        Current value = body.current();
        Units units = body.currentUnits();
        if (value == null || units == null || blank(value.time()) || value.weatherCode() == null) {
            throw invalid("当前天气关键字段缺失");
        }
        number(value.temperature());
        unit(units.temperature(), "°C");
        List<String> missing = new ArrayList<>();
        optional(value.apparent(), "apparentTemperature", missing);
        optional(value.humidity(), "relativeHumidity", missing);
        optional(value.wind(), "windSpeed", missing);
        if (value.apparent() != null) {
            unit(units.apparent(), "°C");
        }
        if (value.humidity() != null) {
            unit(units.humidity(), "%");
            probability(value.humidity());
        }
        if (value.wind() != null) {
            unit(units.wind(), "km/h");
            nonnegative(value.wind());
        }
        String time;
        try {
            LocalDateTime local = LocalDateTime.parse(value.time());
            if (body.utcOffset() == null) {
                throw new IllegalArgumentException("missing UTC offset");
            }
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(body.utcOffset());
            if (!ZoneId.of(body.timezone()).getRules().getValidOffsets(local).contains(offset)) {
                throw new IllegalArgumentException("invalid offset");
            }
            time = local.atOffset(offset).toString();
        } catch (RuntimeException e) {
            throw invalid("当前天气时间无效");
        }
        return new CurrentData(time, body.latitude(), body.longitude(), value.temperature(), value.apparent(),
            value.humidity(), value.weatherCode(), value.wind(), missing);
    }

    public ForecastData forecast(GeoLocation location, int days, WeatherCall call)
        throws IOException, InterruptedException {
        var params = coordinates(location);
        params.put("forecast_days", Integer.toString(days));
        params.put("daily",
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max");
        Weather body = get(forecast, params, Weather.class, call);
        validate(body, location);
        Daily value = body.daily();
        Units units = body.dailyUnits();
        if (value == null || units == null) {
            throw invalid("逐日天气缺失");
        }
        size(value.time(), days);
        size(value.codes(), days);
        size(value.min(), days);
        size(value.max(), days);
        unit(units.min(), "°C");
        unit(units.max(), "°C");
        if (value.probability() != null) {
            size(value.probability(), days);
            unit(units.probability(), "%");
        }
        if (value.wind() != null) {
            size(value.wind(), days);
            unit(units.windMax(), "km/h");
        }
        List<ForecastDay> result = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            number(value.min().get(i));
            number(value.max().get(i));
            if (value.codes().get(i) == null || value.time().get(i) == null
                || value.min().get(i) > value.max().get(i)) {
                throw invalid("逐日关键数据不正确");
            }
            Double rain = value.probability() == null ? null : value.probability().get(i);
            Double wind = value.wind() == null ? null : value.wind().get(i);
            optional(rain, "days[" + i + "].precipitationProbabilityMax", missing);
            optional(wind, "days[" + i + "].windSpeedMax", missing);
            if (rain != null) {
                probability(rain);
            }
            if (wind != null) {
                nonnegative(wind);
            }
            int code = value.codes().get(i);
            result.add(new ForecastDay(value.time().get(i), code, WeatherCodes.describe(code), value.min().get(i),
                value.max().get(i), rain, wind));
        }
        return new ForecastData(body.latitude(), body.longitude(), result, missing);
    }

    private Map<String, String> coordinates(GeoLocation place) {
        var params = new LinkedHashMap<String, String>();
        params.put("latitude", Double.toString(place.latitude()));
        params.put("longitude", Double.toString(place.longitude()));
        params.put("timezone", place.timezone());
        params.put("temperature_unit", "celsius");
        params.put("wind_speed_unit", "kmh");
        return params;
    }

    private void validate(Weather body, GeoLocation place) throws WeatherFailure {
        if (Boolean.TRUE.equals(body.error()) || !place.timezone().equals(body.timezone())) {
            throw invalid("天气响应错误或时区不一致");
        }
        coordinates(body.latitude(), body.longitude());
    }

    private <T> T get(URI endpoint, Map<String, String> params, Class<T> type, WeatherCall call)
        throws IOException, InterruptedException {
        try (var span = observer.http("GET", endpoint)) {
            try {
                return get(endpoint, params, type, call, span);
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof WeatherFailure failure) {
                    span.error(failure.code());
                } else {
                    span.fail(error);
                }
                throw error;
            }
        }
    }

    private <T> T get(URI endpoint, Map<String, String> params, Class<T> type, WeatherCall call,
        dev.learning.agent.core.runtime.TraceSpan span) throws IOException, InterruptedException {
        String query = params.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        long timeout = call.requestTimeoutMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
            .timeout(Duration.ofMillis(timeout)).header("Accept", "application/json").GET().build();
        call.dispatched();
        var future = http.sendAsync(request, ignored -> new LimitedBody());
        AutoCloseable cancellation = call.control().onCancel(() -> future.cancel(true));
        try {
            HttpResponse<byte[]> response = future.get(timeout, TimeUnit.MILLISECONDS);
            span.attribute("http.response.status_code", response.statusCode());
            if (response.statusCode() >= 400) {
                span.error("HTTP_" + response.statusCode());
            }
            call.control().check();
            if (response.statusCode() == 429) {
                throw new WeatherFailure("WEATHER_RATE_LIMITED", "天气服务请求限流");
            }
            if (response.statusCode() != 200) {
                throw new WeatherFailure("WEATHER_UNAVAILABLE", "天气服务返回 HTTP " + response.statusCode());
            }
            try {
                T body = json.readValue(response.body(), type);
                if (body == null) {
                    throw invalid("天气响应为空");
                }
                return body;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw invalid("天气响应不是合法的结构化数据");
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new WeatherFailure("WEATHER_TIMEOUT", "天气服务完整响应超时");
        } catch (CancellationException e) {
            call.control().check();
            throw new WeatherFailure("WEATHER_UNAVAILABLE", "天气请求被中止");
        } catch (ExecutionException e) {
            call.control().check();
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new WeatherFailure("WEATHER_TIMEOUT", "天气服务完整响应超时");
            }
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof WeatherFailure) && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            if (cause instanceof WeatherFailure failure) {
                throw failure;
            }
            throw new WeatherFailure("WEATHER_UNAVAILABLE", "天气服务连接失败");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } finally {
            try {
                cancellation.close();
            } catch (Exception ignored) {
                /* Unregister resource action. */
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static WeatherFailure invalid(String message) {
        return new WeatherFailure("WEATHER_INVALID_RESPONSE", message);
    }

    private static void coordinates(Double lat, Double lon) throws WeatherFailure {
        number(lat);
        number(lon);
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw invalid("天气坐标越界");
        }
    }

    private static void number(Double value) throws WeatherFailure {
        if (value == null || !Double.isFinite(value)) {
            throw invalid("天气数值缺失或非法");
        }
    }

    private static void optional(Double value, String field, List<String> missing) throws WeatherFailure {
        if (value == null) {
            missing.add(field);
        } else {
            number(value);
        }
    }

    private static void probability(Double value) throws WeatherFailure {
        if (value < 0 || value > 100) {
            throw invalid("百分比越界");
        }
    }

    private static void nonnegative(Double value) throws WeatherFailure {
        if (value < 0) {
            throw invalid("风速为负值");
        }
    }

    private static void unit(String actual, String expected) throws WeatherFailure {
        if (!expected.equals(actual)) {
            throw invalid("天气单位不一致");
        }
    }

    private static void size(List<?> values, int days) throws WeatherFailure {
        if (values == null || values.size() != days) {
            throw invalid("天气数组长度不一致");
        }
    }

    /** Bounded subscriber limits memory before the entire response has arrived. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return body;
        }

        public void onSubscribe(Flow.Subscription next) {
            subscription = next;
            next.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 262_144) {
                    subscription.cancel();
                    body.completeExceptionally(invalid("天气响应体过大"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    record Lookup(List<Place> results, Boolean error) {
    }

    record Place(Long id, String name, @JsonProperty("country_code") String countryCode, String country, String admin1,
        Double latitude, Double longitude, String timezone) {
    }

    record Weather(Double latitude, Double longitude, String timezone,
        @JsonProperty("utc_offset_seconds") Integer utcOffset, Boolean error, Current current,
        @JsonProperty("current_units") Units currentUnits, Daily daily, @JsonProperty("daily_units") Units dailyUnits) {
    }

    record Current(String time, @JsonProperty("temperature_2m") Double temperature,
        @JsonProperty("apparent_temperature") Double apparent, @JsonProperty("relative_humidity_2m") Double humidity,
        @JsonProperty("weather_code") Integer weatherCode, @JsonProperty("wind_speed_10m") Double wind) {
    }

    record Daily(List<String> time, @JsonProperty("weather_code") List<Integer> codes,
        @JsonProperty("temperature_2m_min") List<Double> min, @JsonProperty("temperature_2m_max") List<Double> max,
        @JsonProperty("precipitation_probability_max") List<Double> probability,
        @JsonProperty("wind_speed_10m_max") List<Double> wind) {
    }

    record Units(@JsonProperty("temperature_2m") String temperature,
        @JsonProperty("apparent_temperature") String apparent, @JsonProperty("relative_humidity_2m") String humidity,
        @JsonProperty("wind_speed_10m") String wind, @JsonProperty("temperature_2m_min") String min,
        @JsonProperty("temperature_2m_max") String max,
        @JsonProperty("precipitation_probability_max") String probability,
        @JsonProperty("wind_speed_10m_max") String windMax) {
    }
}
