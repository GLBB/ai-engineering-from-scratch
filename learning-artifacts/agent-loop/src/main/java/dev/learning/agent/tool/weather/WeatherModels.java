package dev.learning.agent.tool.weather;

import java.util.List;

/** Provider-independent values. Times are ISO strings with an explicit accompanying zone. */
public final class WeatherModels {
    private WeatherModels() {
    }

    public record LocationQuery(String city, String countryCode, String admin1) {
    }

    public record GeoLocation(long id, String name, String countryCode, String country, String admin1, double latitude,
        double longitude, String timezone) {
    }

    public sealed interface Report permits CurrentWeather, DailyForecast {}

    public record CurrentData(String dataTime, double gridLatitude, double gridLongitude, double temperature,
        Double apparentTemperature, Double relativeHumidity, int weatherCode, Double windSpeed,
        List<String> missingFields) {
        public CurrentData {
            missingFields = List.copyOf(missingFields);
        }
    }

    public record ForecastDay(String date, int weatherCode, String description, double temperatureMin,
        double temperatureMax, Double precipitationProbabilityMax, Double windSpeedMax) {
    }

    public record ForecastData(double gridLatitude, double gridLongitude, List<ForecastDay> days,
        List<String> missingFields) {
        public ForecastData {
            days = List.copyOf(days);
            missingFields = List.copyOf(missingFields);
        }
    }

    public record CurrentWeather(LocationQuery query, String searchedCity, GeoLocation location, String timezone,
        String localDate, String fetchedAt, String dataKind, String temperatureUnit, String windSpeedUnit,
        String description, CurrentData current, int httpRequests) implements Report {
    }

    public record DailyForecast(LocationQuery query, String searchedCity, GeoLocation location, String timezone,
        String localDate, String fetchedAt, String dataKind, String temperatureUnit, String windSpeedUnit,
        ForecastData forecast, int httpRequests) implements Report {
    }
}
