package dev.learning.agent.tool.weather;

import dev.learning.agent.core.runtime.RunControl;
import java.time.Duration;
import java.io.IOException;

/** One logical tool call: deadline and HTTP attempts remain local to this instance. */
public final class WeatherCall {
    private final RunControl control;
    private final long deadline;
    private int requests;

    public WeatherCall(RunControl control) {
        this(control, Duration.ofSeconds(30));
    }

    public WeatherCall(RunControl control, Duration duration) {
        this.control = control;
        deadline = System.nanoTime() + duration.toNanos();
    }

    public RunControl control() {
        return control;
    }

    public int requests() {
        return requests;
    }

    public void dispatched() {
        requests++;
    }

    public long requestTimeoutMillis() throws IOException, InterruptedException {
        control.check();
        long remaining = Math.min(control.remaining().toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) {
            throw new WeatherFailure("WEATHER_TIMEOUT", "天气查询超过总时限");
        }
        return Math.max(1, Math.min(12_000, Duration.ofNanos(remaining).toMillis()));
    }
}
