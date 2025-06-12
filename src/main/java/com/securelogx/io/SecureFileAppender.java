package com.securelogx.io;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Writes final logs (masked or raw) to file with thread safety.
 * This class handles log output. It appends logs to a file defined in your config — like /logs/securelogx.log.
 * In SecureLogX.java, every time a log event is processed, it goes here:
  * appender.write("[SECURE] " + maskedLog);
 */

public class SecureFileAppender {
    private final PrintWriter writer;
    private int writeCount = 0;
    private static final int FLUSH_THRESHOLD = 1000;

    public SecureFileAppender(String filePath) {
        try {
            File logFile = new File(filePath);
            File parent = logFile.getParentFile();

            if (parent != null && !parent.exists()) {
                parent.mkdirs(); // ✅ Create logs/ folder if it doesn't exist
            }

            this.writer = new PrintWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            throw new RuntimeException("Failed to open log file: " + filePath, e);
        }
    }

    //The write(...) method is synchronized to ensure:
//No two threads write at the same time
//Log lines don't get jumbled
    public synchronized void write(String formattedLog) {
    //    writer.println(formattedLog);
   //     writer.flush(); //Each log line gets flushed immediately after writing to avoid losing logs during crashes:
   //     System.out.println(Thread.currentThread().getName() + " writing log");
        writer.println(formattedLog);
        writeCount++;

        if (writeCount >= FLUSH_THRESHOLD) {
            writer.println(formattedLog);
            writer.flush();
            writeCount = 0;
        }
    }

    // Close writer cleanly
    public void close() {

            if (writer != null) {
                writer.flush();
                writer.close();
            }
    }
}
