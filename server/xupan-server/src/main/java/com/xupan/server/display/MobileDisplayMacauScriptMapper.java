package com.xupan.server.display;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MobileDisplayMacauScriptMapper {

    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern SCRIPT_ASSIGNMENT = Pattern.compile(
            "G_temps_result_(\\d+)\\s*=\\s*JSON\\.parse\\(__ras\\(atob\\(['\"]([A-Za-z0-9+/=]+)['\"]\\)\\)\\)",
            Pattern.DOTALL);
    private static final Pattern CREATED_AT = Pattern.compile(
            "create_at\\s+(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Set<Integer> RED_NUMBERS = Set.of(
            1, 2, 7, 8, 12, 13, 18, 19, 23, 24, 29, 30, 34, 35, 40, 45, 46);
    private static final Set<Integer> GREEN_NUMBERS = Set.of(
            5, 6, 11, 16, 17, 21, 22, 27, 28, 32, 33, 38, 39, 43, 44, 49);
    private static final Set<Integer> BLUE_NUMBERS = Set.of(
            3, 4, 9, 10, 14, 15, 20, 25, 26, 31, 36, 37, 41, 42, 47, 48);

    private final ObjectMapper objectMapper;

    public MobileDisplayMacauScriptMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedMacau parse(HttpMobileDisplayMacauClient.ScriptPayloads payloads,
                             LocalTime newDrawTime,
                             LocalTime oldDrawTime) {
        ParsedScript newMacau = parseScript(payloads.newScript(), "1", "Macau",
                "macau-new", 1, "新澳彩", newDrawTime);
        ParsedScript oldMacau = parseScript(payloads.oldScript(), "3", "Macau60",
                "macau-old", 3, "老澳彩", oldDrawTime);
        Instant sourceTime = newMacau.sourceTime().isAfter(oldMacau.sourceTime())
                ? newMacau.sourceTime() : oldMacau.sourceTime();
        return new ParsedMacau(sourceTime, List.of(newMacau.card(), oldMacau.card()));
    }

    private ParsedScript parseScript(String script,
                                     String expectedScriptNumber,
                                     String expectedRuleType,
                                     String key,
                                     int lotCode,
                                     String name,
                                     LocalTime drawTime) {
        Matcher matcher = SCRIPT_ASSIGNMENT.matcher(script);
        String encoded = null;
        while (matcher.find()) {
            if (expectedScriptNumber.equals(matcher.group(1))) {
                encoded = matcher.group(2);
                break;
            }
        }
        if (encoded == null) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_SCRIPT_PAYLOAD_MISSING_" + expectedScriptNumber);
        }

        JsonNode root = objectMapper.readTree(Base64.getDecoder().decode(encoded));
        JsonNode records = root.path("data");
        JsonNode target = null;
        for (JsonNode record : records) {
            if (record.path("widgetId").asInt(-1) == 40617) {
                target = record;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_WIDGET_MISSING_" + expectedScriptNumber);
        }

        String cacheKey = target.path("cacheKey").asString("");
        if (!cacheKey.contains(":" + expectedRuleType + ":")) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_RULE_TYPE_MISMATCH_" + expectedScriptNumber);
        }
        String upcomingIssue = target.path("latestIssue").asString("");
        int upcomingIssueValue = parseIssue(upcomingIssue);
        JsonNode drawRows = target.path("data").path("data");
        JsonNode latestDraw = null;
        int latestDrawIssue = -1;
        for (JsonNode row : drawRows) {
            int issue = row.path("issue").asInt(-1);
            JsonNode balls = row.path("w");
            if (issue <= upcomingIssueValue && row.path("ok").asInt() == 1
                    && balls.isArray() && balls.size() == 7 && issue > latestDrawIssue) {
                latestDraw = row;
                latestDrawIssue = issue;
            }
        }
        if (latestDraw == null) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_LATEST_DRAW_MISSING_" + expectedScriptNumber);
        }

        List<String> numbers = new ArrayList<>(7);
        List<String> colors = new ArrayList<>(7);
        List<String> zodiac = new ArrayList<>(7);
        for (JsonNode ball : latestDraw.path("w")) {
            int number = Integer.parseInt(ball.path("c").asString(""));
            if (number < 1 || number > 49) {
                throw new IllegalStateException("MOBILE_DISPLAY_MACAU_NUMBER_INVALID_" + expectedScriptNumber);
            }
            String zodiacName = ball.path("z").asString("").trim();
            if (zodiacName.isBlank()) {
                throw new IllegalStateException("MOBILE_DISPLAY_MACAU_ZODIAC_INVALID_" + expectedScriptNumber);
            }
            numbers.add(String.format("%02d", number));
            colors.add(color(number));
            zodiac.add(zodiacName);
        }

        Instant sourceTime = sourceTime(script);
        LocalDate drawDate = sourceTime.atZone(SOURCE_ZONE).toLocalDate();
        Instant nextDrawAt = LocalDateTime.of(drawDate, drawTime).atZone(SOURCE_ZONE).toInstant();
        String drawIssue = Integer.toString(latestDrawIssue);
        String displayedUpcomingIssue = latestDrawIssue == upcomingIssueValue
                ? Integer.toString(upcomingIssueValue + 1) : upcomingIssue;
        MobileDisplayHomeResponse.LotteryCard card = new MobileDisplayHomeResponse.LotteryCard(
                key,
                lotCode,
                name,
                drawIssue,
                nextDrawAt,
                "HH_MM_SS",
                List.copyOf(numbers),
                List.copyOf(colors),
                List.copyOf(zodiac),
                displayedUpcomingIssue);
        return new ParsedScript(sourceTime, card);
    }

    private static int parseIssue(String issue) {
        try {
            return Integer.parseInt(issue);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_ISSUE_INVALID", exception);
        }
    }

    private static Instant sourceTime(String script) {
        Matcher matcher = CREATED_AT.matcher(script);
        if (!matcher.find()) {
            return Instant.now();
        }
        return LocalDateTime.parse(matcher.group(1), DATE_TIME).atZone(SOURCE_ZONE).toInstant();
    }

    private static String color(int number) {
        if (RED_NUMBERS.contains(number)) return "red";
        if (GREEN_NUMBERS.contains(number)) return "green";
        if (BLUE_NUMBERS.contains(number)) return "blue";
        throw new IllegalStateException("MOBILE_DISPLAY_MACAU_COLOR_MISSING");
    }

    public record ParsedMacau(Instant sourceTime, List<MobileDisplayHomeResponse.LotteryCard> cards) {
    }

    private record ParsedScript(Instant sourceTime, MobileDisplayHomeResponse.LotteryCard card) {
    }
}
