package org.github.lscoughlin.jeorje.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.config.SecretScrubber;
import org.slf4j.LoggerFactory;

/**
 * Installs the Logback appenders. The file appender rolls each day and keeps 14 files. The run mode
 * logs to standard error and not to the file.
 */
public final class LoggingSetup {

    static final int KEPT_FILES = 14;
    private static final String PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSS} %-5level [%thread] %logger{20} - %msg%n";

    private LoggingSetup() {}

    /** A layout that removes secrets from each log line. */
    static final class ScrubbingLayout extends PatternLayout {
        private final SecretScrubber scrubber;

        ScrubbingLayout(SecretScrubber scrubber) {
            this.scrubber = scrubber;
        }

        @Override
        public String doLayout(ILoggingEvent event) {
            return scrubber.scrub(super.doLayout(event));
        }
    }

    public static void install(String levelName, Path file, boolean toStderr) {
        install(levelName, file, toStderr, new SecretScrubber());
    }

    /** @param scrubber removes secrets from every log line, so no log file or log stream has a key */
    public static void install(String levelName, Path file, boolean toStderr, SecretScrubber scrubber) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        var level = Level.toLevel(levelName, Level.INFO);
        var layout = new ScrubbingLayout(scrubber);
        layout.setContext(context);
        layout.setPattern(PATTERN);
        layout.start();
        var encoder = new LayoutWrappingEncoder<ILoggingEvent>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.start();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(level);
        if (toStderr) {
            var console = new ConsoleAppender<ILoggingEvent>();
            console.setContext(context);
            console.setName("stderr");
            console.setTarget("System.err");
            console.setEncoder(encoder);
            console.start();
            root.addAppender(console);
        } else {
            var appender = new RollingFileAppender<ILoggingEvent>();
            appender.setContext(context);
            appender.setName("file");
            appender.setFile(file.toString());
            var policy = new TimeBasedRollingPolicy<ILoggingEvent>();
            policy.setContext(context);
            policy.setParent(appender);
            policy.setFileNamePattern(file + ".%d{yyyy-MM-dd}");
            policy.setMaxHistory(KEPT_FILES);
            policy.start();
            appender.setRollingPolicy(policy);
            appender.setEncoder(encoder);
            appender.start();
            root.addAppender(appender);
        }
    }
}
