package com.example.rankfinder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benchmark-db;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "tracker.domain=embarkx.com",
        "tracker.check-cron=-"
})
public class RankEvaluationBenchmarkTest {

    @Autowired
    private RankTracker rankTracker;

    @Autowired
    private JdbcClient db;

    @MockitoBean
    private SearchApiClient searchApiClient;

    private static final String TARGET_DOMAIN = "embarkx.com";

    // 50 realistic developer and tutorial test queries
    private static final List<String> TEST_KEYWORDS = List.of(
            "spring boot tutorial", "java backend interview questions", "spring boot rest api guide",
            "learn java microservices", "docker for java developers", "spring security jwt auth",
            "hibernate vs jpa differences", "kafka with spring boot", "spring data jpa pagination",
            "junit 5 mockito tutorial", "system design basics", "java 21 virtual threads",
            "spring boot docker compose", "rest api best practices", "spring boot websocket chat",
            "h2 in memory database spring boot", "spring boot caching redis", "reactive spring webflux",
            "spring boot transaction management", "sql indexing explained", "spring cloud gateway setup",
            "java stream api examples", "spring boot validation annotations", "java concurrency multithreading",
            "spring boot file upload", "maven multi module project", "spring boot acturators metrics",
            "gradle vs maven java", "java record classes explained", "spring boot sendgrid email",
            "graphql with spring boot", "spring batch step by step", "spring boot flyway migrations",
            "elasticsearch spring boot", "distributed locking redis java", "spring boot profile configurations",
            "testcontainers spring boot", "java design patterns singleton factory", "spring boot sleuth zipkin",
            "oauth2 authorization server java", "spring boot global exception handler", "java memory model garbage collection",
            "spring boot pagination sorting", "thymeleaf dynamic table spring boot", "spring boot scheduled cron jobs",
            "java lambda expressions practice", "spring boot prometheus grafana", "resilience4j circuit breaker spring",
            "clean architecture java spring", "spring boot search api rank tracker"
    );

    @Test
    @DisplayName("Evaluate 50 queries for Latency, Throughput, Deduplication, and Accuracy")
    void run50QueryEvaluationSuite() {
        // Clear any previous test entries
        db.sql("DELETE FROM ranking").update();

        // Simulate realistic SERP responses across the 50 queries:
        // Half will contain the target domain at varying positions, half will not (testing 100+ unranked)
        Map<String, Integer> groundTruth = new HashMap<>();
        Random random = new Random(42);

        for (int i = 0; i < TEST_KEYWORDS.size(); i++) {
            String kw = TEST_KEYWORDS.get(i);
            boolean ranked = (i % 2 == 0); // 25 ranked, 25 not ranked
            Integer expectedPos = ranked ? (random.nextInt(45) + 1) : null;
            groundTruth.put(kw, expectedPos);

            List<SearchApiClient.Result> mockResults = new ArrayList<>();
            for (int p = 1; p <= 100; p++) {
                if (ranked && p == expectedPos) {
                    mockResults.add(new SearchApiClient.Result(p, "https://" + TARGET_DOMAIN + "/post-" + p, TARGET_DOMAIN));
                } else {
                    mockResults.add(new SearchApiClient.Result(p, "https://competitor" + p + ".com/article", "competitor" + p + ".com"));
                }
            }
            when(searchApiClient.top100(kw)).thenReturn(mockResults);
        }

        // Measure execution for all 50 queries
        List<Long> latenciesNanos = new ArrayList<>();
        int successfulLookups = 0;
        int accurateIdentifications = 0;

        long suiteStartTime = System.nanoTime();

        for (String kw : TEST_KEYWORDS) {
            long start = System.nanoTime();
            rankTracker.check(kw);
            long elapsed = System.nanoTime() - start;
            latenciesNanos.add(elapsed);

            // Verify in DB
            Integer dbPos = db.sql("SELECT position FROM ranking WHERE keyword = ? AND checked_on = ?")
                    .params(kw, LocalDate.now())
                    .query(Integer.class)
                    .optional()
                    .orElse(null);

            successfulLookups++;
            Integer expected = groundTruth.get(kw);
            if (Objects.equals(dbPos, expected)) {
                accurateIdentifications++;
            }
        }

        long totalSuiteDurationNanos = System.nanoTime() - suiteStartTime;
        double totalSeconds = totalSuiteDurationNanos / 1_000_000_000.0;
        double throughputQueriesPerSec = TEST_KEYWORDS.size() / totalSeconds;

        // Statistics
        latenciesNanos.sort(Long::compareTo);
        double avgLatencyMs = latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p50LatencyMs = latenciesNanos.get((int) (latenciesNanos.size() * 0.50)) / 1_000_000.0;
        double p95LatencyMs = latenciesNanos.get((int) (latenciesNanos.size() * 0.95)) / 1_000_000.0;
        double minLatencyMs = latenciesNanos.get(0) / 1_000_000.0;
        double maxLatencyMs = latenciesNanos.get(latenciesNanos.size() - 1) / 1_000_000.0;
        double accuracyPct = (accurateIdentifications * 100.0) / TEST_KEYWORDS.size();

        // Test Deduplication: Re-running all 50 queries on the same day should make ZERO external calls & fast return
        long dedupStart = System.nanoTime();
        for (String kw : TEST_KEYWORDS) {
            rankTracker.check(kw);
        }
        long dedupDurationNanos = System.nanoTime() - dedupStart;
        double dedupAvgMs = (dedupDurationNanos / 1_000_000.0) / TEST_KEYWORDS.size();

        int finalRowCount = db.sql("SELECT COUNT(*) FROM ranking").query(Integer.class).single();
        double deduplicationEfficiencyPct = (finalRowCount == TEST_KEYWORDS.size()) ? 100.0 : 0.0;

        System.out.println("=================================================");
        System.out.println("            50-QUERY BENCHMARK RESULTS           ");
        System.out.println("=================================================");
        System.out.printf("Total Queries Evaluated     : %d%n", TEST_KEYWORDS.size());
        System.out.printf("Rank Identification Accuracy: %.1f%%%n", accuracyPct);
        System.out.printf("Throughput                  : %.1f queries/sec%n", throughputQueriesPerSec);
        System.out.printf("Average Latency             : %.2f ms%n", avgLatencyMs);
        System.out.printf("Median (P50) Latency        : %.2f ms%n", p50LatencyMs);
        System.out.printf("P95 Latency                 : %.2f ms%n", p95LatencyMs);
        System.out.printf("Min / Max Latency           : %.2f ms / %.2f ms%n", minLatencyMs, maxLatencyMs);
        System.out.printf("Same-Day Cache Dedup Gate   : %.2f ms/query (%.1f%% duplicate elimination)%n",
                dedupAvgMs, deduplicationEfficiencyPct);
        System.out.println("=================================================");

        assertEquals(100.0, accuracyPct, 0.01);
        assertEquals(TEST_KEYWORDS.size(), finalRowCount);
    }
}
