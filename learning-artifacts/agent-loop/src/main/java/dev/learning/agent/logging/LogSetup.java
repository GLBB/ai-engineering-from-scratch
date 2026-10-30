package dev.learning.agent.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Configures console diagnostics and rotating project-local log files. */
public final class LogSetup {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private LogSetup() {}

    public static Path configure(Path projectRoot) throws IOException {
        Path directory = projectRoot.resolve("logs");
        Files.createDirectories(directory);
        Logger logger = Logger.getLogger("dev.learning.agent");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.FINE);
        for (Handler old : logger.getHandlers()) { logger.removeHandler(old); old.close(); }

        Formatter format = new Formatter() {
            @Override public String format(LogRecord record) {
                return TIME.format(record.getInstant()) + " " + record.getLevel() + " "
                        + record.getLoggerName() + " - " + formatMessage(record) + System.lineSeparator();
            }
        };
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(consoleLevel());
        console.setFormatter(format);
        logger.addHandler(console);

        FileHandler file = new FileHandler(directory.resolve("agent-%g.log").toString(),
                1_000_000, 5, true);
        file.setLevel(Level.FINE);
        file.setFormatter(format);
        logger.addHandler(file);
        return directory;
    }

    private static Level consoleLevel() {
        String raw = System.getenv().getOrDefault("AGENT_LOG_LEVEL", "INFO").toUpperCase();
        try { return Level.parse(raw); }
        catch (IllegalArgumentException ignored) { return Level.INFO; }
    }
}
