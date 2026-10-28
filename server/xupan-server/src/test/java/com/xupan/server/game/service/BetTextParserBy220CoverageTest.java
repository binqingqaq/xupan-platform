package com.xupan.server.game.service;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BetTextParserBy220CoverageTest {

    private static final Path BASELINE = Path.of("..", "..", "docs", "零时文档",
            "2026-10-23-BY220番摊赔率限额返水与快捷下注业务实现基线.md");

    @TestFactory
    Stream<DynamicTest> classifiesEveryBy220NameFromTheBusinessBaseline() throws Exception {
        List<NameCase> cases = Files.readAllLines(BASELINE).stream()
                .filter(line -> line.startsWith("| "))
                .map(line -> line.split("\\|", -1))
                .filter(columns -> columns.length == 10 && columns[1].trim().matches("\\d+"))
                .map(columns -> new NameCase(
                        Integer.parseInt(columns[1].trim()),
                        columns[3].trim(),
                        columns[5].trim(),
                        expectedAccepted(columns[7].trim())))
                .toList();

        assertThat(cases).hasSize(224);
        return cases.stream().map(nameCase -> DynamicTest.dynamicTest(
                "%03d %s".formatted(nameCase.sequence(), nameCase.name()),
                () -> assertThat(BetTextParser.parse(nameCase.input()).status())
                        .as("BY220 example: %s", nameCase.input())
                        .isEqualTo(nameCase.accepted()
                                ? BetTextParser.Status.ACCEPTED
                                : BetTextParser.Status.INVALID)));
    }

    private static boolean expectedAccepted(String httpGate) {
        return httpGate.contains("支持该示例") || httpGate.contains("白名单含该名称");
    }

    private record NameCase(int sequence, String name, String input, boolean accepted) {
    }
}
