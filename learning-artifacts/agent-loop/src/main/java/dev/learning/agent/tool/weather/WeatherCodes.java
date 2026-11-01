package dev.learning.agent.tool.weather;

/** WMO codes: https://open-meteo.com/en/docs#weathervariables */
final class WeatherCodes {
    private WeatherCodes() {}
    static String describe(int code) {
        return switch (code) {
            case 0 -> "晴"; case 1 -> "大部晴朗"; case 2 -> "多云"; case 3 -> "阴";
            case 45 -> "雾"; case 48 -> "雾凇";
            case 51 -> "小毛毛雨"; case 53 -> "中等毛毛雨"; case 55 -> "浓毛毛雨";
            case 56, 57 -> "冻毛毛雨"; case 61 -> "小雨"; case 63 -> "中雨"; case 65 -> "大雨";
            case 66, 67 -> "冻雨"; case 71 -> "小雪"; case 73 -> "中雪"; case 75 -> "大雪";
            case 77 -> "米雪"; case 80 -> "小阵雨"; case 81 -> "中等阵雨"; case 82 -> "强阵雨";
            case 85, 86 -> "阵雪"; case 95 -> "雷暴"; case 96 -> "雷暴伴小冰雹";
            case 97 -> "强雷暴"; case 99 -> "雷暴伴大冰雹";
            default -> "未知天气代码（" + code + "）";
        };
    }
}
