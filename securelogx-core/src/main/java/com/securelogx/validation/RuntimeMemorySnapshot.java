package com.securelogx.validation;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort memory snapshot that deliberately separates JVM-managed memory
 * from whole-process memory.
 *
 * ONNX Runtime allocates native memory outside the Java heap. Therefore heap
 * usage alone is not a sufficient production memory metric.
 */
public final class RuntimeMemorySnapshot {

    private final String label;
    private final long timestampMillis;
    private final long pid;

    private final long heapUsedBytes;
    private final long heapCommittedBytes;
    private final long heapMaxBytes;
    private final long nonHeapUsedBytes;
    private final long nonHeapCommittedBytes;

    private final long directUsedBytes;
    private final long directCapacityBytes;
    private final long directBufferCount;
    private final long mappedUsedBytes;
    private final long mappedCapacityBytes;
    private final long mappedBufferCount;

    private final long processCommittedVirtualBytes;
    private final long processWorkingSetBytes;
    private final long processPrivateBytes;
    private final long processVirtualBytes;
    private final long gpuProcessMemoryBytes;
    private final String processProbe;

    private RuntimeMemorySnapshot(
            String label,
            long timestampMillis,
            long pid,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long nonHeapCommittedBytes,
            long directUsedBytes,
            long directCapacityBytes,
            long directBufferCount,
            long mappedUsedBytes,
            long mappedCapacityBytes,
            long mappedBufferCount,
            long processCommittedVirtualBytes,
            long processWorkingSetBytes,
            long processPrivateBytes,
            long processVirtualBytes,
            long gpuProcessMemoryBytes,
            String processProbe
    ) {
        this.label = label;
        this.timestampMillis = timestampMillis;
        this.pid = pid;
        this.heapUsedBytes = heapUsedBytes;
        this.heapCommittedBytes = heapCommittedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.nonHeapUsedBytes = nonHeapUsedBytes;
        this.nonHeapCommittedBytes = nonHeapCommittedBytes;
        this.directUsedBytes = directUsedBytes;
        this.directCapacityBytes = directCapacityBytes;
        this.directBufferCount = directBufferCount;
        this.mappedUsedBytes = mappedUsedBytes;
        this.mappedCapacityBytes = mappedCapacityBytes;
        this.mappedBufferCount = mappedBufferCount;
        this.processCommittedVirtualBytes =
                processCommittedVirtualBytes;
        this.processWorkingSetBytes = processWorkingSetBytes;
        this.processPrivateBytes = processPrivateBytes;
        this.processVirtualBytes = processVirtualBytes;
        this.gpuProcessMemoryBytes = gpuProcessMemoryBytes;
        this.processProbe = processProbe;
    }

    public static RuntimeMemorySnapshot capture(String label) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        BufferTotals buffers = bufferTotals();

        long committedVirtual = -1L;
        java.lang.management.OperatingSystemMXBean rawOs =
                ManagementFactory.getOperatingSystemMXBean();
        if (rawOs instanceof com.sun.management.OperatingSystemMXBean os) {
            committedVirtual = os.getCommittedVirtualMemorySize();
        }

        long pid = ProcessHandle.current().pid();
        ProcessMemory process = processMemory(pid);
        long gpuBytes = gpuProcessMemory(pid);

        return new RuntimeMemorySnapshot(
                label,
                System.currentTimeMillis(),
                pid,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                buffers.directUsed,
                buffers.directCapacity,
                buffers.directCount,
                buffers.mappedUsed,
                buffers.mappedCapacity,
                buffers.mappedCount,
                committedVirtual,
                process.workingSetBytes,
                process.privateBytes,
                process.virtualBytes,
                gpuBytes,
                process.probe
        );
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("label", label);
        object.put("timestamp_millis", timestampMillis);
        object.put("pid", pid);

        object.put("heap_used_bytes", heapUsedBytes);
        object.put("heap_committed_bytes", heapCommittedBytes);
        object.put("heap_max_bytes", heapMaxBytes);
        object.put("non_heap_used_bytes", nonHeapUsedBytes);
        object.put("non_heap_committed_bytes", nonHeapCommittedBytes);

        object.put("direct_used_bytes", directUsedBytes);
        object.put("direct_capacity_bytes", directCapacityBytes);
        object.put("direct_buffer_count", directBufferCount);
        object.put("mapped_used_bytes", mappedUsedBytes);
        object.put("mapped_capacity_bytes", mappedCapacityBytes);
        object.put("mapped_buffer_count", mappedBufferCount);

        putOptional(
                object,
                "process_committed_virtual_bytes",
                processCommittedVirtualBytes
        );
        putOptional(
                object,
                "process_working_set_bytes",
                processWorkingSetBytes
        );
        putOptional(
                object,
                "process_private_bytes",
                processPrivateBytes
        );
        putOptional(
                object,
                "process_virtual_bytes",
                processVirtualBytes
        );
        putOptional(
                object,
                "gpu_process_memory_bytes",
                gpuProcessMemoryBytes
        );
        object.put("process_probe", processProbe);
        return object;
    }

    public long heapUsedBytes() {
        return heapUsedBytes;
    }

    public long nonHeapUsedBytes() {
        return nonHeapUsedBytes;
    }

    public long directUsedBytes() {
        return directUsedBytes;
    }

    public long processCommittedVirtualBytes() {
        return processCommittedVirtualBytes;
    }

    public long processWorkingSetBytes() {
        return processWorkingSetBytes;
    }

    public long processPrivateBytes() {
        return processPrivateBytes;
    }

    public long gpuProcessMemoryBytes() {
        return gpuProcessMemoryBytes;
    }

    private static void putOptional(
            JSONObject object,
            String key,
            long value
    ) {
        if (value >= 0) {
            object.put(key, value);
        } else {
            object.put(key, JSONObject.NULL);
        }
    }

    private static BufferTotals bufferTotals() {
        long directUsed = 0;
        long directCapacity = 0;
        long directCount = 0;
        long mappedUsed = 0;
        long mappedCapacity = 0;
        long mappedCount = 0;

        List<BufferPoolMXBean> pools =
                ManagementFactory.getPlatformMXBeans(
                        BufferPoolMXBean.class
                );

        for (BufferPoolMXBean pool : pools) {
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if ("direct".equals(name)) {
                directUsed += pool.getMemoryUsed();
                directCapacity += pool.getTotalCapacity();
                directCount += pool.getCount();
            } else if (name.startsWith("mapped")) {
                mappedUsed += pool.getMemoryUsed();
                mappedCapacity += pool.getTotalCapacity();
                mappedCount += pool.getCount();
            }
        }

        return new BufferTotals(
                directUsed,
                directCapacity,
                directCount,
                mappedUsed,
                mappedCapacity,
                mappedCount
        );
    }

    private static ProcessMemory processMemory(long pid) {
        String os = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);

        try {
            if (os.contains("win")) {
                return windowsProcessMemory(pid);
            }
            if (os.contains("linux")) {
                return linuxProcessMemory(pid);
            }
            if (os.contains("mac")) {
                return macProcessMemory(pid);
            }
        } catch (Exception ignored) {
            // Best-effort probe. JVM metrics remain available.
        }

        return new ProcessMemory(-1, -1, -1, "unavailable");
    }

    private static ProcessMemory windowsProcessMemory(
            long pid
    ) throws Exception {
        String script =
                "$p=Get-Process -Id "
                        + pid
                        + "; Write-Output "
                        + "($p.WorkingSet64.ToString()+','+"
                        + "$p.PrivateMemorySize64.ToString()+','+"
                        + "$p.VirtualMemorySize64.ToString())";

        Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script
        ).redirectErrorStream(true).start();

        String line;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        )) {
            line = reader.readLine();
        }

        int exit = process.waitFor();
        if (exit != 0 || line == null || line.isBlank()) {
            return new ProcessMemory(
                    -1,
                    -1,
                    -1,
                    "windows-powershell-failed"
            );
        }

        String[] values = line.trim().split(",");
        if (values.length != 3) {
            return new ProcessMemory(
                    -1,
                    -1,
                    -1,
                    "windows-powershell-invalid"
            );
        }

        return new ProcessMemory(
                Long.parseLong(values[0].trim()),
                Long.parseLong(values[1].trim()),
                Long.parseLong(values[2].trim()),
                "windows-powershell"
        );
    }

    private static ProcessMemory linuxProcessMemory(
            long pid
    ) throws IOException {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        long rss = -1;
        long virtual = -1;

        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("VmRSS:")) {
                rss = parseLinuxKb(line);
            } else if (line.startsWith("VmSize:")) {
                virtual = parseLinuxKb(line);
            }
        }

        long privateBytes = -1;
        Path rollup =
                Path.of("/proc", Long.toString(pid), "smaps_rollup");
        if (Files.exists(rollup)) {
            long privateKb = 0;
            boolean found = false;
            for (String line : Files.readAllLines(rollup)) {
                if (line.startsWith("Private_Clean:")
                        || line.startsWith("Private_Dirty:")
                        || line.startsWith("Private_Hugetlb:")) {
                    privateKb += parseRawKb(line);
                    found = true;
                }
            }
            if (found) {
                privateBytes = privateKb * 1024L;
            }
        }

        return new ProcessMemory(
                rss,
                privateBytes,
                virtual,
                "linux-proc"
        );
    }

    private static long parseLinuxKb(String line) {
        return parseRawKb(line) * 1024L;
    }

    private static long parseRawKb(String line) {
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            if (part.chars().allMatch(Character::isDigit)) {
                return Long.parseLong(part);
            }
        }
        return 0L;
    }

    private static ProcessMemory macProcessMemory(
            long pid
    ) throws Exception {
        Process process = new ProcessBuilder(
                "ps",
                "-o",
                "rss=,vsz=",
                "-p",
                Long.toString(pid)
        ).redirectErrorStream(true).start();

        String line;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        )) {
            line = reader.readLine();
        }

        int exit = process.waitFor();
        if (exit != 0 || line == null || line.isBlank()) {
            return new ProcessMemory(-1, -1, -1, "mac-ps-failed");
        }

        String[] values = line.trim().split("\\s+");
        if (values.length < 2) {
            return new ProcessMemory(-1, -1, -1, "mac-ps-invalid");
        }

        return new ProcessMemory(
                Long.parseLong(values[0]) * 1024L,
                -1,
                Long.parseLong(values[1]) * 1024L,
                "mac-ps"
        );
    }

    private static long gpuProcessMemory(long pid) {
        try {
            Process process = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-compute-apps=pid,used_gpu_memory",
                    "--format=csv,noheader,nounits"
            ).redirectErrorStream(true).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split(",");
                    if (parts.length != 2) {
                        continue;
                    }

                    long candidatePid =
                            Long.parseLong(parts[0].trim());
                    if (candidatePid != pid) {
                        continue;
                    }

                    String memory = parts[1]
                            .replace("MiB", "")
                            .trim();
                    return Long.parseLong(memory) * 1024L * 1024L;
                }
            }

            process.waitFor();
        } catch (Exception ignored) {
            // GPU metrics are optional.
        }
        return -1L;
    }

    private record BufferTotals(
            long directUsed,
            long directCapacity,
            long directCount,
            long mappedUsed,
            long mappedCapacity,
            long mappedCount
    ) {
    }

    private record ProcessMemory(
            long workingSetBytes,
            long privateBytes,
            long virtualBytes,
            String probe
    ) {
    }
}
