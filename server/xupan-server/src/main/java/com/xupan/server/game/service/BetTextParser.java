package com.xupan.server.game.service;

import com.xupan.server.game.domain.PlayType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the confirmed Huiyingbo bet text grammar.
 *
 * <p>The parser deliberately owns no user, issue, wallet, or settlement state.
 * It only turns one text item into a structured bet request candidate. The
 * default target is always ball 1; callers must not infer another ball from
 * the text.</p>
 */
public final class BetTextParser {

    private static final String AMOUNT = "([0-9]{1,12}(?:\\.[0-9]{1,2})?)";
    private static final Pattern FAN = Pattern.compile("^([0-9]+)番/?" + AMOUNT + "$");
    private static final Pattern ANGLE = Pattern.compile("^([0-9]+)/?角/?" + AMOUNT + "$");
    private static final Pattern STRICT = Pattern.compile("^([0-9])(?:严|念)([0-9])/" + AMOUNT + "$");
    private static final Pattern ADD = Pattern.compile("^([0-9])加([0-9]{2})/" + AMOUNT + "$");
    private static final Pattern POSITIVE = Pattern.compile("^([0-9])正/?" + AMOUNT + "$");
    private static final Pattern TONG = Pattern.compile("^([0-9])通([0-9]{2})/" + AMOUNT + "$");
    private static final Pattern NONE = Pattern.compile("^([0-9]{2})无([0-9])/" + AMOUNT + "$");
    private static final Pattern ODD_EVEN = Pattern.compile("^(单|双)/?" + AMOUNT + "$");
    private static final Pattern BIG_SMALL = Pattern.compile("^(大|小)/?" + AMOUNT + "$");
    private static final Pattern SPECIAL = Pattern.compile("^([0-9]{1,2}(?:/[0-9]{1,2})*)特/?" + AMOUNT + "$");
    private static final Pattern SHORT = Pattern.compile("^([0-9]{2,3})/" + AMOUNT + "$");

    private BetTextParser() {
    }

    /**
     * Parses one complete bet item or a comma-separated combo. Surrounding
     * whitespace is ignored, but whitespace inside an item is not accepted.
     */
    public static ParseResult parse(String text) {
        if (text != null && (text.contains(",") || text.contains("，"))) {
            String source = text.trim();
            String[] items = source.split("[,，]", -1);
            if (items.length < 2) {
                return invalid("组合下注格式不正确");
            }

            List<ParsedBet> bets = new ArrayList<>(items.length);
            for (String item : items) {
                if (item.isBlank()) {
                    return invalid("组合下注格式不正确");
                }
                ParseResult itemResult = parseSingle(item.trim());
                if (!itemResult.accepted()) {
                    return invalid("组合下注格式不正确");
                }
                bets.addAll(itemResult.bets());
            }
            return new ParseResult(Status.ACCEPTED, bets.get(0), "", bets);
        }
        return parseSingle(text);
    }

    private static ParseResult parseSingle(String text) {
        if (text == null || text.isBlank()) {
            return invalid("下注文本不能为空");
        }

        String source = text.trim();
        if (source.contains("车")) {
            return unsupported("车玩法暂未确认，当前不支持下注");
        }

        ParseResult result = parseWithAmount(source, FAN, PlayType.FAN, 1, false);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, ANGLE, PlayType.ANGLE, 2, true);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, STRICT, PlayType.STRICT, 2, true);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, ADD, PlayType.ADD, 3, true);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, POSITIVE, PlayType.POSITIVE, 1, false);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, TONG, PlayType.TONG, 3, true);
        if (result != null) {
            return result;
        }
        result = parseWithAmount(source, NONE, PlayType.NONE, 3, true);
        if (result != null) {
            return result;
        }

        Matcher oddEven = ODD_EVEN.matcher(source);
        if (oddEven.matches()) {
            return accepted(source, oddEven.group(1).equals("单") ? PlayType.ODD_EVEN : PlayType.ODD_EVEN,
                    List.of(oddEven.group(1).equals("单") ? 1 : 2), oddEven.group(2));
        }

        Matcher bigSmall = BIG_SMALL.matcher(source);
        if (bigSmall.matches()) {
            return accepted(source, PlayType.BIG_SMALL,
                    List.of(bigSmall.group(1).equals("大") ? 1 : 2), bigSmall.group(2));
        }

        Matcher special = SPECIAL.matcher(source);
        if (special.matches()) {
            List<Integer> numbers = parseSpecialNumbers(special.group(1));
            if (numbers == null) {
                return invalid("特玩法号码必须在 01 至 20 之间且不能重复");
            }
            return accepted(source, PlayType.SPECIAL, numbers, special.group(2));
        }

        Matcher shortForm = SHORT.matcher(source);
        if (shortForm.matches()) {
            String code = shortForm.group(1);
            PlayType playType = code.length() == 2 ? PlayType.ANGLE : PlayType.CAR;
            List<Integer> parameters = parseFanNumbers(code);
            if (parameters == null || hasDuplicates(parameters)) {
                return invalid("番值必须是 1 至 4 且不能重复");
            }
            return accepted(source, playType, parameters, shortForm.group(2));
        }

        if (source.contains("无")) {
            return unsupported("单数字无玩法未确认，当前仅支持“两赢一输一和”的 12无3 格式");
        }

        return invalid("无法识别的下注格式");
    }

    /** Returns whether an unparsed message looks like an attempted bet. */
    public static boolean looksLikeBet(String text) {
        return text != null && text.trim().matches(
                ".*(?:[0-9].*[\\/番角车严念加正通无单双大小特]|[\\/番角车严念加正通无单双大小特].*[0-9]).*");
    }

    private static ParseResult parseWithAmount(String source, Pattern pattern, PlayType playType,
                                                int expectedParameterCount, boolean requireDistinct) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.matches()) {
            return null;
        }

        StringBuilder parameterText = new StringBuilder();
        for (int group = 1; group < matcher.groupCount(); group++) {
            parameterText.append(matcher.group(group));
        }
        List<Integer> parameters = parseFanNumbers(parameterText.toString());
        if (parameters == null || parameters.size() != expectedParameterCount) {
            return invalid("番值必须是 1 至 4");
        }
        if (requireDistinct && hasDuplicates(parameters)) {
            return invalid("同一玩法中的番值不能重复");
        }
        return accepted(source, playType, parameters, matcher.group(matcher.groupCount()));
    }

    private static List<Integer> parseFanNumbers(String text) {
        List<Integer> values = text.chars()
                .map(character -> character - '0')
                .boxed()
                .toList();
        if (values.stream().anyMatch(value -> value < 1 || value > 4)) {
            return null;
        }
        return values;
    }

    private static List<Integer> parseSpecialNumbers(String text) {
        String[] tokens = text.split("/");
        List<Integer> numbers = new java.util.ArrayList<>(tokens.length);
        for (String token : tokens) {
            int number = Integer.parseInt(token);
            if (number < 1 || number > 20 || numbers.contains(number)) {
                return null;
            }
            numbers.add(number);
        }
        return List.copyOf(numbers);
    }

    private static boolean hasDuplicates(List<Integer> values) {
        return values.size() != values.stream().distinct().count();
    }

    private static ParseResult accepted(String source, PlayType playType, List<Integer> parameters,
                                        String amountText) {
        BigDecimal amount = parseAmount(amountText);
        if (amount == null) {
            return invalid("金额必须是大于 0 且最多两位小数的数字");
        }
        return new ParseResult(Status.ACCEPTED,
                new ParsedBet(1, playType, parameters, amount, source), "");
    }

    private static BigDecimal parseAmount(String text) {
        try {
            BigDecimal amount = new BigDecimal(text);
            int integerDigits = amount.precision() - amount.scale();
            if (amount.signum() <= 0 || amount.scale() > 2 || integerDigits > 12) {
                return null;
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static ParseResult invalid(String reason) {
        return new ParseResult(Status.INVALID, null, reason);
    }

    private static ParseResult unsupported(String reason) {
        return new ParseResult(Status.UNSUPPORTED, null, reason);
    }

    public enum Status {
        ACCEPTED,
        UNSUPPORTED,
        INVALID
    }

    public record ParseResult(Status status, ParsedBet bet, String reason, List<ParsedBet> bets) {
        public ParseResult(Status status, ParsedBet bet, String reason) {
            this(status, bet, reason, bet == null ? List.of() : List.of(bet));
        }

        public ParseResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            bets = List.copyOf(Objects.requireNonNull(bets, "bets"));
            if (status == Status.ACCEPTED && (bet == null || bets.isEmpty())) {
                throw new IllegalArgumentException("accepted result requires bets");
            }
            if (status != Status.ACCEPTED && (bet != null || !bets.isEmpty())) {
                throw new IllegalArgumentException("non-accepted result cannot contain bets");
            }
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }

    public record ParsedBet(int ballNumber, PlayType playType, List<Integer> parameters,
                            BigDecimal stake, String sourceText) {
        public ParsedBet {
            if (ballNumber != 1) {
                throw new IllegalArgumentException("confirmed text bets only target ball 1");
            }
            Objects.requireNonNull(playType, "playType");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(stake, "stake");
            Objects.requireNonNull(sourceText, "sourceText");
        }
    }
}
