package com.abavava.chat.auth;

import com.abavava.chat.http.JsonBody;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP-эндпоинты аутентификации: /api/v1/auth/*.
 *
 * <p>Регистрация двухшаговая: {@code /register} создаёт неподтверждённый
 * аккаунт и отправляет код (202), {@code /verify} проверяет код и выдаёт
 * токены (200). Пока адрес не подтверждён, {@code /login} отвечает 403
 * {@code email_not_verified}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final int MAX_USER_AGENT = 256;
    private static final int MAX_IP = 64;

    private final AuthService auth;
    private final ObjectMapper mapper;

    public AuthController(AuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    /**
     * {@code display_name} принимается только ради клиентов, закешированных до
     * переименования поля в {@code nickname}; уходит в API уже одно имя.
     */
    public record RegisterRequest(
            @JsonProperty("email") String email,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("display_name") String legacyNickname,
            @JsonProperty("password") String password) {
    }

    public record LoginRequest(
            @JsonProperty("email") String email,
            @JsonProperty("password") String password) {
    }

    public record VerifyRequest(
            @JsonProperty("email") String email,
            @JsonProperty("code") String code) {
    }

    public record ResendRequest(
            @JsonProperty("email") String email) {
    }

    public record ForgotPasswordRequest(
            @JsonProperty("email") String email) {
    }

    public record ResetPasswordRequest(
            @JsonProperty("email") String email,
            @JsonProperty("code") String code,
            @JsonProperty("password") String password) {
    }

    public record ForgotPasswordResponse(
            @JsonProperty("email") String email,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("resend_in") long resendIn,
            @JsonProperty("dev_code") String devCode) {
    }

    public record ResendResponse(
            @JsonProperty("resend_in") long resendIn,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("email_sent") boolean emailSent,
            @JsonProperty("dev_code") String devCode) {
    }

    public record RefreshRequest(
            @JsonProperty("refresh_token") String refreshToken) {
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.RegisterPending> register(HttpServletRequest request) {
        RegisterRequest body = JsonBody.required(request, RegisterRequest.class, mapper);
        String nickname = body.nickname() != null ? body.nickname() : body.legacyNickname();
        AuthService.RegisterPending result = auth.register(
                new AuthService.RegisterInput(body.email(), nickname, body.password()), meta(request));
        return ResponseEntity.status(202).body(result);
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthService.AuthResult> verify(HttpServletRequest request) {
        VerifyRequest body = JsonBody.required(request, VerifyRequest.class, mapper);
        AuthService.AuthResult result = auth.verifyEmail(
                new AuthService.VerifyInput(body.email(), body.code()), meta(request));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/resend")
    public ResponseEntity<ResendResponse> resend(HttpServletRequest request) {
        ResendRequest body = JsonBody.required(request, ResendRequest.class, mapper);
        EmailVerificationService.Resent result = auth.resendVerification(body.email(), clientIp(request));
        return ResponseEntity.status(202).body(new ResendResponse(
                result.resendIn().toSeconds(), result.expiresIn().toSeconds(), result.emailQueued(),
                result.devCode()));
    }

    /**
     * Ручная отправка кода на email (проверка SMTP, прогон сценария из
     * REST-клиента). Работает только с существующим неподтверждённым аккаунтом:
     * адреса нет или он уже подтверждён — 409. Режим и гейтинг — в
     * abv.email-verification.send-code (по умолчанию `dev`, только вне production).
     */
    @PostMapping("/send-code")
    public ResponseEntity<ResendResponse> sendCode(HttpServletRequest request) {
        ResendRequest body = JsonBody.required(request, ResendRequest.class, mapper);
        EmailVerificationService.Resent result = auth.sendVerificationCode(body.email(), clientIp(request));
        return ResponseEntity.status(202).body(new ResendResponse(
                result.resendIn().toSeconds(), result.expiresIn().toSeconds(), result.emailQueued(),
                result.devCode()));
    }

    /**
     * Сброс пароля, шаг 1: код на email. Ответ не раскрывает, есть ли такой
     * адрес: для неизвестного email — тот же 202 и те же поля, просто письма нет.
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(HttpServletRequest request) {
        ForgotPasswordRequest body = JsonBody.required(request, ForgotPasswordRequest.class, mapper);
        PasswordResetService.Requested result = auth.forgotPassword(body.email(), clientIp(request));
        return ResponseEntity.status(202).body(new ForgotPasswordResponse(
                result.email(), result.expiresIn().toSeconds(), result.resendIn().toSeconds(),
                result.devCode()));
    }

    /**
     * Сброс пароля, шаг 2: код + новый пароль. 204 без тела; после успеха все
     * refresh-токены пользователя отозваны, поэтому нужно войти заново.
     */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(HttpServletRequest request) {
        ResetPasswordRequest body = JsonBody.required(request, ResetPasswordRequest.class, mapper);
        auth.resetPassword(body.email(), body.code(), body.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthResult> login(HttpServletRequest request) {
        LoginRequest body = JsonBody.required(request, LoginRequest.class, mapper);
        return ResponseEntity.ok(auth.login(new AuthService.LoginInput(body.email(), body.password()), meta(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthService.AuthResult> refresh(HttpServletRequest request) {
        RefreshRequest body = JsonBody.required(request, RefreshRequest.class, mapper);
        return ResponseEntity.ok(auth.refresh(body.refreshToken(), meta(request)));
    }

    /** Идемпотентно: 204 и без тела, даже если токен не передан. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        RefreshRequest body = JsonBody.optional(request, RefreshRequest.class, mapper);
        auth.logout(body == null ? null : body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static AuthService.SessionMeta meta(HttpServletRequest request) {
        return new AuthService.SessionMeta(
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT),
                truncate(clientIp(request), MAX_IP));
    }

    private static String clientIp(HttpServletRequest request) {
        return truncate(com.abavava.chat.http.Httpx.clientIp(request), MAX_IP);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
