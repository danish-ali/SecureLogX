package com.securelogx.detection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationRuleRegistryTest {

    @TempDir
    Path temp;

    private Path file(String org, String field) throws IOException {
        Path file = temp.resolve("approved-" + org + ".json");
        Files.writeString(file, """
                {
                  "organizationId": "%s",
                  "version": 1,
                  "rules": [
                    {
                      "id": "customer-reference",
                      "status": "APPROVED",
                      "field": "%s",
                      "prefix": "CUST-",
                      "digits": 6,
                      "entityType": "BUSINESS_ID"
                    }
                  ]
                }
                """.formatted(org, field));
        return file;
    }

    @Test
    void approvedWholeFieldBypassesMlAndMasksValue() throws Exception {
        OrganizationRuleRegistry rules =
                OrganizationRuleRegistry.load(
                        "ORG42", file("ORG42", "customerReference").toString()
                );
        HybridMaskingPipeline pipeline = new HybridMaskingPipeline(rules);
        String text = "customerReference=CUST-834729";
        DeterministicScanResult scan = pipeline.scan(text);

        assertFalse(scan.requiresMl());
        assertEquals("approved-organization-rule", scan.gateReason());
        assertEquals(1, scan.evidence().size());
        assertEquals(ResolutionAction.MASK, scan.evidence().get(0).action());
        assertEquals("BUSINESS_ID", scan.evidence().get(0).entityType());
        assertEquals(
                "customerReference=****-******",
                pipeline.maskWithoutMl(text, scan, false)
        );
    }

    @Test
    void mixedContentAndMismatchedFieldsRemainMlRouted() throws Exception {
        HybridMaskingPipeline pipeline = new HybridMaskingPipeline(
                OrganizationRuleRegistry.load(
                        "ORG42", file("ORG42", "customerReference").toString()
                )
        );
        assertTrue(
                pipeline.scan("customerReference=CUST-834729 email=alice@example.com")
                        .requiresMl()
        );
        assertTrue(
                pipeline.scan("customerReference=CUST-834730 extra")
                        .requiresMl()
        );
        assertTrue(
                pipeline.scan("customerReference=CUST-8347298")
                        .requiresMl()
        );
        assertTrue(
                pipeline.scan("otherReference=CUST-834729")
                        .requiresMl()
        );
        assertTrue(
                pipeline.scan("customerReference=CUST-834729\nsecret=123")
                        .requiresMl()
        );
    }

    @Test
    void organizationMismatchFailsInitialization() throws Exception {
        Path rules = file("ORG42", "customerReference");
        assertThrows(
                IllegalArgumentException.class,
                () -> OrganizationRuleRegistry.load("ORG99", rules.toString())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> OrganizationRuleRegistry.load("default", rules.toString())
        );
        assertEquals(
                0,
                OrganizationRuleRegistry.load("ORG99", "").ruleCount()
        );
    }

    @Test
    void rulesAreIsolatedBetweenOrganizationDeployments() throws Exception {
        OrganizationRuleRegistry first = OrganizationRuleRegistry.load(
                "ORG42", file("ORG42", "customerReference").toString()
        );
        OrganizationRuleRegistry second = OrganizationRuleRegistry.load(
                "ORG43", file("ORG43", "claimReference").toString()
        );
        assertNotNull(first.scanExact("customerReference=CUST-834729"));
        assertNull(second.scanExact("customerReference=CUST-834729"));
        assertNotNull(second.scanExact("claimReference=CUST-834729"));
        assertNull(first.scanExact("claimReference=CUST-834729"));
    }

    @Test
    void proposedRulesCannotBeActivatedAsApproved() throws Exception {
        Path rules = file("ORG42", "customerReference");
        String proposed = Files.readString(rules)
                .replace("\"APPROVED\"", "\"PROPOSED\"");
        Files.writeString(rules, proposed);
        assertThrows(
                IllegalArgumentException.class,
                () -> OrganizationRuleRegistry.load("ORG42", rules.toString())
        );
    }

    @Test
    void safePatternGrammarRejectsUnboundedRegex() throws Exception {
        Path rules = file("ORG42", "customerReference");
        String malicious = Files.readString(rules)
                .replace("\"CUST-\"", "\"(.*)+\"");
        Files.writeString(rules, malicious);
        assertThrows(
                IllegalArgumentException.class,
                () -> OrganizationRuleRegistry.load("ORG42", rules.toString())
        );
    }

    @Test
    void absenceOfPolicyPreservesOriginalRouting() {
        HybridMaskingPipeline pipeline = new HybridMaskingPipeline();
        assertTrue(
                pipeline.scan("customerReference=CUST-834729").requiresMl()
        );
    }
    @Test
    void approvedOperationalEventIdCanBypassMlWithoutMasking()
            throws Exception {
        Path policyFile = file("ORG42", "customerReference");
        org.json.JSONObject policy = new org.json.JSONObject(
                Files.readString(policyFile)
        );
        policy.put("safeMetadata", new org.json.JSONArray().put(
                new org.json.JSONObject()
                        .put("id", "event-id")
                        .put("status", "APPROVED")
                        .put("field", "eventId")
                        .put("prefix", "bench-")
                        .put("digits", 2)
        ));
        Files.writeString(policyFile, policy.toString(2));

        HybridMaskingPipeline pipeline = new HybridMaskingPipeline(
                OrganizationRuleRegistry.load(
                        "ORG42", policyFile.toString()
                )
        );
        String safe = "eventId=bench-12";
        DeterministicScanResult scan = pipeline.scan(safe);
        assertFalse(scan.requiresMl());
        assertTrue(scan.evidence().isEmpty());
        assertEquals(
                "approved-organization-safe-metadata",
                scan.gateReason()
        );
        assertEquals(safe, pipeline.maskWithoutMl(safe, scan, false));

        assertTrue(pipeline.scan("eventId=bench-123").requiresMl());
        assertTrue(pipeline.scan("eventId=bench-12 ssn=123-45-6789")
                .requiresMl());
        assertTrue(pipeline.scan("customer=CUST-834729").requiresMl());
        assertTrue(pipeline.scan("eventId=bench-12\nssn=123-45-6789")
                .requiresMl());
    }

    @Test
    void sensitiveFieldsCannotBeWhitelistedAsSafeMetadata()
            throws Exception {
        Path policyFile = file("ORG42", "customerReference");
        org.json.JSONObject policy = new org.json.JSONObject(
                Files.readString(policyFile)
        );
        policy.put("safeMetadata", new org.json.JSONArray().put(
                new org.json.JSONObject()
                        .put("id", "customer-allow")
                        .put("status", "APPROVED")
                        .put("field", "customer")
                        .put("prefix", "CUST-")
                        .put("digits", 6)
        ));
        Files.writeString(policyFile, policy.toString(2));
        assertThrows(
                IllegalArgumentException.class,
                () -> OrganizationRuleRegistry.load(
                        "ORG42", policyFile.toString()
                )
        );
    }

}
