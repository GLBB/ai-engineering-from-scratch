package dev.learning.agent.tool.weather;

import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunScope;
import dev.learning.agent.model.ToolSpec;
import dev.learning.agent.tool.ToolInput;
import dev.learning.agent.tool.ToolResult;
import java.io.IOException;

public final class CurrentWeatherTool extends WeatherTool {
    public CurrentWeatherTool(WeatherService service) {
        super(service);
    }

    public ToolSpec definition() {
        return WeatherArguments.definition("current_weather",
            "Get current model-estimated weather: temperature, apparent temper"
                + "ature, humidity and wind. Not a daily forecast or station observa"
                + "tion. Results include local date, data time, units and sources. A"
                + "sk the user when location is ambiguous.",
            false);
    }

    public ToolInput decode(String arguments) throws IOException {
        var node = WeatherArguments.object(arguments, false);
        return new CurrentWeatherInput(WeatherArguments.city(node), WeatherArguments.country(node),
            WeatherArguments.optional(node, "admin1"));
    }

    public ToolResult execute(ToolInput input, RunScope scope, RunContext context) throws InterruptedException {
        if (!(input instanceof CurrentWeatherInput args)) {
            throw new IllegalArgumentException("expected current weather input");
        }
        return query(call -> service.current(args.location(), call), context);
    }
}
