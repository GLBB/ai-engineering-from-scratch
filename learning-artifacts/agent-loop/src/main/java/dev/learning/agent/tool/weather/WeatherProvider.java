package dev.learning.agent.tool.weather;

import dev.learning.agent.tool.weather.WeatherModels.CurrentData;
import dev.learning.agent.tool.weather.WeatherModels.ForecastData;
import dev.learning.agent.tool.weather.WeatherModels.GeoLocation;
import dev.learning.agent.tool.weather.WeatherModels.LocationQuery;
import java.io.IOException;
import java.util.List;
import dev.learning.agent.tool.ToolSource;

/** Weather facts, independent of agent history, plans and evidence IDs. */
public interface WeatherProvider {
    List<ToolSource> sources();

    List<GeoLocation> search(LocationQuery query, String city, WeatherCall call)
        throws IOException, InterruptedException;

    CurrentData current(GeoLocation location, WeatherCall call) throws IOException, InterruptedException;

    ForecastData forecast(GeoLocation location, int days, WeatherCall call) throws IOException, InterruptedException;
}
