package com.securelogx.main;

import java.util.concurrent.ThreadLocalRandom;

public class LogGen {

    private static final String[] FIRST = {
            "John","Noor","Sam","Kim","Ravi","Lee","Alex","Pat","Jordan","Taylor","Avery","Morgan","Jamie","Robin"
    };
    private static final String[] LAST = {
            "Smith","Johnson","Patel","Singh","Williams","Brown","Garcia","Ng","Chen","Davis","Lopez","Martin","Clark"
    };
    private static final String[] DOMAINS = {
            "example.com","test.net","corp.local","demo.io","mail.org"
    };
    private static final String[] CITIES = {
            "New York, NY","San Jose, CA","Austin, TX","Seattle, WA","Boston, MA","Chicago, IL","Denver, CO"
    };
    private static final String[] NOISE = {
            "cache warm complete","heartbeat ok","retrying connection to shard=3",
            "user clicked button id=signup","feature flag off: ALPHA_SEARCH",
            "metrics flushed","scheduled job ran in 132ms","OK 200 GET /health"
    };

    public static void main(String[] args) {
        final int TOTAL = (args.length > 0) ? parseIntOr(args[0], 10_000) : 10_000;
        final long seedBase = (args.length > 1) ? safeParseLong(args[1], System.currentTimeMillis()) : System.currentTimeMillis();

        for (int i = 0; i < TOTAL; i++) {
            long seed = seedBase + i; // reserved for deterministic runs later if desired

            // Person/name/email
            String first = pick(FIRST);
            String last  = pick(LAST);
            String email = randomEmail(first, last, pick(DOMAINS));

            // PII-ish fields
            String ssn   = randomSSN();
            String phone = randomUSPhone();
            String cc    = randomVisa16(); // Luhn-valid 16-digit

            // Address-ish
            String addr  = randomAddress();

            // --- Plain stdout lines (NO timestamp/level/message wrapper) ---
            System.out.println(String.format("Customer name: %s %s", first, last));
            System.out.println(String.format("User email: %s", email));
            System.out.println(String.format("Phone: %s", phone));
            System.out.println(String.format("SSN: %s", ssn));
            System.out.println(String.format("Credit Card: %s", cc));
            System.out.println(String.format("Customer address: %s", addr));

            // JSON payload variant (unchanged)
            String payload = String.format(
                    "{\"name\":\"%s %s\",\"email\":\"%s\",\"phone\":\"%s\",\"ssn\":\"%s\",\"address\":\"%s\"}",
                    first, last, email, phone, ssn, addr);
            System.out.println("payload=" + payload);

            // A few non-PII/noise lines to keep data realistic
            System.out.println(pick(NOISE));

            // Occasionally emit XML-ish with multiple fields (previously only email)
            if (rand(0, 10) < 3) {
                // Add more fields into XML to exercise your parsers
                System.out.println(String.format(
                        "<log><user><name>%s %s</name><email>%s</email><phone>%s</phone></user><sev>INFO</sev></log>",
                        first, last, email, phone));
            }

            // Occasionally emit a trace-style line without the "message=" wrapper
            if (rand(0, 10) < 2) {
                System.out.println(String.format(
                        "traceId=%s seq=%d Customer name: %s %s",
                        randomUuidLike(), rand(1, 50), first, last));
            }

            // (Optional) If you DO want SSN in XML for PII tests, uncomment:
            // if (rand(0, 10) < 1) {
            //     System.out.println(String.format(
            //             "<log><user><name>%s %s</name><email>%s</email><phone>%s</phone><ssn>%s</ssn></user><sev>SECURE</sev></log>",
            //             first, last, email, phone, ssn));
            // }
        }
    }

    // ---------------- helpers ----------------

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }
    private static long safeParseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static String pick(String[] arr) {
        return arr[ThreadLocalRandom.current().nextInt(arr.length)];
    }

    private static int rand(int loInclusive, int hiExclusive) {
        return ThreadLocalRandom.current().nextInt(loInclusive, hiExclusive);
    }

    private static String randomEmail(String first, String last, String domain) {
        String base = (first + "." + last).toLowerCase().replaceAll("[^a-z]", "");
        int n = rand(10, 9999);
        return base + n + "@" + domain;
    }

    // US SSN pattern (fake): AAA-GG-SSSS; avoid 000/666/9xx and 00/0000 — just keep it “plausible”
    private static String randomSSN() {
        int area;
        do { area = rand(100, 899); } while (area == 666);
        int group = rand(1, 99);
        int serial = rand(1, 9999);
        return String.format("%03d-%02d-%04d", area, group, serial);
    }

    // US phone formats like (AAA) BBB-CCCC
    private static String randomUSPhone() {
        int a = rand(201, 989); // skip 0/1-leading and low ranges
        int b = rand(200, 999);
        int c = rand(1000, 9999);
        return String.format("(%03d) %03d-%04d", a, b, c);
    }

    // Luhn-valid Visa 16-digit (starts with 4)
    private static String randomVisa16() {
        int[] digits = new int[16];
        digits[0] = 4;
        for (int i = 1; i < 15; i++) digits[i] = rand(0, 10);
        digits[15] = luhnCheckDigit(digits);
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            sb.append(digits[i]);
            if (i == 3 || i == 7 || i == 11) sb.append(' ');
        }
        return sb.toString();
    }

    private static int luhnCheckDigit(int[] first15) {
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            int d = first15[14 - i];            // from right to left over first 15 digits
            if (i % 2 == 0) {                   // double every second digit
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        int mod = sum % 10;
        return (10 - mod) % 10;
    }

    private static String randomAddress() {
        int num = rand(10, 9999);
        String street = pick(new String[]{"Main St","Oak Ave","Pine Rd","Maple Blvd","Cedar St","Elm St"});
        String city = pick(CITIES);
        return num + " " + street + ", " + city;
    }

    // simple UUID-like for logs (not RFC 4122)
    private static String randomUuidLike() {
        return String.format("%08x-%04x-%04x-%04x-%012x",
                rand(0, 0x7FFFFFFF), rand(0, 0xFFFF), rand(0, 0xFFFF), rand(0, 0xFFFF), rand(0, 0x0FFFFFFF));
    }
}
