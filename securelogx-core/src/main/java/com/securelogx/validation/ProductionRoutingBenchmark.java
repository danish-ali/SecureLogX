package com.securelogx.validation;

import com.securelogx.detection.DetectionEvidence;
import com.securelogx.detection.DeterministicScanResult;
import com.securelogx.detection.DeterministicSensitiveDataDetector;
import com.securelogx.detection.ResolutionAction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproducible routing-only benchmark for realistic application traffic mixes.
 *
 * This benchmark is deliberately separate from the NER-heavy security corpus.
 * It does not run ONNX inference. Its purpose is to measure which records the
 * hybrid gate would send to ML under different production traffic mixes while
 * enforcing fast-path safety expectations.
 */
public final class ProductionRoutingBenchmark {

    private static final double SCENARIO_A_MAX_ML_RATE = 0.20;

    private ProductionRoutingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path resultPath = args.length > 0
                ? Path.of(args[0])
                : Path.of("reports/production-routing-benchmark/result.json");

        DeterministicSensitiveDataDetector detector =
                new DeterministicSensitiveDataDetector();

        List<Scenario> scenarios = List.of(
                new Scenario(
                        "A",
                        "normal-operations-90-10",
                        1000,
                        Map.of(
                                RecordClass.ORDINARY_SAFE, 850,
                                RecordClass.NEGATIVE_TECHNICAL, 50,
                                RecordClass.DETERMINISTIC_SENSITIVE, 50,
                                RecordClass.CONTEXTUAL_SENSITIVE, 40,
                                RecordClass.ADVERSARIAL, 10
                        )
                ),
                new Scenario(
                        "B",
                        "mixed-operations-75-25",
                        1000,
                        Map.of(
                                RecordClass.ORDINARY_SAFE, 675,
                                RecordClass.NEGATIVE_TECHNICAL, 75,
                                RecordClass.DETERMINISTIC_SENSITIVE, 100,
                                RecordClass.CONTEXTUAL_SENSITIVE, 125,
                                RecordClass.ADVERSARIAL, 25
                        )
                ),
                new Scenario(
                        "C",
                        "balanced-50-50",
                        1000,
                        Map.of(
                                RecordClass.ORDINARY_SAFE, 400,
                                RecordClass.NEGATIVE_TECHNICAL, 100,
                                RecordClass.DETERMINISTIC_SENSITIVE, 150,
                                RecordClass.CONTEXTUAL_SENSITIVE, 300,
                                RecordClass.ADVERSARIAL, 50
                        )
                ),
                new Scenario(
                        "D",
                        "high-risk-stress",
                        1000,
                        Map.of(
                                RecordClass.ORDINARY_SAFE, 100,
                                RecordClass.NEGATIVE_TECHNICAL, 50,
                                RecordClass.DETERMINISTIC_SENSITIVE, 250,
                                RecordClass.CONTEXTUAL_SENSITIVE, 500,
                                RecordClass.ADVERSARIAL, 100
                        )
                )
        );

        JSONArray scenarioResults = new JSONArray();
        long totalRecords = 0;
        long totalMl = 0;
        long totalFast = 0;
        long totalUnsafeBypass = 0;
        long totalFastPathUncoveredSensitive = 0;
        long totalNegativeOvermask = 0;

        for (Scenario scenario : scenarios) {
            List<Fixture> fixtures = buildScenario(scenario);
            ScenarioResult result = evaluateScenario(
                    scenario,
                    fixtures,
                    detector
            );
            scenarioResults.put(result.toJson());

            totalRecords += result.records;
            totalMl += result.mlRecords;
            totalFast += result.fastPathRecords;
            totalUnsafeBypass += result.unsafeBypass;
            totalFastPathUncoveredSensitive +=
                    result.fastPathUncoveredSensitive;
            totalNegativeOvermask += result.negativeOvermask;

            printScenario(result);
        }

        boolean safetyPassed = totalUnsafeBypass == 0
                && totalFastPathUncoveredSensitive == 0
                && totalNegativeOvermask == 0;

        JSONObject output = new JSONObject();
        output.put(
                "status",
                safetyPassed
                        ? "PRODUCTION ROUTING BENCHMARK SAFETY PASSED"
                        : "PRODUCTION ROUTING BENCHMARK SAFETY FAILED"
        );
        output.put("passed", safetyPassed);
        output.put("routing_only", true);
        output.put("onnx_inference", false);
        output.put("records", totalRecords);
        output.put("ml_records", totalMl);
        output.put("fast_path_records", totalFast);
        output.put(
                "ml_invocation_rate",
                rate(totalMl, totalRecords)
        );
        output.put(
                "fast_path_rate",
                rate(totalFast, totalRecords)
        );
        output.put("unsafe_bypass", totalUnsafeBypass);
        output.put(
                "fast_path_uncovered_sensitive",
                totalFastPathUncoveredSensitive
        );
        output.put("negative_overmask", totalNegativeOvermask);
        output.put("scenario_a_max_ml_rate", SCENARIO_A_MAX_ML_RATE);
        output.put("scenarios", scenarioResults);

        Path parent = resultPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                resultPath,
                output.toString(2) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );

        System.out.println();
        System.out.println(output.getString("status"));
        System.out.println("Total records: " + totalRecords);
        System.out.println(
                "Overall ML invocation: "
                        + percent(rate(totalMl, totalRecords))
        );
        System.out.println(
                "Overall fast path: "
                        + percent(rate(totalFast, totalRecords))
        );
        System.out.println("Unsafe bypass: " + totalUnsafeBypass);
        System.out.println(
                "Fast-path uncovered sensitive values: "
                        + totalFastPathUncoveredSensitive
        );
        System.out.println(
                "Negative technical overmask: " + totalNegativeOvermask
        );
        System.out.println("Result: " + resultPath);

        if (!safetyPassed) {
            throw new IllegalStateException(
                    "Production routing benchmark failed safety assertions"
            );
        }
    }

    private static ScenarioResult evaluateScenario(
            Scenario scenario,
            List<Fixture> fixtures,
            DeterministicSensitiveDataDetector detector
    ) {
        long mlRecords = 0;
        long fastPathRecords = 0;
        long unsafeBypass = 0;
        long fastPathUncoveredSensitive = 0;
        long negativeOvermask = 0;

        Map<RecordClass, Long> byClass = new EnumMap<>(RecordClass.class);
        Map<RecordClass, Long> mlByClass = new EnumMap<>(RecordClass.class);
        Map<RecordClass, Long> fastByClass = new EnumMap<>(RecordClass.class);
        Map<String, Long> gateReasons = new LinkedHashMap<>();
        List<String> safetyFailures = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            DeterministicScanResult scan = detector.scan(fixture.text);
            byClass.merge(fixture.recordClass, 1L, Long::sum);
            gateReasons.merge(scan.gateReason(), 1L, Long::sum);

            if (scan.requiresMl()) {
                mlRecords++;
                mlByClass.merge(fixture.recordClass, 1L, Long::sum);
            } else {
                fastPathRecords++;
                fastByClass.merge(fixture.recordClass, 1L, Long::sum);
            }

            if (!scan.requiresMl()
                    && fixture.recordClass.requiresContextualMl()) {
                unsafeBypass++;
                addFailure(
                        safetyFailures,
                        fixture.id
                                + " unexpectedly bypassed ML; reason="
                                + scan.gateReason()
                );
            }

            if (!scan.requiresMl()) {
                for (String sensitive : fixture.sensitiveValues) {
                    if (!coveredByMask(
                            fixture.text,
                            sensitive,
                            scan.evidence()
                    )) {
                        fastPathUncoveredSensitive++;
                        addFailure(
                                safetyFailures,
                                fixture.id
                                        + " fast path left sensitive value uncovered: "
                                        + sensitive
                        );
                    }
                }
            }

            for (String allowed : fixture.allowedValues) {
                if (coveredByMask(
                        fixture.text,
                        allowed,
                        scan.evidence()
                )) {
                    negativeOvermask++;
                    addFailure(
                            safetyFailures,
                            fixture.id
                                    + " masked negative technical value: "
                                    + allowed
                    );
                }
            }
        }

        double mlRate = rate(mlRecords, fixtures.size());
        boolean safetyPassed = unsafeBypass == 0
                && fastPathUncoveredSensitive == 0
                && negativeOvermask == 0;
        boolean efficiencyTargetMet = !"A".equals(scenario.id)
                || mlRate <= SCENARIO_A_MAX_ML_RATE;

        return new ScenarioResult(
                scenario.id,
                scenario.name,
                fixtures.size(),
                mlRecords,
                fastPathRecords,
                mlRate,
                unsafeBypass,
                fastPathUncoveredSensitive,
                negativeOvermask,
                safetyPassed,
                efficiencyTargetMet,
                byClass,
                mlByClass,
                fastByClass,
                gateReasons,
                safetyFailures
        );
    }

    private static List<Fixture> buildScenario(Scenario scenario) {
        List<Fixture> fixtures = new ArrayList<>(scenario.records);
        for (RecordClass recordClass : RecordClass.values()) {
            int count = scenario.mix.getOrDefault(recordClass, 0);
            for (int i = 0; i < count; i++) {
                fixtures.add(fixture(
                        scenario.id,
                        recordClass,
                        i
                ));
            }
        }

        if (fixtures.size() != scenario.records) {
            throw new IllegalStateException(
                    "Scenario "
                            + scenario.id
                            + " expected "
                            + scenario.records
                            + " records but generated "
                            + fixtures.size()
            );
        }

        return fixtures;
    }

    private static Fixture fixture(
            String scenario,
            RecordClass recordClass,
            int index
    ) {
        String id = scenario
                + "-"
                + recordClass.name().toLowerCase()
                + "-"
                + String.format("%04d", index);

        return switch (recordClass) {
            case ORDINARY_SAFE -> ordinaryFixture(id, index);
            case NEGATIVE_TECHNICAL -> negativeFixture(id, index);
            case DETERMINISTIC_SENSITIVE -> deterministicFixture(id, index);
            case CONTEXTUAL_SENSITIVE -> contextualFixture(id, index);
            case ADVERSARIAL -> adversarialFixture(id, index);
        };
    }

    private static Fixture ordinaryFixture(String id, int index) {
        return switch (index % 6) {
            case 0 -> fixture(
                    id,
                    "status=SUCCESS action=healthcheck service=payments result=ok",
                    RecordClass.ORDINARY_SAFE
            );
            case 1 -> fixture(
                    id,
                    "level=INFO operation=cacheRefresh service=inventory status=complete",
                    RecordClass.ORDINARY_SAFE
            );
            case 2 -> fixture(
                    id,
                    "{\"@timestamp\":\"2026-09-27T15:00:00Z\","
                            + "\"log.level\":\"INFO\","
                            + "\"service.name\":\"orders\","
                            + "\"trace.id\":\"trace-"
                            + index
                            + "\"}",
                    RecordClass.ORDINARY_SAFE
            );
            case 3 -> fixture(
                    id,
                    "{\"@timestamp\":\"2026-09-27T15:00:00Z\","
                            + "\"log.level\":\"INFO\","
                            + "\"service.name\":\"orders\","
                            + "\"message\":\"status=SUCCESS operation=heartbeat\"}",
                    RecordClass.ORDINARY_SAFE
            );
            case 4 -> fixture(
                    id,
                    "2026-09-27 15:00:00 INFO payment-service "
                            + "operation=refresh status=SUCCESS",
                    RecordClass.ORDINARY_SAFE
            );
            default -> fixture(
                    id,
                    "deployment=canary environment=prod phase=ready status=healthy",
                    RecordClass.ORDINARY_SAFE
            );
        };
    }

    private static Fixture negativeFixture(String id, int index) {
        String version = (1 + index % 8)
                + "."
                + (index % 20)
                + "."
                + (index % 40)
                + "."
                + (index % 10);

        return switch (index % 4) {
            case 0 -> fixture(
                    id,
                    "releaseVersion="
                            + version
                            + " deployment=canary status=healthy",
                    RecordClass.NEGATIVE_TECHNICAL,
                    List.of(),
                    List.of(version)
            );
            case 1 -> fixture(
                    id,
                    "buildVersion="
                            + version
                            + " environment=prod status=ready",
                    RecordClass.NEGATIVE_TECHNICAL,
                    List.of(),
                    List.of(version)
            );
            case 2 -> fixture(
                    id,
                    "artifactVersion="
                            + version
                            + " phase=deploy result=accepted",
                    RecordClass.NEGATIVE_TECHNICAL,
                    List.of(),
                    List.of(version)
            );
            default -> fixture(
                    id,
                    "{\"@timestamp\":\"2026-09-27T15:00:00Z\","
                            + "\"log.level\":\"INFO\","
                            + "\"service.name\":\"catalog\","
                            + "\"environment\":\"prod\"}",
                    RecordClass.NEGATIVE_TECHNICAL
            );
        };
    }

    private static Fixture deterministicFixture(String id, int index) {
        return switch (index % 7) {
            case 0 -> {
                String value = "user" + index + "@example.com";
                yield fixture(
                        id,
                        "email=" + value + " status=accepted",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 1 -> {
                String value = "GB82WEST12345698765432";
                yield fixture(
                        id,
                        "iban=" + value + " status=verified",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 2 -> {
                String value = "4111111111111111";
                yield fixture(
                        id,
                        "card=" + value + " status=declined",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 3 -> {
                String value = "10.20.30." + (1 + index % 200);
                yield fixture(
                        id,
                        "remoteIp=" + value + " status=blocked",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 4 -> {
                String value = "ak_live_AbCdEf1234567890";
                yield fixture(
                        id,
                        "apiKey=" + value + " status=denied",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 5 -> {
                String value = "eyJhbGciOiJIUzI1NiJ9.AbCdEf123456.QrStUv987654";
                yield fixture(
                        id,
                        "authorization=Bearer " + value + " status=denied",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            default -> {
                String value = "json.user" + index + "@example.com";
                yield fixture(
                        id,
                        "{\"@timestamp\":\"2026-09-27T15:00:00Z\","
                                + "\"log.level\":\"WARN\","
                                + "\"service.name\":\"payments\","
                                + "\"message\":\"email="
                                + value
                                + " status=blocked\"}",
                        RecordClass.DETERMINISTIC_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
        };
    }

    private static Fixture contextualFixture(String id, int index) {
        return switch (index % 7) {
            case 0 -> {
                String value = "123-45-" + String.format("%04d", 1000 + index % 8000);
                yield fixture(
                        id,
                        "ssn=" + value + " status=verified",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 1 -> {
                String value = "021000021";
                yield fixture(
                        id,
                        "routing=" + value + " status=pending",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 2 -> {
                String value = "CUST-" + String.format("%06d", index);
                yield fixture(
                        id,
                        "customerId=" + value + " lifecycle=active",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 3 -> {
                String value = "Jane Doe";
                yield fixture(
                        id,
                        "name=" + value + " action=login",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 4 -> {
                String value = "221B Baker Street";
                yield fixture(
                        id,
                        "address=" + value + " action=checkout",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            case 5 -> {
                String value = "+1-704-555-" + String.format("%04d", index % 10000);
                yield fixture(
                        id,
                        "phone=" + value + " purpose=support",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
            default -> {
                String value = "REQ-" + String.format("%08d", index);
                yield fixture(
                        id,
                        "requestId=" + value + " status=processing",
                        RecordClass.CONTEXTUAL_SENSITIVE,
                        List.of(value),
                        List.of()
                );
            }
        };
    }

    private static Fixture adversarialFixture(String id, int index) {
        return switch (index % 5) {
            case 0 -> fixture(
                    id,
                    "owner=Jane Doe email=jane@example.com status=active",
                    RecordClass.ADVERSARIAL,
                    List.of("Jane Doe", "jane@example.com"),
                    List.of()
            );
            case 1 -> fixture(
                    id,
                    "reference=4111111111111111 status=active",
                    RecordClass.ADVERSARIAL,
                    List.of("4111111111111111"),
                    List.of()
            );
            case 2 -> fixture(
                    id,
                    "value=10.20.30.40 status=unknown",
                    RecordClass.ADVERSARIAL,
                    List.of("10.20.30.40"),
                    List.of()
            );
            case 3 -> fixture(
                    id,
                    "{\"@timestamp\":\"2026-09-27T15:00:00Z\","
                            + "\"log.level\":\"INFO\","
                            + "\"customer.secret\":\"SECRET-"
                            + index
                            + "\","
                            + "\"message\":\"status=ok\"}",
                    RecordClass.ADVERSARIAL,
                    List.of("SECRET-" + index),
                    List.of()
            );
            default -> fixture(
                    id,
                    "Contact Jane Doe at jane@example.com immediately",
                    RecordClass.ADVERSARIAL,
                    List.of("Jane Doe", "jane@example.com"),
                    List.of()
            );
        };
    }

    private static Fixture fixture(
            String id,
            String text,
            RecordClass recordClass
    ) {
        return fixture(id, text, recordClass, List.of(), List.of());
    }

    private static Fixture fixture(
            String id,
            String text,
            RecordClass recordClass,
            List<String> sensitiveValues,
            List<String> allowedValues
    ) {
        return new Fixture(
                id,
                text,
                recordClass,
                sensitiveValues,
                allowedValues
        );
    }

    private static boolean coveredByMask(
            String text,
            String value,
            List<DetectionEvidence> evidence
    ) {
        int start = text.indexOf(value);
        if (start < 0) {
            throw new IllegalStateException(
                    "Benchmark fixture value not found in text: " + value
            );
        }
        int end = start + value.length();

        for (DetectionEvidence item : evidence) {
            if (item.action() == ResolutionAction.MASK
                    && item.start() <= start
                    && item.end() >= end) {
                return true;
            }
        }
        return false;
    }

    private static void addFailure(
            List<String> failures,
            String failure
    ) {
        if (failures.size() < 25) {
            failures.add(failure);
        }
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0
                ? 0.0
                : (double) numerator / denominator;
    }

    private static String percent(double value) {
        return String.format("%.2f%%", value * 100.0);
    }

    private static void printScenario(ScenarioResult result) {
        System.out.println();
        System.out.println(
                "Scenario "
                        + result.id
                        + " - "
                        + result.name
        );
        System.out.println("  Records: " + result.records);
        System.out.println(
                "  ML invocation: " + percent(result.mlRate)
        );
        System.out.println(
                "  Fast path: "
                        + percent(rate(
                                result.fastPathRecords,
                                result.records
                        ))
        );
        System.out.println(
                "  Safety: "
                        + (result.safetyPassed ? "PASSED" : "FAILED")
        );
        if ("A".equals(result.id)) {
            System.out.println(
                    "  <=20% ML target: "
                            + (
                                    result.efficiencyTargetMet
                                            ? "MET"
                                            : "NOT MET"
                            )
            );
        }
        System.out.println("  ML invocation by record class:");
        for (Map.Entry<RecordClass, Long> entry : result.byClass.entrySet()) {
            RecordClass recordClass = entry.getKey();
            long total = entry.getValue();
            long ml = result.mlByClass.getOrDefault(recordClass, 0L);
            System.out.println(
                    "    "
                            + recordClass.name().toLowerCase()
                            + ": "
                            + percent(rate(ml, total))
                            + " ("
                            + ml
                            + "/"
                            + total
                            + ")"
            );
        }
        System.out.println("  Top gate reasons:");
        result.gateReasons.entrySet().stream()
                .sorted(
                        (left, right) -> Long.compare(
                                right.getValue(),
                                left.getValue()
                        )
                )
                .limit(8)
                .forEach(
                        entry -> System.out.println(
                                "    "
                                        + entry.getValue()
                                        + "  "
                                        + entry.getKey()
                        )
                );
    }

    private enum RecordClass {
        ORDINARY_SAFE(false),
        DETERMINISTIC_SENSITIVE(false),
        CONTEXTUAL_SENSITIVE(true),
        NEGATIVE_TECHNICAL(false),
        ADVERSARIAL(true);

        private final boolean requiresContextualMl;

        RecordClass(boolean requiresContextualMl) {
            this.requiresContextualMl = requiresContextualMl;
        }

        private boolean requiresContextualMl() {
            return requiresContextualMl;
        }
    }

    private record Fixture(
            String id,
            String text,
            RecordClass recordClass,
            List<String> sensitiveValues,
            List<String> allowedValues
    ) {
        private Fixture {
            sensitiveValues = List.copyOf(sensitiveValues);
            allowedValues = List.copyOf(allowedValues);
        }
    }

    private record Scenario(
            String id,
            String name,
            int records,
            Map<RecordClass, Integer> mix
    ) {
    }

    private static final class ScenarioResult {
        private final String id;
        private final String name;
        private final long records;
        private final long mlRecords;
        private final long fastPathRecords;
        private final double mlRate;
        private final long unsafeBypass;
        private final long fastPathUncoveredSensitive;
        private final long negativeOvermask;
        private final boolean safetyPassed;
        private final boolean efficiencyTargetMet;
        private final Map<RecordClass, Long> byClass;
        private final Map<RecordClass, Long> mlByClass;
        private final Map<RecordClass, Long> fastByClass;
        private final Map<String, Long> gateReasons;
        private final List<String> safetyFailures;

        private ScenarioResult(
                String id,
                String name,
                long records,
                long mlRecords,
                long fastPathRecords,
                double mlRate,
                long unsafeBypass,
                long fastPathUncoveredSensitive,
                long negativeOvermask,
                boolean safetyPassed,
                boolean efficiencyTargetMet,
                Map<RecordClass, Long> byClass,
                Map<RecordClass, Long> mlByClass,
                Map<RecordClass, Long> fastByClass,
                Map<String, Long> gateReasons,
                List<String> safetyFailures
        ) {
            this.id = id;
            this.name = name;
            this.records = records;
            this.mlRecords = mlRecords;
            this.fastPathRecords = fastPathRecords;
            this.mlRate = mlRate;
            this.unsafeBypass = unsafeBypass;
            this.fastPathUncoveredSensitive =
                    fastPathUncoveredSensitive;
            this.negativeOvermask = negativeOvermask;
            this.safetyPassed = safetyPassed;
            this.efficiencyTargetMet = efficiencyTargetMet;
            this.byClass = new EnumMap<>(byClass);
            this.mlByClass = new EnumMap<>(mlByClass);
            this.fastByClass = new EnumMap<>(fastByClass);
            this.gateReasons = new LinkedHashMap<>(gateReasons);
            this.safetyFailures = List.copyOf(safetyFailures);
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("name", name);
            object.put("records", records);
            object.put("ml_records", mlRecords);
            object.put("fast_path_records", fastPathRecords);
            object.put("ml_invocation_rate", mlRate);
            object.put(
                    "fast_path_rate",
                    rate(fastPathRecords, records)
            );
            object.put("unsafe_bypass", unsafeBypass);
            object.put(
                    "fast_path_uncovered_sensitive",
                    fastPathUncoveredSensitive
            );
            object.put("negative_overmask", negativeOvermask);
            object.put("safety_passed", safetyPassed);
            object.put(
                    "efficiency_target_met",
                    efficiencyTargetMet
            );

            JSONObject classes = new JSONObject();
            for (Map.Entry<RecordClass, Long> entry : byClass.entrySet()) {
                RecordClass recordClass = entry.getKey();
                long total = entry.getValue();
                long ml = mlByClass.getOrDefault(recordClass, 0L);
                long fast = fastByClass.getOrDefault(recordClass, 0L);

                JSONObject classResult = new JSONObject();
                classResult.put("records", total);
                classResult.put("ml_records", ml);
                classResult.put("fast_path_records", fast);
                classResult.put("ml_invocation_rate", rate(ml, total));
                classes.put(
                        recordClass.name().toLowerCase(),
                        classResult
                );
            }
            object.put("record_classes", classes);

            JSONObject reasons = new JSONObject();
            gateReasons.entrySet().stream()
                    .sorted(
                            (left, right) -> Long.compare(
                                    right.getValue(),
                                    left.getValue()
                            )
                    )
                    .forEach(
                            entry -> reasons.put(
                                    entry.getKey(),
                                    entry.getValue()
                            )
                    );
            object.put("gate_reasons", reasons);
            object.put(
                    "safety_failures",
                    new JSONArray(safetyFailures)
            );
            return object;
        }
    }
}
