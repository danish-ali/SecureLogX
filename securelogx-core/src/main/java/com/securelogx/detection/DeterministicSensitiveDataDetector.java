package com.securelogx.detection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

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
    private static final Pattern KEY_VALUE_KEY = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.-]{1,40})\\s*[:=]"
    );
    private static final Pattern OWNING_KEY = Pattern.compile(
            "[\\\"']?([A-Za-z][A-Za-z0-9_.-]{1,40})[\\\"']?\\s*[:=]\\s*[\\\"']?$"
    );
    private static final Pattern CAPITALIZED_NAME = Pattern.compile(
            "\\b[A-Z][a-z]{2,}\\s+[A-Z][a-z]{2,}\\b"
    );

    private static final Set<String> DETERMINISTIC_KEYS = Set.of(
            "email",
            "ssn",
            "socialsecuritynumber",
            "card",
            "cardnumber",
            "creditcard",
            "creditcardnumber",
            "routing",
            "routingnumber",
            "aba",
            "iban",
            "remoteip",
            "clientip",
            "sourceip",
            "destinationip",
            "srcip",
            "dstip",
            "gateway",
            "peer",
            "releaseversion",
            "buildversion",
            "artifactversion",
            "version",
            "apikey",
            "accesskey",
            "token",
            "authorization",
            "auth"
    );

    private static final Set<String> JSON_ENVELOPE_SAFE_KEYS = Set.of(
            "timestamp",
            "time",
            "level",
            "loglevel",
            "severity",
            "servicename",
            "service",
            "logger",
            "loggername",
            "thread",
            "threadname",
            "traceid",
            "spanid",
            "hostname",
            "host",
            "environment",
            "env",
            "deploymentenvironment",
            "eventdataset",
            "eventmodule",
            "eventkind",
            "eventcategory",
            "ecsversion",
            "processid",
            "processname",
            "podname",
            "containername",
            "namespace",
            "application",
            "app",
            "component"
    );

    private static final Set<String> JSON_PAYLOAD_KEYS = Set.of(
            "message",
            "msg",
            "logmessage"
    );

    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "status",
            "state",
            "result",
            "outcome",
            "action",
            "event",
            "level",
            "trace",
            "traceid",
            "seq",
            "sequence",
            "deployment",
            "environment",
            "service",
            "mode",
            "phase",
            "channel",
            "operation",
            "purpose",
            "type",
            "source",
            "target",
            "reason"
    );

    private static final Set<String> CARD_KEYS = Set.of(
            "card",
            "cardnumber",
            "creditcard",
            "creditcardnumber",
            "paymentcard",
            "pan",
            "primaryaccountnumber"
    );

    private static final Set<String> IP_TECHNICAL_KEYS = Set.of(
            "version",
            "releaseversion",
            "buildversion",
            "artifactversion",
            "protocolversion",
            "packageversion",
            "serviceversion",
            "appversion",
            "applicationversion"
    );

    private static final Set<String> IP_NETWORK_KEYS = Set.of(
            "ip",
            "clientip",
            "remoteip",
            "sourceip",
            "destinationip",
            "srcip",
            "dstip",
            "gateway",
            "peerip",
            "networkaddress",
            "remoteaddress",
            "clientaddress"
    );

    public DeterministicScanResult scan(String text) {
        if (text == null || text.isEmpty()) {
            return new DeterministicScanResult(List.of(), false, "empty-log");
        }

        List<DetectionEvidence> evidence = new ArrayList<>();

        addSimpleMatches(text, EMAIL, "EMAIL", "validated-email-shape", evidence);
        Matcher ssn = SSN.matcher(text);
        while (ssn.find()) {
            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            ssn.start(),
                            ssn.end(),
                            "SSN",
                            DetectionSource.DETERMINISTIC,
                            ResolutionAction.ESCALATE,
                            0.75,
                            "ssn-shaped-value-requires-context"
                    )
            );
        }
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

            if (digits.length() < 13
                    || digits.length() > 19
                    || !passesLuhn(digits)) {
                continue;
            }

            String owningKey = owningKey(text, card.start());
            if (CARD_KEYS.contains(owningKey)) {
                addEvidence(
                        evidence,
                        new DetectionEvidence(
                                card.start(),
                                card.end(),
                                "CREDIT_CARD_NUMBER",
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.MASK,
                                1.0,
                                "card-field-and-luhn-valid"
                        )
                );
            } else {
                addEvidence(
                        evidence,
                        new DetectionEvidence(
                                card.start(),
                                card.end(),
                                "CREDIT_CARD_NUMBER",
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.ESCALATE,
                                0.5,
                                "luhn-valid-but-no-card-field"
                        )
                );
            }
        }

        Matcher routing = ROUTING_CONTEXT.matcher(text);
        while (routing.find()) {
            String digits = routing.group(1);
            boolean checksumValid = isValidUsRoutingNumber(digits);
            addEvidence(
                    evidence,
                    new DetectionEvidence(
                            routing.start(1),
                            routing.end(1),
                            "ROUTING_NUMBER",
                            DetectionSource.DETERMINISTIC,
                            ResolutionAction.ESCALATE,
                            checksumValid ? 0.75 : 0.4,
                            checksumValid
                                    ? "valid-routing-number-requires-account-context"
                                    : "routing-shaped-value-checksum-invalid"
                    )
            );
        }

        Matcher ip = IPV4.matcher(text);
        while (ip.find()) {
            String owningKey = owningKey(text, ip.start());

            ResolutionAction action;
            String reason;
            double confidence;

            if (IP_TECHNICAL_KEYS.contains(owningKey)) {
                action = ResolutionAction.ALLOW;
                reason = "explicit-version-field-ip-shape";
                confidence = 1.0;
            } else if (IP_NETWORK_KEYS.contains(owningKey)) {
                action = ResolutionAction.MASK;
                reason = "explicit-network-field-ip-address";
                confidence = 1.0;
            } else {
                action = ResolutionAction.ESCALATE;
                reason = "ip-without-authoritative-field-context";
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

        MlGateDecision gate = decideMlRouting(text, evidence);
        return new DeterministicScanResult(
                evidence,
                gate.requiresMl(),
                gate.reason()
        );
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

    private MlGateDecision decideMlRouting(
            String text,
            List<DetectionEvidence> evidence
    ) {
        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.ESCALATE) {
                return MlGateDecision.ml(
                        "evidence-escalate:"
                                + item.entityType()
                                + ":"
                                + item.reason()
                );
            }
        }

        StructuredGateDecision structured = evaluateStructuredJson(text);
        if (structured.recognized()) {
            return structured.requiresMl()
                    ? MlGateDecision.ml(structured.reason())
                    : MlGateDecision.bypass(structured.reason());
        }

        if (evidence.isEmpty()) {
            return MlGateDecision.ml("no-deterministic-evidence");
        }

        if (containsCapitalizedNameOutsideEvidence(text, evidence)) {
            return MlGateDecision.ml("capitalized-name-outside-evidence");
        }

        Matcher keyMatcher = KEY_VALUE_KEY.matcher(text);
        boolean sawKeyValue = false;
        while (keyMatcher.find()) {
            sawKeyValue = true;
            String rawKey = keyMatcher.group(1);
            String normalizedKey = normalizeKey(rawKey);
            if (!DETERMINISTIC_KEYS.contains(normalizedKey)
                    && !SAFE_METADATA_KEYS.contains(normalizedKey)) {
                return MlGateDecision.ml(
                        "unknown-key:" + normalizedKey
                );
            }
        }

        // Deterministic bypass is intentionally limited to structured log
        // records. Free prose continues through contextual ML.
        if (!sawKeyValue) {
            return MlGateDecision.ml("free-text-no-keyvalue");
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
            return MlGateDecision.ml("semantic-risk-keyword");
        }

        if (lower.contains("ssn") && !hasEntity(evidence, "SSN")) {
            return MlGateDecision.ml("unresolved-keyword:ssn");
        }
        if (lower.contains("email") && !hasEntity(evidence, "EMAIL")) {
            return MlGateDecision.ml("unresolved-keyword:email");
        }
        if (containsAny(lower, Set.of("credit card", "cardnumber", "card_number"))
                && !hasEntity(evidence, "CREDIT_CARD_NUMBER")) {
            return MlGateDecision.ml("unresolved-keyword:credit-card");
        }
        if (lower.contains("iban") && !hasEntity(evidence, "IBAN")) {
            return MlGateDecision.ml("unresolved-keyword:iban");
        }
        if (lower.contains("routing") && !hasEntity(evidence, "ROUTING_NUMBER")) {
            return MlGateDecision.ml("unresolved-keyword:routing");
        }
        if (containsAny(lower, Set.of("token", "apikey", "api_key", "api-key"))
                && !hasEntity(evidence, "AUTH_TOKEN")
                && !hasEntity(evidence, "API_KEY")) {
            return MlGateDecision.ml("unresolved-keyword:token");
        }

        return MlGateDecision.bypass(
                "fully-resolved-by-deterministic-evidence"
        );
    }

    private record MlGateDecision(
            boolean requiresMl,
            String reason
    ) {
        private static MlGateDecision ml(String reason) {
            return new MlGateDecision(true, reason);
        }

        private static MlGateDecision bypass(String reason) {
            return new MlGateDecision(false, reason);
        }
    }

    private StructuredGateDecision evaluateStructuredJson(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return StructuredGateDecision.notRecognized();
        }

        final JSONObject object;
        try {
            object = new JSONObject(trimmed);
        } catch (Exception ignored) {
            return StructuredGateDecision.notRecognized();
        }

        boolean sawKnownKey = false;
        boolean sawPayload = false;

        for (String key : object.keySet()) {
            String normalized = normalizeStructuredKey(key);

            if (JSON_ENVELOPE_SAFE_KEYS.contains(normalized)) {
                sawKnownKey = true;
                continue;
            }

            if (JSON_PAYLOAD_KEYS.contains(normalized)) {
                sawKnownKey = true;
                sawPayload = true;

                Object value = object.opt(key);
                if (!(value instanceof String payload)) {
                    return StructuredGateDecision.requiresMl(
                            "non-string-json-payload"
                    );
                }

                DeterministicScanResult payloadScan = scan(payload);
                if (payloadScan.requiresMl()) {
                    return StructuredGateDecision.requiresMl(
                            "json-payload-requires-ml"
                    );
                }
                continue;
            }

            // Unknown JSON fields remain conservative. We do not infer that an
            // unfamiliar field is non-sensitive merely because it is outside
            // the message payload.
            return StructuredGateDecision.requiresMl(
                    "unknown-json-field:" + key
            );
        }

        if (!sawKnownKey) {
            return StructuredGateDecision.notRecognized();
        }

        // A recognized envelope with only approved metadata is safe to bypass.
        // If a message payload exists, it has already been independently
        // scanned above.
        return StructuredGateDecision.bypass(
                sawPayload
                        ? "json-envelope-payload-resolved"
                        : "json-envelope-metadata-only"
        );
    }

    private static String normalizeStructuredKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private record StructuredGateDecision(
            boolean recognized,
            boolean requiresMl,
            String reason
    ) {
        private static StructuredGateDecision notRecognized() {
            return new StructuredGateDecision(false, true, "not-json-envelope");
        }

        private static StructuredGateDecision requiresMl(String reason) {
            return new StructuredGateDecision(true, true, reason);
        }

        private static StructuredGateDecision bypass(String reason) {
            return new StructuredGateDecision(true, false, reason);
        }
    }

    private static boolean containsCapitalizedNameOutsideEvidence(
            String text,
            List<DetectionEvidence> evidence
    ) {
        Matcher matcher = CAPITALIZED_NAME.matcher(text);
        while (matcher.find()) {
            boolean covered = false;
            for (DetectionEvidence item : evidence) {
                if (item.overlaps(matcher.start(), matcher.end())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(".", "");
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

    private static String owningKey(String text, int valueStart) {
        int left = Math.max(0, valueStart - 80);
        String prefix = text.substring(left, valueStart);
        Matcher matcher = OWNING_KEY.matcher(prefix);
        if (!matcher.find()) {
            return "";
        }
        return normalizeKey(matcher.group(1));
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
