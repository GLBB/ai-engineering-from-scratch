package dev.learning.agent.tool.weather;

import dev.learning.agent.telemetry.SpanAttributes;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;
import dev.learning.agent.core.runtime.ExternalObserver;
import dev.learning.agent.core.runtime.TraceSpan;
import java.io.IOException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Conservative matching: population and provider ordering never resolve ambiguity. */
public final class CityResolver {
    public sealed interface Resolution permits Resolved, NotFound, Ambiguous {}

    public record Resolved(GeoLocation location, String searchedCity) implements Resolution {
    }

    public record NotFound(String city) implements Resolution {
    }

    public record Ambiguous(List<GeoLocation> candidates) implements Resolution {
        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    private final WeatherProvider provider;
    private final ExternalObserver observer;

    public CityResolver(WeatherProvider provider) {
        this(provider, ExternalObserver.NOOP);
    }

    public CityResolver(WeatherProvider provider, ExternalObserver observer) {
        this.provider = provider;
        this.observer = observer;
    }

    public Resolution resolve(LocationQuery query, WeatherCall call) throws IOException, InterruptedException {
        try (TraceSpan span = observer.operation("agent_loop.weather.resolve")) {
            try {
                return resolve(query, call, span);
            } catch (IOException | InterruptedException | RuntimeException error) {
                span.fail(error);
                throw error;
            }
        }
    }

    private Resolution resolve(LocationQuery query, WeatherCall call, TraceSpan span)
        throws IOException, InterruptedException {
        String city = query.city();
        List<GeoLocation> found = provider.search(query, city, call);
        if (found.isEmpty() && "CN".equals(query.countryCode()) && city.endsWith("市") && city.length() > 1) {
            span.attributes(new SpanAttributes.WeatherFallback(true));
            city = city.substring(0, city.length() - 1);
            found = provider.search(query, city, call);
        }
        var unique = new LinkedHashMap<Long, GeoLocation>();
        for (GeoLocation place : found) {
            if (query.countryCode() != null && !query.countryCode().equals(place.countryCode())) {
                continue;
            }
            if (query.admin1() != null && !same(query.admin1(), place.admin1())) {
                continue;
            }
            unique.putIfAbsent(place.id(), place);
        }
        span.attributes(new SpanAttributes.WeatherCandidates(unique.size()));
        if (unique.isEmpty()) {
            span.attributes(new SpanAttributes.WeatherResolution("NOT_FOUND"));
            return new NotFound(query.city());
        }
        if (unique.size() == 1 && same(city, unique.values().iterator().next().name())) {
            span.attributes(new SpanAttributes.WeatherResolution("RESOLVED"));
            return new Resolved(unique.values().iterator().next(), city);
        }
        span.attributes(new SpanAttributes.WeatherResolution("AMBIGUOUS"));
        return new Ambiguous(unique.values().stream().limit(5).toList());
    }

    private static boolean same(String first, String second) {
        return second != null && normalized(first).equals(normalized(second));
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
