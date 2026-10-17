package com.example.learnhub.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the config server and refreshes the application when the delivered settings change.
 *
 * <p>Why polling rather than Spring Cloud Bus or a filesystem watch (research R4): the config repo is
 * bind-mounted from the host, and bind-mount file events are unreliable on Docker Desktop for Windows
 * and macOS, so a push-based mechanism cannot be relied on to meet the ten seconds FR-016 allows.
 *
 * <p>Deliberately built on {@link HttpClient} and Jackson only, with no servlet or WebFlux types, so
 * the WebFlux gateway can use it unchanged (research R10).
 */
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final ConfigWatcherProperties properties;
    private final String application;
    private final String profiles;
    private final Runnable refresh;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger pollsInFlight = new AtomicInteger();
    private final AtomicInteger maxConcurrentPolls = new AtomicInteger();

    private volatile ScheduledExecutorService scheduler;
    private volatile String lastGoodHash;
    private volatile boolean outageLogged;

    public ConfigWatcher(ConfigWatcherProperties properties, String application, String profiles,
            Runnable refresh) {
        this.properties = properties;
        this.application = application;
        this.profiles = profiles;
        this.refresh = refresh;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // A single thread with fixedDelay: a slow or hanging poll can never overlap the next one.
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "config-watcher");
            thread.setDaemon(true);
            return thread;
        });
        long millis = properties.getInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pollOnce, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Config watcher polling {} for {}/{} every {}",
                properties.getUri(), application, profiles, properties.getInterval());
    }

    public void stop() {
        if (running.compareAndSet(true, false) && scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Highest number of polls ever in flight at once; must stay at 1. */
    public int maxConcurrentPolls() {
        return maxConcurrentPolls.get();
    }

    void pollOnce() {
        int inFlight = pollsInFlight.incrementAndGet();
        maxConcurrentPolls.accumulateAndGet(inFlight, Math::max);
        try {
            String hash = ConfigSourcesHasher.hash(mapper.readTree(fetch()));
            outageLogged = false;

            if (lastGoodHash == null) {
                // First successful poll only records the baseline: the application has just read this
                // configuration at startup, so refreshing here would be pointless work.
                lastGoodHash = hash;
                return;
            }
            if (!hash.equals(lastGoodHash)) {
                log.info("Central configuration changed for {}/{}; refreshing", application, profiles);
                lastGoodHash = hash;
                refresh.run();
            }
        } catch (Exception e) {
            // An outage is not a configuration change: keep the last good state and try again next tick.
            if (!outageLogged) {
                log.warn("Could not read configuration from {} ({}); keeping the last good configuration "
                        + "and retrying every {}", properties.getUri(), e.toString(), properties.getInterval());
                outageLogged = true;
            }
        } finally {
            pollsInFlight.decrementAndGet();
        }
    }

    private String fetch() throws Exception {
        URI uri = URI.create("%s/%s/%s".formatted(trimTrailingSlash(properties.getUri()), application, profiles));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET();

        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            String credentials = properties.getUsername() + ":" + nullToEmpty(properties.getPassword());
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("config server returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String trimTrailingSlash(String uri) {
        return uri != null && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
