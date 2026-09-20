package com.securelogx.detection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative deterministic scanner for strongly structured sensitive values.
 *
 * It only returns MASK when validation is strong enough to be useful without
 * the ML model. Ambiguous candidates return ESCALATE so BERT remains in the
 * decision path.
 */
public final class DeterministicSensitiveDataDetector {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern SSN = Pattern.compile(
            "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)"
    );
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}(?![A-Za-z0-9_-])"
    );
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+([A-Za-z0-9._~+/-]{8,})"
    );
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|apikey|access[_-]?key)\\s*[:=]\\s*[\"']?([A-Za-z0-9_./+=-]{8,})"
    );
    private static final Pattern IBAN = Pattern.compile(
            "(?i)\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"
    );
    private static final Pattern CARD_CANDIDATE = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)"
    );
    private static final Pattern ROUTING_CONTEXT = Pattern.compile(
            "(?i)\\b(?:routing(?:[_ -]?number)?|routingNumber|aba)\\s*[:=]\\s*[\"']?(\\d{9})"
    );
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b"
    );

    private static final Set<String> IP_NEGATIVE_CONTEXT = Set.of(
            "version",
            "release",
            "build",
            "artifact",
            "revision",
            "protocol",
            "package",
            "coordinate"
    );

    private static final Set<String> IP_POSITIVE_CONTEXT = Set.of(
            "ip",
            "clientip",
            "remoteip",
            "sourceip",
            "destinationip",
            "srcip",
            "dstip",
            "gateway",
            "peer",
            "network address",
            "remote address",
            "client address"
    );

    public DeterministicScanResult scan(String text) {
        if (text == null || text.isEmpty()) {
            return new DeterministicScanResult(List.of(), false, "empty-log");
        }

        List<DetectionEvidence> evidence = new ArrayList<>();

        addSimpleMatches(text, EMAIL, "EMAIL", "validated-email-shape", evidence);
        addSimpleMatches(text, SSN, "SSN", "validated-ssn-shape", evidence);
        addSimpleMatches(text, JWT, "AUTH_TOKEN", "validated-jwt-shape", evidence);

        Matcher bearer = BEARER.matcher(text);
        while (bearer.find()) {
            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            bearer.start(1),
                            bearer.end(1),
                            "AUTH_TOKEN",
                            DetectionSource.DETERMINISTIC,
                            ResolutionAction.MASK,
                            1.0,
                            "explicit-bearer-token"
                    )
            );
        }

        Matcher api = API_KEY.matcher(text);
        while (api.find()) {
            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            api.start(1),
                            api.end(1),
                            "API_KEY",
                            DetectionSource.DETERMINISTIC,
                            ResolutionAction.MASK,
                            1.0,
                            "explicit-api-key-field"
                    )
            );
        }

        Matcher iban = IBAN.matcher(text);
        while (iban.find()) {
            String raw = iban.group();
            if (isValidIban(raw)) {
                addEvidence(
                        evidence,
                        new DetectionEvidence(
                                iban.start(),
                                iban.end(),
                                "IBAN",
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.MASK,
                                1.0,
                                "iban-mod97-valid"
                        )
                );
            } else {
                addEvidence(
                        evidence,
                        new DetectionEvidence(
                                iban.start(),
                                iban.end(),
                                "IBAN",
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.ESCALATE,
                                0.5,
                                "iban-shaped-but-checksum-invalid"
                        )
                );
            }
        }

        Matcher card = CARD_CANDIDATE.matcher(text);
        while (card.find()) {
            String raw = card.group();
            String digits = digitsOnly(raw);
            if (digits.length() >= 13 && digits.length() <= 19 && passesLuhn(digits)) {
                addEvidence(
                        evidence,
                        new DetectionEvidence(
                                card.start(),
                                card.end(),
                                "CREDIT_CARD_NUMBER",
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.MASK,
                                1.0,
                                "luhn-valid-card"
                        )
                );
            }
        }

        Matcher routing = ROUTING_CONTEXT.matcher(text);
        while (routing.find()) {
            String digits = routing.group(1);
            ResolutionAction action = isValidUsRoutingNumber(digits)
                    ? ResolutionAction.MASK
                    : ResolutionAction.ESCALATE;
            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            routing.start(1),
                            routing.end(1),
                            "ROUTING_NUMBER",
                            DetectionSource.DETERMINISTIC,
                            action,
                            action == ResolutionAction.MASK ? 1.0 : 0.5,
                            action == ResolutionAction.MASK
                                    ? "routing-context-and-checksum-valid"
                                    : "routing-context-but-checksum-invalid"
                    )
            );
        }

        Matcher ip = IPV4.matcher(text);
        while (ip.find()) {
            String context = contextWindow(text, ip.start(), ip.end(), 40)
                    .toLowerCase(Locale.ROOT);

            boolean negative = containsAny(context, IP_NEGATIVE_CONTEXT);
            boolean positive = containsAny(context, IP_POSITIVE_CONTEXT);

            ResolutionAction action;
            String reason;
            double confidence;

            if (negative && !positive) {
                action = ResolutionAction.ALLOW;
                reason = "technical-reference-ip-shape";
                confidence = 1.0;
            } else if (positive && !negative) {
                action = ResolutionAction.MASK;
                reason = "network-context-ip-address";
                confidence = 1.0;
            } else {
                action = ResolutionAction.ESCALATE;
                reason = "ambiguous-ip-context";
                confidence = 0.5;
            }

            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            ip.start(),
                            ip.end(),
                            "IP_ADDRESS",
                            DetectionSource.DETERMINISTIC,
                            action,
                            confidence,
                            reason
                    )
            );
        }

        evidence.sort(
                Comparator.comparingInt(DetectionEvidence::start)
                        .thenComparingInt(DetectionEvidence::end)
                        .thenComparing(DetectionEvidence::entityType)
        );

        boolean requiresMl = shouldInvokeMl(text, evidence);
        String gateReason = requiresMl
                ? "unresolved-or-semantic-risk"
                : "fully-resolved-by-deterministic-evidence";

        return new DeterministicScanResult(evidence, requiresMl, gateReason);
    }

    private static void addSimpleMatches(
            String text,
            Pattern pattern,
            String entityType,
            String reason,
            List<DetectionEvidence> output
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addEvidence(
                    output,
                    new DetectionEvidence(
                            matcher.start(),
                            matcher.end(),
                            entityType,
                            DetectionSource.DETERMINISTIC,
                            ResolutionAction.MASK,
                            1.0,
                            reason
                    )
            );
        }
    }

    private static void addEvidence(
            List<DetectionEvidence> output,
            DetectionEvidence candidate
    ) {
        for (DetectionEvidence current : output) {
            if (current.start() == candidate.start()
                    && current.end() == candidate.end()
                    && current.entityType().equals(candidate.entityType())
                    && current.action() == candidate.action()) {
                return;
            }
        }
        output.add(candidate);
    }

    private static boolean shouldInvokeMl(
            String text,
            List<DetectionEvidence> evidence
    ) {
        if (evidence.stream().anyMatch(
                item -> item.action() == ResolutionAction.ESCALATE
        )) {
            return true;
        }

        if (evidence.isEmpty()) {
            return true;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        if (containsAny(
                lower,
                Set.of(
                        "name",
                        "dob",
                        "birth",
                        "age",
                        "phone",
                        "mobile",
                        "street",
                        "address",
                        "city",
                        "state",
                        "postal",
                        "zip",
                        "country",
                        "passport",
                        "driver",
                        "license",
                        "customer",
                        "userid",
                        "user_id",
                        "order",
                        "request",
                        "case",
                        "ticket",
                        "invoice",
                        "workflow",
                        "businessid",
                        "business_id",
                        "accountid",
                        "account_id",
                        "bankaccount",
                        "bank_account",
                        "taxid",
                        "tax_id",
                        "itin",
                        "swift",
                        "device"
                )
        )) {
            return true;
        }

        if (lower.contains("ssn") && !hasEntity(evidence, "SSN")) {
            return true;
        }
        if (lower.contains("email") && !hasEntity(evidence, "EMAIL")) {
            return true;
        }
        if (containsAny(lower, Set.of("credit card", "cardnumber", "card_number"))
                && !hasEntity(evidence, "CREDIT_CARD_NUMBER")) {
            return true;
        }
        if (lower.contains("iban") && !hasEntity(evidence, "IBAN")) {
            return true;
        }
        if (lower.contains("routing") && !hasEntity(evidence, "ROUTING_NUMBER")) {
            return true;
        }
        if (containsAny(lower, Set.of("token", "apikey", "api_key", "api-key"))
                && !hasEntity(evidence, "AUTH_TOKEN")
                && !hasEntity(evidence, "API_KEY")) {
            return true;
        }

        return false;
    }

    private static boolean hasEntity(
            List<DetectionEvidence> evidence,
            String entityType
    ) {
        return evidence.stream().anyMatch(
                item -> item.entityType().equals(entityType)
                        && item.action() != ResolutionAction.ESCALATE
        );
    }

    private static boolean containsAny(String text, Set<String> values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String contextWindow(
            String text,
            int start,
            int end,
            int radius
    ) {
        int left = Math.max(0, start - radius);
        int right = Math.min(text.length(), end + radius);
        return text.substring(left, right);
    }

    private static String digitsOnly(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;

        for (int i = digits.length() - 1; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }

        return sum % 10 == 0;
    }

    private static boolean isValidUsRoutingNumber(String digits) {
        if (digits.length() != 9) {
            return false;
        }

        int sum = 0;
        for (int i = 0; i < 9; i += 3) {
            sum += 3 * (digits.charAt(i) - '0');
            sum += 7 * (digits.charAt(i + 1) - '0');
            sum += digits.charAt(i + 2) - '0';
        }
        return sum % 10 == 0;
    }

    private static boolean isValidIban(String value) {
        String normalized = value.replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < 15 || normalized.length() > 34) {
            return false;
        }

        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        StringBuilder numeric = new StringBuilder();

        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }

        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
