package com.xupan.server.chat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the small set of player commands handled by the chat service. */
public final class ChatCommandParser {

    private static final Pattern POINT_REQUEST =
            Pattern.compile("^(上|下)([0-9]{1,12}(?:\\.[0-9]{1,2})?)$");

    private ChatCommandParser() {
    }

    public static Optional<Command> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return switch (text.trim()) {
            case "查" -> Optional.of(new Command(Type.BALANCE, null));
            case "玩法", "说明" -> Optional.of(new Command(Type.RULES, null));
            case "取消" -> Optional.of(new Command(Type.CANCEL, null));
            case "流水" -> Optional.of(new Command(Type.DAILY_SUMMARY, null));
            default -> parsePointRequest(text.trim());
        };
    }

    private static Optional<Command> parsePointRequest(String text) {
        Matcher matcher = POINT_REQUEST.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            BigDecimal amount = new BigDecimal(matcher.group(2));
            if (amount.signum() <= 0 || amount.scale() > 2) {
                return Optional.empty();
            }
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            return Optional.of(new Command(
                    "上".equals(matcher.group(1)) ? Type.TOP_UP : Type.DOWN,
                    amount));
        } catch (NumberFormatException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    public enum Type {
        BALANCE,
        RULES,
        CANCEL,
        DAILY_SUMMARY,
        TOP_UP,
        DOWN
    }

    public record Command(Type type, BigDecimal amount) {
    }
}
