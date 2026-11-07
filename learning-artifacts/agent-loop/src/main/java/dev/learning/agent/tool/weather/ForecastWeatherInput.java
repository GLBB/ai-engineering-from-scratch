package dev.learning.agent.tool.weather;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.learning.agent.tool.ToolInput;
import java.util.List;
import java.util.function.UnaryOperator;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForecastWeatherInput(String city, @JsonProperty("country_code") String countryCode, String admin1,
    int days) implements ToolInput {
    public String toolName() {
        return "weather_forecast";
    }

    public List<String> templateStrings() {
        return admin1 == null ? List.of(city) : List.of(city, admin1);
    }

    public ToolInput mapStrings(UnaryOperator<String> mapper) {
        return new ForecastWeatherInput(mapper.apply(city), countryCode, admin1 == null ? null : mapper.apply(admin1),
            days);
    }

    WeatherModels.LocationQuery location() {
        return new WeatherModels.LocationQuery(city, countryCode, admin1);
    }
}
