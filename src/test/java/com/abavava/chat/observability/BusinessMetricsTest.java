package com.abavava.chat.observability;

import com.abavava.chat.contract.RealtimeStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бизнес-метрики: имена, метки, кардинальность и формат Prometheus.
 *
 * <p>Тест фиксирует контракт между backend и Grafana/Prometheus: именно эти
 * имена и метки используют панели дашборда и PromQL в правилах алертов
 * (monitoring/grafana/dashboards/ababawa-business.json,
 * monitoring/prometheus/rules/ababawa.yml).
 */
class BusinessMetricsTest {

    /** Метки, допустимые в метриках ababawa: каждая — из закрытого набора. */
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "result", "reason", "purpose", "kind", "status", "event", "role", "scope", "channel");

    /** Полный набор имён метрик в формате Prometheus exposition. */
    private static final List<String> EXPECTED_PROMETHEUS_NAMES = List.of(
            "ababawa_auth_registration_total",
            "ababawa_auth_otp_requests_total",
            "ababawa_auth_otp_emails_total",
            "ababawa_auth_otp_verifications_total",
            "ababawa_auth_logins_total",
            "ababawa_auth_refresh_total",
            "ababawa_calls_start_total",
            "ababawa_calls_initiated_total",
            "ababawa_calls_accepted_total",
            "ababawa_calls_ended_total",
            "ababawa_calls_signaling_total",
            "ababawa_calls_media_connected_total",
            "ababawa_calls_duration_seconds_count",
            "ababawa_mail_deliveries_total",
            "ababawa_security_rate_limited_total",
            "ababawa_security_access_denied_total",
            "ababawa_call_telemetry_events_total",
            "ababawa_ws_connections",
            "ababawa_ws_online_users");

    @Test
    void countsSuccessAndFailureWithBoundedLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.registration(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.registration(BusinessMetrics.FAILURE, "email_taken");
        metrics.login(BusinessMetrics.FAILURE, "invalid_credentials");
        metrics.otpVerification("email_verify", BusinessMetrics.FAILURE, "invalid_code");

        assertThat(counter(registry, "ababawa.auth.registration",
                "result", "success", "reason", "none").count()).isEqualTo(1.0);
        assertThat(counter(registry, "ababawa.auth.registration",
                "result", "failure", "reason", "email_taken").count()).isEqualTo(1.0);
        assertThat(counter(registry, "ababawa.auth.logins",
                "result", "failure", "reason", "invalid_credentials").count()).isEqualTo(1.0);
        assertThat(counter(registry, "ababawa.auth.otp.verifications",
                "purpose", "email_verify", "result", "failure", "reason", "invalid_code").count())
                .isEqualTo(1.0);
    }

    @Test
    void unknownLabelValuesCollapseToOtherInsteadOfCreatingSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        // Так делать нельзя (IP/email/произвольный текст), но даже если кто-то
        // передаст такое значение, новой временной серии не появится.
        metrics.login(BusinessMetrics.FAILURE, "203.0.113.7");
        metrics.login(BusinessMetrics.FAILURE, "user@example.com");

        assertThat(counter(registry, "ababawa.auth.logins",
                "result", "failure", "reason", BusinessMetrics.OTHER).count()).isEqualTo(2.0);
        assertThat(registry.find("ababawa.auth.logins")
                .tag("reason", "203.0.113.7").counter()).isNull();
        assertThat(registry.find("ababawa.auth.logins")
                .tag("reason", "user@example.com").counter()).isNull();
        assertThat(registry.find("ababawa.auth.logins").counters()).hasSize(1);
    }

    @Test
    void everyMeterUsesOnlyBoundedTagKeysAndSafeValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);
        exerciseEveryMetric(metrics);

        assertThat(registry.getMeters()).isNotEmpty();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ALLOWED_TAG_KEYS)
                        .as("метка %s у метрики %s", tag.getKey(), meter.getId().getName())
                        .contains(tag.getKey());
                assertThat(tag.getValue())
                        .as("значение метки %s у метрики %s", tag.getKey(), meter.getId().getName())
                        .doesNotContain("@")
                        .doesNotContain(".0.")
                        .doesNotContain("::")
                        .doesNotMatch(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*");
            }
        }
    }

    @Test
    void prometheusExpositionKeepsPrefixAndTotalSuffix() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        enableHistogram(registry, "ababawa.calls.duration");
        BusinessMetrics metrics = new BusinessMetrics(registry);
        new RealtimeMetrics(registry, new MutableRealtimeStats());
        exerciseEveryMetric(metrics);

        String scrape = registry.scrape();
        for (String name : EXPECTED_PROMETHEUS_NAMES) {
            assertThat(scrape).as("метрика %s в /actuator/prometheus", name).contains(name);
        }
        // Гистограмма таймера (management.metrics.distribution.percentiles-histogram)
        // нужна для histogram_quantile() на панели p95 длительности звонка.
        assertThat(scrape).contains("ababawa_calls_duration_seconds_bucket{");
        // Метки в exposition отсортированы по имени; значения — из набора.
        assertThat(scrape).contains("ababawa_auth_logins_total{reason=\"invalid_credentials\",result=\"failure\"}");
        assertThat(scrape).contains("ababawa_calls_signaling_total{event=\"candidate\",reason=\"peer_offline\",result=\"failure\"}");
        assertThat(scrape).contains("kind=\"audio\"");
        // Ни одного IP, email или uuid в метках.
        assertThat(scrape).doesNotContain("@example.com").doesNotContain("203.0.113.7");
    }

    /** Повторяет percentiles-histogram из application.yml для одного префикса имени. */
    private static void enableHistogram(PrometheusMeterRegistry registry, String prefix) {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().startsWith(prefix)) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }
        });
    }

    @Test
    void realtimeGaugesReadHubStateOnScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableRealtimeStats realtime = new MutableRealtimeStats();
        new RealtimeMetrics(registry, realtime);

        realtime.connections = 4;
        realtime.onlineUsers = 3;

        assertThat(registry.get("ababawa.ws.connections").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("ababawa.ws.online_users").gauge().value()).isEqualTo(3.0);

        realtime.connections = 0;
        assertThat(registry.get("ababawa.ws.connections").gauge().value()).isZero();
    }

    private static void exerciseEveryMetric(BusinessMetrics metrics) {
        metrics.registration(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.registration(BusinessMetrics.FAILURE, "email_taken");
        metrics.otpRequest("registration", BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.otpRequest("resend", BusinessMetrics.FAILURE, "rate_limited");
        metrics.otpEmail("verification", BusinessMetrics.SUCCESS);
        metrics.otpEmail("password_reset", BusinessMetrics.FAILURE);
        metrics.otpVerification("email_verify", BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.otpVerification("password_reset", BusinessMetrics.FAILURE, "expired");
        metrics.login(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.login(BusinessMetrics.FAILURE, "invalid_credentials");
        metrics.refresh(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.refresh(BusinessMetrics.FAILURE, "token_reuse");
        metrics.callStart(BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.callStart(BusinessMetrics.FAILURE, "busy");
        metrics.callCreated("audio");
        metrics.callCreated("video");
        metrics.callAccepted("audio");
        metrics.callEnded("ended");
        metrics.callEnded("missed");
        metrics.callSignaling("offer", BusinessMetrics.SUCCESS, BusinessMetrics.NO_REASON);
        metrics.callSignaling("candidate", BusinessMetrics.FAILURE, "peer_offline");
        metrics.callMediaConnected("caller");
        metrics.callDuration("audio", 42);
        metrics.mailDelivery(BusinessMetrics.SUCCESS);
        metrics.mailDelivery(BusinessMetrics.FAILURE);
        metrics.rateLimited("auth");
        metrics.rateLimited("mail");
        metrics.accessDenied("http", "invalid_token");
        metrics.accessDenied("websocket", "origin_denied");
        metrics.telemetryEvent("ice_state_changed", "accepted");
        metrics.telemetryEvent("call_started", "dropped");
    }

    private static Counter counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as("счётчик %s с метками %s", name, java.util.Arrays.toString(tags))
                .isNotNull();
        return counter;
    }

    /** Мутируемая заглушка realtime-слоя: Gauge должен читать её при скрейпе. */
    private static final class MutableRealtimeStats implements RealtimeStats {

        private int connections;
        private int onlineUsers;

        @Override
        public int onlineUsers() {
            return onlineUsers;
        }

        @Override
        public int connections() {
            return connections;
        }
    }
}
