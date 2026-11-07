package dev.learning.agent.tool.weather;

import dev.learning.agent.tool.weather.WeatherModels.CurrentData;
import dev.learning.agent.tool.weather.WeatherModels.CurrentWeather;
import dev.learning.agent.tool.weather.WeatherModels.DailyForecast;
import dev.learning.agent.tool.weather.WeatherModels.ForecastData;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import dev.learning.agent.tool.ToolSource;
import dev.learning.agent.core.runtime.ExternalObserver;

/** Orchestrates location resolution and facts; it never accesses an agent RunContext. */
public final class WeatherService {
    public static final class LocationRequired extends Exception {
        private final List<GeoLocation> candidates;

        public LocationRequired(List<GeoLocation> candidates) {
            this.candidates = List.copyOf(candidates);
        }

        public List<GeoLocation> candidates() {
            return candidates;
        }
    }

    private final WeatherProvider provider;
    private final CityResolver resolver;
    private final Clock clock;

    public WeatherService(WeatherProvider provider, Clock clock) {
        this(provider, clock, ExternalObserver.NOOP);
    }

    public WeatherService(WeatherProvider provider, Clock clock, ExternalObserver observer) {
        this.provider = provider;
        resolver = new CityResolver(provider, observer);
        this.clock = clock;
    }

    public List<ToolSource> sources() {
        return provider.sources();
    }

    public CurrentWeather current(LocationQuery query, WeatherCall call)
        throws IOException, InterruptedException, LocationRequired {
        var resolved = resolve(query, call);
        CurrentData data = provider.current(resolved.location(), call);
        String today = today(resolved.location());
        return new CurrentWeather(query, resolved.searchedCity(), resolved.location(), resolved.location().timezone(),
            today, clock.instant().toString(), "MODEL_ESTIMATE", "°C", "km/h",
            WeatherCodes.describe(data.weatherCode()), data, call.requests());
    }

    public DailyForecast forecast(LocationQuery query, int days, WeatherCall call)
        throws IOException, InterruptedException, LocationRequired {
        var resolved = resolve(query, call);
        ForecastData data = provider.forecast(resolved.location(), days, call);
        LocalDate start = LocalDate.parse(today(resolved.location()));
        if (data.days().size() != days) {
            throw new WeatherFailure("WEATHER_INVALID_RESPONSE", "天气预报天数不完整");
        }
        for (int i = 0; i < days; i++) {
            if (!start.plusDays(i).toString().equals(data.days().get(i).date())) {
                throw new WeatherFailure("WEATHER_INVALID_RESPONSE", "天气预报日期与当地今天不一致，请重新查询");
            }
        }
        return new DailyForecast(query, resolved.searchedCity(), resolved.location(), resolved.location().timezone(),
            start.toString(), clock.instant().toString(), "MODEL_FORECAST", "°C", "km/h", data, call.requests());
    }

    private CityResolver.Resolved resolve(LocationQuery query, WeatherCall call)
        throws IOException, InterruptedException, LocationRequired {
        var result = resolver.resolve(query, call);
        if (result instanceof CityResolver.Resolved resolved) {
            return resolved;
        }
        if (result instanceof CityResolver.Ambiguous ambiguous) {
            throw new LocationRequired(ambiguous.candidates());
        }
        throw new WeatherFailure("LOCATION_NOT_FOUND", "未找到地点：" + query.city() + "；请提供城市名、国家或省级地区");
    }

    private String today(GeoLocation place) {
        return LocalDate.now(clock.withZone(ZoneId.of(place.timezone()))).toString();
    }
}
