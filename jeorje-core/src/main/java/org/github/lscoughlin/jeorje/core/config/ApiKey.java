package org.github.lscoughlin.jeorje.core.config;

/** An API key and the name of the variable that held it. The key never shows in {@code toString}. */
public record ApiKey(String envName, String value) {

    @Override
    public String toString() {
        return "ApiKey[envName=" + envName + ", value=" + SecretScrubber.MASK + "]";
    }
}
