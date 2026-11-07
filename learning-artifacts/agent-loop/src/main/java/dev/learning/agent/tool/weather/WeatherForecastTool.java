package dev.learning.agent.tool.weather;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolSpec;
import dev.learning.agent.tool.ToolInput;
import dev.learning.agent.tool.ToolResult;
import java.io.IOException;

public final class WeatherForecastTool extends WeatherTool {
    public WeatherForecastTool(WeatherService service) {
        super(service);
    }

    public ToolSpec definition() {
        return WeatherArguments.definition("weather_forecast",
            "Get a daily forecast for 1–7 consecutive days INCLUDING local tod"
                + "ay; default 7. Includes min/max temperature, precipitation probab"
                + "ility and wind. Use for today's whole-day weather, tomorrow or a "
                + "seven-day outlook, not history/hourly data. Use returned localDat"
                + "e and timezone for relative dates. Ask the user when location is " + "ambiguous.",
            true);
    }

    public ToolInput decode(String arguments) throws IOException {
        var node = WeatherArguments.object(arguments, true);
        return new ForecastWeatherInput(WeatherArguments.city(node), WeatherArguments.country(node),
            WeatherArguments.optional(node, "admin1"), WeatherArguments.days(node));
    }

    public ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws InterruptedException {
        if (!(input instanceof ForecastWeatherInput args)) {
            throw new IllegalArgumentException("expected forecast input");
        }
        return query(call -> service.forecast(args.location(), args.days(), call), context);
    }
}
