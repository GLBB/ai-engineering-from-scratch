package dev.learning.agent.tool.weather;

import java.io.IOException;

public final class WeatherFailure extends IOException {
    private final String code;
    public WeatherFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
