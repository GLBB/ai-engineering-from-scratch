package dev.learning.agent.tool.weather;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learning.agent.model.ToolSpec;
import dev.learning.agent.support.json.StrictJson;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WeatherArguments {
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private WeatherArguments() {}
    static JsonNode object(String arguments, boolean forecast) throws IOException {
        JsonNode node = StrictJson.object(arguments);
        if (forecast) StrictJson.fields(node, "city", "country_code", "admin1", "days");
        else StrictJson.fields(node, "city", "country_code", "admin1");
        return node;
    }
    static String city(JsonNode node) throws IOException {
        String city = string(node, "city");
        if (city.indexOf(',') >= 0 || city.indexOf('，') >= 0) throw new IOException("city 只填写城市名；国家和省级地区使用独立参数");
        return city;
    }
    static String optional(JsonNode node, String field) throws IOException { return node.has(field) ? string(node, field) : null; }
    static String country(JsonNode node) throws IOException {
        String country = optional(node, "country_code");
        if (country == null) return null;
        country = country.toUpperCase(Locale.ROOT);
        if (!COUNTRIES.contains(country)) throw new IOException("country_code 必须是有效的两字母国家代码");
        return country;
    }
    private static String string(JsonNode node, String field) throws IOException {
        String value = StrictJson.text(node, field).strip();
        if (value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl)) throw new IOException("invalid " + field);
        return value;
    }
    static int days(JsonNode node) throws IOException {
        if (!node.has("days")) return 7;
        JsonNode days = node.get("days");
        if (!days.isIntegralNumber() || !days.canConvertToInt() || days.intValue() < 1 || days.intValue() > 7)
            throw new IOException("days 必须是 1–7 的 JSON 整数");
        return days.intValue();
    }
    static ToolSpec definition(String name, String description, boolean forecast) {
        var fields = new LinkedHashMap<String, ToolSpec.PropertySchema>();
        fields.put("city", new ToolSpec.PropertySchema("string", "City name only, such as 北京. Do not combine a country or province into this string.", null, null, 100, null));
        fields.put("country_code", new ToolSpec.PropertySchema("string", "Optional ISO country code, e.g. CN. Use only a country established by the user.", null, null, 2, "^[A-Za-z]{2}$"));
        fields.put("admin1", new ToolSpec.PropertySchema("string", "Optional province/state/municipality established by the user, e.g. 北京市. Do not guess to resolve duplicate cities.", null, null, 100, null));
        if (forecast) fields.put("days", new ToolSpec.PropertySchema("integer", "Number of consecutive days including local today; default 7. For tomorrow, request at least 2 and select the returned date.", 1, 7, null, null));
        return new ToolSpec("function", new ToolSpec.FunctionSpec(name, description,
                new ToolSpec.ParameterSchema("object", fields, List.of("city"), false)));
    }
}
