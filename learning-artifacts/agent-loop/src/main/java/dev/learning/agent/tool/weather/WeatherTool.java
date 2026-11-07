package dev.learning.agent.tool.weather;

import dev.learning.agent.core.runtime.DefaultRunControl;
import dev.learning.agent.core.runtime.RunContext;
import dev.learning.agent.core.runtime.RunControl;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.AgentTool;
import dev.learning.agent.tool.ToolResult;
import dev.learning.agent.tool.ToolSource;
import dev.learning.agent.tool.ToolStatus;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** Agent adapter: source attribution and execution flags stay outside the weather service. */
abstract class WeatherTool implements AgentTool {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WeatherTool.class);
    protected final WeatherService service;
    WeatherTool(WeatherService service) { this.service = service; }
    @FunctionalInterface interface Query {
        WeatherModels.Report fetch(WeatherCall call) throws IOException, InterruptedException, WeatherService.LocationRequired;
    }
    protected ToolResult query(Query query, RunContext context) throws InterruptedException {
        RunControl control = context == null ? new DefaultRunControl(Duration.ofSeconds(30)) : context.control();
        WeatherCall call = new WeatherCall(control);
        try {
            control.check();
            WeatherModels.Report report = query.fetch(call);
            control.check();
            return result(ToolStatus.SUCCESS, StrictJson.write(report), null, call, true, service.sources());
        } catch (WeatherService.LocationRequired need) {
            String candidates = need.candidates().stream().map(place -> "- " + place.name() + " / "
                    + (place.admin1() == null ? "未标注省级地区" : place.admin1()) + " / " + place.countryCode()
                    + " (id=" + place.id() + ")").collect(Collectors.joining("\n"));
            return result(ToolStatus.NEEDS_INPUT, "地点候选（最多展示五项，请补充国家或省级地区）：\n" + candidates,
                    "LOCATION_AMBIGUOUS", call, false, service.sources());
        } catch (WeatherFailure failure) {
            ToolStatus status = failure.code().equals("WEATHER_TIMEOUT") ? ToolStatus.TIMEOUT : ToolStatus.ERROR;
            return result(status, failure.getMessage(), failure.code(), call, false, service.sources());
        } catch (IOException failure) {
            if (control.cancelled()) return result(ToolStatus.CANCELLED, "天气查询已取消", "CANCELLED", call, false, service.sources());
            return result(ToolStatus.ERROR, "天气服务网络或响应错误：" + failure.getMessage(), "WEATHER_UNAVAILABLE", call, false, service.sources());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return result(ToolStatus.CANCELLED, "天气查询已取消", "CANCELLED", call, false, service.sources());
        } finally {
            if (context == null) control.close();
            else context.telemetry().toolHttpRequests(call.requests());
        }
    }
    private ToolResult result(ToolStatus status, String content, String code, WeatherCall call, boolean hasValue, List<ToolSource> sources) {
        LOG.info("Weather tool finished; status={}; httpRequests={}", status, call.requests());
        return new ToolResult(status, content, code, call.requests() > 0, hasValue, false, sources);
    }
}
