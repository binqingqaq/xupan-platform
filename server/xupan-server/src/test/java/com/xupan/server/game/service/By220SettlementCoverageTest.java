package com.xupan.server.game.service;

import com.xupan.server.game.domain.BallResult;
import com.xupan.server.game.domain.PlayType;
import com.xupan.server.game.domain.SettlementStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class By220SettlementCoverageTest {

    private static final Path BASELINE = Path.of("..", "..", "docs", "零时文档",
            "2026-10-23-BY220番摊赔率限额返水与快捷下注业务实现基线.md");
    private final SettlementService settlement = new SettlementService();

    @TestFactory
    Stream<DynamicTest> checksAllFiftyTwoSettlementRulesAgainstTheBusinessBaseline() throws Exception {
        List<String> lines = Files.readAllLines(BASELINE);
        int start = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).startsWith("## 4."))
                .findFirst().orElseThrow();
        int end = java.util.stream.IntStream.range(start + 1, lines.size())
                .filter(index -> lines.get(index).startsWith("## 5."))
                .findFirst().orElseThrow();
        List<Rule> rules = lines.subList(start, end).stream()
                .filter(line -> line.startsWith("| "))
                .map(line -> line.split("\\|", -1))
                .filter(columns -> columns.length == 8 && columns[1].trim().matches("\\d+"))
                .map(columns -> new Rule(Integer.parseInt(columns[1].trim()),
                        input(columns[2].trim().split("[、，,]")[0]),
                        columns[3].trim(), columns[4].trim(), columns[5].trim(),
                        baselineOdds(columns[6].trim())))
                .toList();

        assertThat(rules).hasSize(52);
        return rules.stream().map(rule -> DynamicTest.dynamicTest(
                "%02d %s".formatted(rule.number(), rule.betText()), () -> checkRule(rule)));
    }

    private void checkRule(Rule rule) {
        BetTextParser.ParseResult parsed = BetTextParser.parse(rule.betText());
        assertThat(parsed.status()).as("BY220 rule %s input %s", rule.number(), rule.betText())
                .isEqualTo(BetTextParser.Status.ACCEPTED);

        for (int fan = 1; fan <= 4; fan++) {
            for (int number : List.of(fan, fan + 10)) {
                BallResult result = BallResult.fromNumber(number);
                SettlementResult actual = settlement.settle(parsed.bet().playType(), parsed.bet().parameters(),
                        parsed.bet().stake(), rule.odds(), result);
                assertThat(actual.status()).as("rule %s, result %s", rule.number(), number)
                        .isEqualTo(expected(rule, result));
            }
        }
    }

    private static SettlementStatus expected(Rule rule, BallResult result) {
        if (conditionMatches(rule.win(), result)) return SettlementStatus.WIN;
        if (conditionMatches(rule.draw(), result)) return SettlementStatus.DRAW;
        if (conditionMatches(rule.lose(), result)) return SettlementStatus.LOSE;
        throw new AssertionError("BY220 baseline has no outcome for rule " + rule.number() + ", number " + result.number());
    }

    private static boolean conditionMatches(String condition, BallResult result) {
        if (condition.equals("—") || condition.isBlank()) return false;
        if (condition.contains("＞10")) return result.number() > 10;
        if (condition.contains("≤10")) return result.number() <= 10;
        return List.of(condition.split("、")).stream().anyMatch(value -> Integer.parseInt(value.trim()) == result.fan());
    }

    private static String input(String name) {
        if (name.contains("严") || name.contains("念")) {
            return name.substring(0, 2).matches("[1-4][1-4](严|念)") ? name + "100" : name + "/100";
        }
        if (name.contains("无") || name.contains("通") || name.contains("加")) return name + "/100";
        if (name.matches("[1-4][1-4][角]")) return name + "100";
        if (name.matches("[1-4][车推]")) return name + "100";
        return name + "100";
    }

    private static BigDecimal baselineOdds(String value) {
        String display = value.contains("页面") ? value.substring(value.indexOf("页面") + 2) : value.split("[；;]")[0];
        return new BigDecimal(display.replace("…", "").trim());
    }

    private record Rule(int number, String betText, String win, String draw, String lose, BigDecimal odds) {
    }
}
