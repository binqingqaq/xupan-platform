package com.xupan.server.display;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MobileDisplayLotteryMapper {

    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<CardDefinition> DEFINITIONS = List.of(
            new CardDefinition("fast-sport", 10086, "极速运动会", "MM_SS"),
            new CardDefinition("happy-sport", 10087, "快乐运动会", "MM_SS"),
            new CardDefinition("hong-kong", 10048, "香港彩", "DAY_HH_MM"),
            new CardDefinition("lucky-ssc", 10059, "幸运时时彩", "MM_SS"),
            new CardDefinition("airship", 10057, "幸运飞艇", "HH_MM_SS"),
            new CardDefinition("pc28", 10074, "PC28", "MM_SS"),
            new CardDefinition("taiwan-ssc", 10064, "台湾5分彩", "MM_SS"),
            new CardDefinition("fast-airship", 10035, "极速飞艇", "HH_MM_SS"),
            new CardDefinition("bingo-six", 10103, "宾果六合彩", "MM_SS"),
            new CardDefinition("happy-eight-six", 10115, "快乐8六合彩", "DAY_HH_MM_SS"),
            new CardDefinition("fast-race", 10037, "极速赛车", "HH_MM_SS"),
            new CardDefinition("fast-ssc", 10036, "极速时时彩", "MM_SS"),
            new CardDefinition("sg-airship", 10058, "SG飞艇", "HH_MM_SS"),
            new CardDefinition("sg-ssc", 10075, "SG时时彩", "MM_SS"),
            new CardDefinition("sg-k3", 10076, "SG快3", "MM_SS")
    );

    private final ObjectMapper objectMapper;

    public MobileDisplayLotteryMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedHome parse(String body) {
        JsonNode root = objectMapper.readTree(body);
        if (root.path("errorCode").asInt(-1) != 0
                || root.path("result").path("businessCode").asInt(-1) != 0) {
            throw new IllegalStateException("MOBILE_DISPLAY_EXTERNAL_BUSINESS_ERROR");
        }
        JsonNode data = root.path("result").path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("MOBILE_DISPLAY_EXTERNAL_DATA_INVALID");
        }
        Map<Integer, JsonNode> byLotCode = new LinkedHashMap<>();
        Instant sourceTime = null;
        for (JsonNode item : data) {
            int lotCode = item.path("lotCode").asInt();
            byLotCode.put(lotCode, item);
            if (sourceTime == null) {
                sourceTime = parseInstant(text(item, "serverTime"));
            }
        }
        if (sourceTime == null) {
            sourceTime = Instant.now();
        }

        List<MobileDisplayHomeResponse.LotteryCard> cards = new ArrayList<>(DEFINITIONS.size());
        for (CardDefinition definition : DEFINITIONS) {
            JsonNode item = byLotCode.get(definition.lotCode());
            if (item == null) {
                throw new IllegalStateException(
                        "MOBILE_DISPLAY_EXTERNAL_INCOMPLETE_" + definition.lotCode());
            }
            cards.add(mapCard(definition, item));
        }
        return new ParsedHome(sourceTime, List.copyOf(cards));
    }

    public MobileDisplayHomeResponse.LotteryCard parseStoredCard(String cardJson) {
        return objectMapper.readValue(cardJson, MobileDisplayHomeResponse.LotteryCard.class);
    }

    public List<MobileDisplayHomeResponse.LotteryCard> sortCards(
            List<MobileDisplayHomeResponse.LotteryCard> cards
    ) {
        Map<Integer, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < DEFINITIONS.size(); index++) {
            order.put(DEFINITIONS.get(index).lotCode(), index);
        }
        return cards.stream()
                .sorted((left, right) -> Integer.compare(
                        order.getOrDefault(left.lotCode(), Integer.MAX_VALUE),
                        order.getOrDefault(right.lotCode(), Integer.MAX_VALUE)))
                .toList();
    }

    private static MobileDisplayHomeResponse.LotteryCard mapCard(
            CardDefinition definition,
            JsonNode item
    ) {
        List<String> numbers = codeNumbers(item);
        List<String> numberColors = colorNames(item.path("color"));
        List<String> summary;
        if (definition.lotCode() == 10048
                || definition.lotCode() == 10103
                || definition.lotCode() == 10115) {
            summary = sixLotterySummary(item);
        } else if (definition.lotCode() == 10086 || definition.lotCode() == 10087) {
            summary = dragonTigerSummary(item, List.of(
                    "firstDragonTiger", "secondDragonTiger", "thirdDragonTiger"));
        } else if (definition.lotCode() == 10057
                || definition.lotCode() == 10035
                || definition.lotCode() == 10037
                || definition.lotCode() == 10058) {
            summary = pk10Summary(item);
        } else if (definition.lotCode() == 10074) {
            Integer sum = integer(item, "sumNum");
            numbers = append(numbers, sum == null ? "" : String.valueOf(sum));
            numberColors = appendColor(numberColors, "red");
            summary = numberSummary(item, "总和：");
        } else if (definition.lotCode() == 10076) {
            summary = numberSummary(item, "总和：");
        } else {
            summary = sscSummary(item);
        }
        return new MobileDisplayHomeResponse.LotteryCard(
                definition.key(),
                definition.lotCode(),
                definition.name(),
                text(item, "preDrawIssue"),
                parseInstant(text(item, "drawTime")),
                definition.countdownFormat(),
                List.copyOf(numbers),
                List.copyOf(numberColors),
                List.copyOf(summary)
        );
    }

    private static List<String> codeNumbers(JsonNode item) {
        String code = text(item, "preDrawCode");
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return Arrays.stream(code.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static List<String> sixLotterySummary(JsonNode item) {
        List<Integer> zodiac = integerArray(item.path("chineseZodiac"));
        List<Integer> elements = integerArray(item.path("fiveElements"));
        List<String> result = new ArrayList<>(zodiac.size() + 1);
        for (int index = 0; index < zodiac.size(); index++) {
            String zodiacName = zodiacName(zodiac.get(index));
            String elementName = index < elements.size() ? elementName(elements.get(index)) : "";
            result.add((zodiacName + " " + elementName).trim());
        }
        Integer total = integer(item, "sumTotal");
        if (total != null) {
            result.add("总分：" + total);
        }
        return result;
    }

    private static List<String> dragonTigerSummary(JsonNode item, List<String> fields) {
        List<String> result = new ArrayList<>(fields.size());
        for (String field : fields) {
            Integer value = integer(item, field);
            if (value != null) {
                result.add(dragonTiger(value));
            }
        }
        return result;
    }

    private static List<String> pk10Summary(JsonNode item) {
        List<String> result = new ArrayList<>(8);
        result.addAll(dragonTigerSummary(item, List.of(
                "firstDT", "secondDT", "thirdDT", "fourthDT", "fifthDT")));
        Integer sum = integer(item, "sumFS");
        if (sum != null) {
            result.add("|");
            result.add("冠亚和：" + sum);
            addSizeAndParity(item, result);
        }
        return result;
    }

    private static List<String> sscSummary(JsonNode item) {
        List<String> result = new ArrayList<>(5);
        Integer dragonTiger = integer(item, "dragonTiger");
        if (dragonTiger != null) {
            result.add(dragonTiger(dragonTiger));
        }
        result.add("|");
        result.addAll(numberSummary(item, "总和："));
        return result;
    }

    private static List<String> numberSummary(JsonNode item, String label) {
        List<String> result = new ArrayList<>(3);
        Integer sum = integer(item, "sumNum");
        if (sum != null) {
            result.add(label + sum);
        }
        addSizeAndParity(item, result);
        return result;
    }

    private static void addSizeAndParity(JsonNode item, List<String> result) {
        Integer size = integer(item, "sumBigSmall", "sumBigSamll");
        if (size != null) {
            result.add(size == 0 ? "大" : "小");
        }
        Integer parity = integer(item, "sumSingleDouble");
        if (parity != null) {
            result.add(parity == 1 ? "双" : "单");
        }
    }

    private static String dragonTiger(int value) {
        return value == 1 ? "龙" : "虎";
    }

    private static String zodiacName(int value) {
        return switch (value) {
            case 1 -> "鼠";
            case 2 -> "牛";
            case 3 -> "虎";
            case 4 -> "兔";
            case 5 -> "龙";
            case 6 -> "蛇";
            case 7 -> "马";
            case 8 -> "羊";
            case 9 -> "猴";
            case 10 -> "鸡";
            case 11 -> "狗";
            case 12 -> "猪";
            default -> "";
        };
    }

    private static String elementName(int value) {
        return switch (value) {
            case 1 -> "金";
            case 2 -> "木";
            case 3 -> "水";
            case 4 -> "火";
            case 5 -> "土";
            default -> "";
        };
    }

    private static List<String> colorNames(JsonNode colors) {
        List<Integer> values = integerArray(colors);
        List<String> result = new ArrayList<>(values.size());
        for (Integer value : values) {
            result.add(switch (value) {
                case 1 -> "red";
                case 2 -> "green";
                case 3 -> "blue";
                default -> "blue";
            });
        }
        return result;
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static List<String> appendColor(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Integer integer(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.get(field);
            if (value != null && !value.isNull()) {
                return value.asInt();
            }
        }
        return null;
    }

    private static List<Integer> integerArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            result.add(value.asInt());
        }
        return result;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATE_TIME).atZone(SOURCE_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(SOURCE_ZONE).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    public record ParsedHome(Instant serverTime, List<MobileDisplayHomeResponse.LotteryCard> cards) {
    }

    private record CardDefinition(String key, int lotCode, String name, String countdownFormat) {
    }
}
