package org.github.lscoughlin.jeorje.core.config;

/** Thrown when a config file or value is not valid. The message never holds secret values. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
