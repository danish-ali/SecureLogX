package com.securelogx.log4j2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sequence verification for the synthetic, delimiter-based application
 * benchmark output only. Do not apply this text parser to arbitrary log
 * payloads or SIEM formats: production consumers should parse typed fields.
 *
 * The reconstruction order is defined ONLY within one instanceId.
 * Gaps are not treated as missing records because other contexts, filtering,
 * fail-closed events and separate destinations may consume sequences.
 */
final class SecureLogXSequenceAudit {

    private SecureLogXSequenceAudit() {
    }

    static Audit inspect(String output) {
        if (output == null) {
            throw new IllegalArgumentException(
                    "Sequence audit requires benchmark output"
            );
        }

        List<Stamp> physical = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, Long> maximumSeenByInstance = new HashMap<>();
        Map<String, List<Stamp>> reconstructed = new LinkedHashMap<>();
        int inversions = 0;

        for (String line : output.lines().toList()) {
            if (!line.startsWith("eventId=")) {
                continue;
            }

            int index = physical.size();
            String instance = field(line, "|instanceId=");
            String sequenceText = field(line, "|seq=");
            String ingressId = field(line, "|ingressId=");
            if (!isCanonicalUuid(instance)) {
                throw new IllegalStateException(
                        "Protected log has invalid instanceId at record "
                                + index
                );
            }

            final long sequence;
            try {
                sequence = Long.parseLong(sequenceText);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(
                        "Protected log has invalid sequence at record "
                                + index, ex
                );
            }
            if (sequence < 1) {
                throw new IllegalStateException(
                        "Protected log has non-positive sequence at record "
                                + index
                );
            }

            String expectedId = instance + "-" + sequence;
            if (!expectedId.equals(ingressId)) {
                throw new IllegalStateException(
                        "Protected log has mismatched ingressId at record "
                                + index
                );
            }
            if (!ids.add(expectedId)) {
                throw new IllegalStateException(
                        "Duplicate SecureLogX ingressId at record " + index
                );
            }

            Long maxSeen = maximumSeenByInstance.get(instance);
            if (maxSeen != null && sequence < maxSeen) {
                inversions++;
            }
            maximumSeenByInstance.merge(instance, sequence, Math::max);

            Stamp stamp = new Stamp(index, instance, sequence, ingressId);
            physical.add(stamp);
            reconstructed.computeIfAbsent(
                    instance, ignored -> new ArrayList<>()
            ).add(stamp);
        }

        if (physical.isEmpty()) {
            throw new IllegalStateException(
                    "No benchmark events found for sequence audit"
            );
        }

        Map<String, List<Stamp>> sorted = new LinkedHashMap<>();
        reconstructed.forEach((instance, records) -> {
            records.sort(
                    java.util.Comparator.comparingLong(Stamp::sequence)
            );
            sorted.put(instance, List.copyOf(records));
        });
        return new Audit(
                physical.size(),
                sorted.size(),
                inversions,
                List.copyOf(physical),
                Collections.unmodifiableMap(sorted)
        );
    }

    private static String field(String line, String label) {
        // The synthetic benchmark format guarantees that metadata is
        // emitted before "|msg=". Never parse arbitrary message contents.
        int headerEnd = line.indexOf("|msg=");
        int start = line.indexOf(label);
        if (headerEnd < 0 || start < 0 || start >= headerEnd) {
            throw new IllegalStateException(
                    "Protected benchmark log is missing sequence metadata"
            );
        }
        start += label.length();
        int end = line.indexOf('|', start);
        if (end < 0 || end > headerEnd || end == start) {
            throw new IllegalStateException(
                    "Protected benchmark log has malformed sequence metadata"
            );
        }
        return line.substring(start, end);
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    record Stamp(
            int physicalLineIndex,
            String instanceId,
            long sequence,
            String ingressId
    ) {
    }

    record Audit(
            int records,
            int instances,
            int physicalOrderInversions,
            List<Stamp> physicalOrder,
            Map<String, List<Stamp>> reconstructedByInstance
    ) {
    }
}
