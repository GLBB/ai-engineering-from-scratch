package dev.learning.agent.tool.weather;

import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;
import java.io.IOException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Conservative matching: population and provider ordering never resolve ambiguity. */
public final class CityResolver {
    public sealed interface Resolution permits Resolved, NotFound, Ambiguous {}
    public record Resolved(GeoLocation location, String searchedCity) implements Resolution {}
    public record NotFound(String city) implements Resolution {}
    public record Ambiguous(List<GeoLocation> candidates) implements Resolution {
        public Ambiguous { candidates = List.copyOf(candidates); }
    }
    private final WeatherProvider provider;
    public CityResolver(WeatherProvider provider) { this.provider = provider; }
    public Resolution resolve(LocationQuery query, WeatherCall call) throws IOException, InterruptedException {
        String city = query.city();
        List<GeoLocation> found = provider.search(query, city, call);
        if (found.isEmpty() && "CN".equals(query.countryCode()) && city.endsWith("市") && city.length() > 1) {
            city = city.substring(0, city.length() - 1);
            found = provider.search(query, city, call);
        }
        var unique = new LinkedHashMap<Long, GeoLocation>();
        for (GeoLocation place : found) {
            if (query.countryCode() != null && !query.countryCode().equals(place.countryCode())) continue;
            if (query.admin1() != null && !same(query.admin1(), place.admin1())) continue;
            unique.putIfAbsent(place.id(), place);
        }
        if (unique.isEmpty()) return new NotFound(query.city());
        if (unique.size() == 1 && same(city, unique.values().iterator().next().name()))
            return new Resolved(unique.values().iterator().next(), city);
        return new Ambiguous(unique.values().stream().limit(5).toList());
    }
    private static boolean same(String first, String second) {
        return second != null && normalized(first).equals(normalized(second));
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
