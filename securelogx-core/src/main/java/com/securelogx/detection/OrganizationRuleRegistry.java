package com.securelogx.detection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, deployment-scoped registry of human-approved organization rules.
 *
 * Only exact, single-field values can bypass ML. This does not parse arbitrary
 * administrator-provided regex, silently learn rules, or allow free-text bypass.
 */
public final class OrganizationRuleRegistry {
    private static final long MAX_FILE_BYTES = 65_536;
    private static final int MAX_RULES = 128;
    private static final Pattern ORGANIZATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,63}");
    private static final Pattern RULE_ID =
            Pattern.compile("[A-Za-z][A-Za-z0-9_-]{1,63}");
    private static final Pattern FIELD =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{1,40}");
    private static final Pattern PREFIX =
            Pattern.compile("(?:[A-Za-z]{2,12}[-_])?");
    private static final Set<String> ENTITY_TYPES = Set.of(
            "BUSINESS_ID", "DEVICE_ID", "BANK_ACCOUNT_NUMBER",
            "AUTH_TOKEN", "API_KEY"
    );

    private final String organizationId;
    private final int version;
    private final List<ApprovedRule> rules;

    private OrganizationRuleRegistry(
            String organizationId,
            int version,
            List<ApprovedRule> rules
    ) {
        this.organizationId = organizationId;
        this.version = version;
        this.rules = List.copyOf(rules);
    }

    public static OrganizationRuleRegistry disabled() {
        return new OrganizationRuleRegistry("", 0, List.of());
    }

    /**
     * A missing configured path disables organization rules.
     * An invalid configured path/policy fails initialization, never silently
     * reverts to a weaker or cross-organization policy.
     */
    public static OrganizationRuleRegistry load(
            String organizationId,
            String configuredPath
    ) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            return disabled();
        }
        if (organizationId == null
                || !ORGANIZATION_ID.matcher(organizationId).matches()
                || "default".equalsIgnoreCase(organizationId)) {
            throw new IllegalArgumentException(
                    "Explicit valid organization ID required when rules are enabled"
            );
        }

        Path path = Path.of(configuredPath);
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "Organization rules file exceeds configured size boundary"
            );
        }

        JSONObject document;
        try {
            document = new JSONObject(
                    Files.readString(path, StandardCharsets.UTF_8)
            );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid organization rule document", e
            );
        }

        if (!document.keySet().equals(Set.of(
                "organizationId", "version", "rules"
        ))) {
            throw new IllegalArgumentException(
                    "Unexpected organization policy fields"
            );
        }
        String declaredOrg = document.getString("organizationId");
        if (!organizationId.equals(declaredOrg)) {
            throw new IllegalArgumentException(
                    "Organization rule file does not match configured organization"
            );
        }
        int version = document.getInt("version");
        if (version < 1) {
            throw new IllegalArgumentException(
                    "Organization rules require a positive version"
            );
        }
        JSONArray items = document.getJSONArray("rules");
        if (items.length() > MAX_RULES) {
            throw new IllegalArgumentException("Too many organization rules");
        }

        List<ApprovedRule> loaded = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Set<String> fields = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!item.keySet().equals(Set.of(
                    "id", "status", "field", "prefix",
                    "digits", "entityType"
            ))) {
                throw new IllegalArgumentException(
                        "Unexpected rule fields at index " + i
                );
            }
            if (!"APPROVED".equals(item.getString("status"))) {
                throw new IllegalArgumentException(
                        "Only explicitly APPROVED rules may be loaded"
                );
            }
            String id = item.getString("id");
            String field = item.getString("field");
            String prefix = item.getString("prefix");
            int digits = item.getInt("digits");
            String entityType = item.getString("entityType");

            if (!RULE_ID.matcher(id).matches()
                    || !FIELD.matcher(field).matches()
                    || !PREFIX.matcher(prefix).matches()
                    || digits < 4 || digits > 20
                    || !ENTITY_TYPES.contains(entityType)) {
                throw new IllegalArgumentException(
                        "Invalid approved rule definition at index " + i
                );
            }
            if (!identities.add(id)
                    || !fields.add(field.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Duplicate rule ID or field in organization policy"
                );
            }

            // Quoted literals plus bounded digits: no arbitrary regex/ReDoS.
            Pattern exactFieldValue = Pattern.compile(
                    "\\A" + Pattern.quote(field) + "="
                            + "(" + Pattern.quote(prefix)
                            + "[0-9]{" + digits + "})\\z"
            );
            loaded.add(new ApprovedRule(
                    id, field, entityType, exactFieldValue
            ));
        }
        return new OrganizationRuleRegistry(organizationId, version, loaded);
    }

    public String organizationId() {
        return organizationId;
    }

    public int version() {
        return version;
    }

    public int ruleCount() {
        return rules.size();
    }

    /**
     * Only a whole field=value input may be deterministically resolved.
     * Mixed messages and unknown values remain on the normal hybrid path.
     */
    public DeterministicScanResult scanExact(String text) {
        if (text == null || text.isEmpty() || rules.isEmpty()) {
            return null;
        }
        for (ApprovedRule rule : rules) {
            Matcher matcher = rule.exactFieldValue().matcher(text);
            if (matcher.matches()) {
                return new DeterministicScanResult(
                        List.of(new DetectionEvidence(
                                matcher.start(1),
                                matcher.end(1),
                                rule.entityType(),
                                DetectionSource.DETERMINISTIC,
                                ResolutionAction.MASK,
                                1.0,
                                "approved-organization-rule"
                        )),
                        false,
                        "approved-organization-rule"
                );
            }
        }
        return null;
    }

    private record ApprovedRule(
            String id,
            String field,
            String entityType,
            Pattern exactFieldValue
    ) {
    }
}
