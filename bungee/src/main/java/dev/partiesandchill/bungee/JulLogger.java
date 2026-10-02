package dev.partiesandchill.bungee;

import org.slf4j.Marker;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.Serial;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Core logs through SLF4J; on BungeeCord that goes to the plugin's {@code java.util.logging} logger (console + log file). */
final class JulLogger extends LegacyAbstractLogger {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Logger jul;

    JulLogger(Logger jul) {
        this.jul = jul;
        this.name = jul.getName();
    }

    @Override
    public boolean isTraceEnabled() {
        return jul.isLoggable(Level.FINEST);
    }

    @Override
    public boolean isDebugEnabled() {
        return jul.isLoggable(Level.FINE);
    }

    @Override
    public boolean isInfoEnabled() {
        return jul.isLoggable(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return jul.isLoggable(Level.WARNING);
    }

    @Override
    public boolean isErrorEnabled() {
        return jul.isLoggable(Level.SEVERE);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    /** SLF4J already moved a trailing exception argument into {@code throwable}. */
    @Override
    protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, Marker marker, String pattern,
                                               Object[] arguments, Throwable throwable) {
        Level julLevel = switch (level) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
        jul.log(julLevel, MessageFormatter.basicArrayFormat(pattern, arguments), throwable);
    }
}
