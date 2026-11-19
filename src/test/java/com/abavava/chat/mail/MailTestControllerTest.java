package com.abavava.chat.mail;

import com.abavava.chat.config.AbvProperties;
import com.abavava.chat.domain.AbvException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Временная ручка проверки почты: письмо на любой адрес против фейкового SMTP. */
class MailTestControllerTest {

    @Test
    void sendsAGeneratedCodeToAnyAddress() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer("250 8BITMIME", false)) {
            MailTestController controller = controller(loadProps(
                    "abv.mail.host", "127.0.0.1",
                    "abv.mail.port", Integer.toString(server.port())));

            MailTestController.TestResponse response = controller.send("tester@example.com");
            server.awaitCompletion();

            assertThat(response.sent()).isTrue();
            assertThat(response.error()).isNull();
            assertThat(response.email()).isEqualTo("tester@example.com");
            assertThat(response.code()).matches("\\d{6}");
            assertThat(server.commands()).contains("MAIL FROM:<no-reply@abavava.local>");
            assertThat(server.data()).contains("To: tester@example.com");
        }
    }

    @Test
    void reportsDeliveryFailureWithoutThrowing() throws Exception {
        int port = closedPort();
        MailTestController controller = controller(loadProps(
                "abv.mail.host", "127.0.0.1",
                "abv.mail.port", Integer.toString(port)));

        MailTestController.TestResponse response = controller.send("tester@example.com");

        assertThat(response.sent()).isFalse();
        assertThat(response.error()).contains("Exception");
        assertThat(response.code()).matches("\\d{6}");
    }

    @Test
    void reportsDisabledMail() throws Exception {
        MailTestController controller = controller(loadProps("abv.mail.enabled", "false"));

        MailTestController.TestResponse response = controller.send("tester@example.com");

        assertThat(response.sent()).isFalse();
        assertThat(response.error()).isEqualTo("abv.mail.enabled=false");
    }

    @Test
    void hidesItselfWhenSendCodeIsOff() throws Exception {
        MailTestController controller = controller(loadProps("abv.email-verification.send-code", "off"));

        assertThatThrownBy(() -> controller.send("tester@example.com"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.NOT_FOUND));
    }

    @Test
    void hidesItselfWithDevModeInProduction() throws Exception {
        MailTestController controller = controller(loadProps("abv.env", "production"));

        assertThatThrownBy(() -> controller.send("tester@example.com"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> assertThat(((AbvException) error).code())
                        .isEqualTo(AbvException.Code.NOT_FOUND));
    }

    @Test
    void rejectsMalformedAddress() throws Exception {
        MailTestController controller = controller(loadProps());

        assertThatThrownBy(() -> controller.send("not-an-address"))
                .isInstanceOf(AbvException.class)
                .satisfies(error -> {
                    AbvException e = (AbvException) error;
                    assertThat(e.code()).isEqualTo(AbvException.Code.VALIDATION_FAILED);
                    assertThat(e.details()).containsEntry("field", "email");
                });
    }

    private static MailTestController controller(AbvProperties props) {
        SmtpMailSender sender = new SmtpMailSender(props);
        return new MailTestController(props, new VerificationMailer(new MailTemplates(), sender, props));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static AbvProperties loadProps(String... overrides) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        ConfigurationPropertySource base = ConfigurationPropertySources.from(sources).iterator().next();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < overrides.length; i += 2) {
            values.put(overrides[i], overrides[i + 1]);
        }
        // Первый источник в Binder имеет приоритет: переопределения — раньше yml.
        return new Binder(new MapConfigurationPropertySource(values), base)
                .bind("abv", Bindable.of(AbvProperties.class))
                .orElseThrow(() -> new AssertionError("abv section is missing in application.yml"));
    }
}
