package com.raph.gatewayapi.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.raph.gatewayapi.util.JwtUtil;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final WebClient webClient;
    private final JwtUtil jwtUtil;

    public AuthController(
            WebClient.Builder webClientBuilder,
            JwtUtil jwtUtil,
            @Value("${services.user.base-url}") String userBaseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(userBaseUrl).build();
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> credentials) {
        return webClient.post()
                .uri("/api/internal/user/login")
                .bodyValue(credentials)
                .exchangeToMono(response -> response
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .defaultIfEmpty(new HashMap<>())
                        .map(body -> buildLoginResponse(response.statusCode(), body))
                );
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody Map<String, Object> user) {
        return webClient.post()
                .uri("/api/internal/user/register")
                .bodyValue(user)
                .exchangeToMono(response -> response
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .defaultIfEmpty(new HashMap<>())
                        .map(body -> ResponseEntity.status(response.statusCode()).body(body))
                );
    }

    private ResponseEntity<Map<String, Object>> buildLoginResponse(HttpStatusCode status, Map<String, Object> body) {
        if (!status.is2xxSuccessful()) {
            return ResponseEntity.status(status).body(body);
        }

        Object idValue = body.get("id");
        if (idValue == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "用户服务返回数据缺少 id");
            return ResponseEntity.internalServerError().body(error);
        }

        long userId = Long.parseLong(String.valueOf(idValue));
        String token = jwtUtil.generateToken(userId);

        Map<String, Object> result = new HashMap<>(body);
        result.put("token", token);
        return ResponseEntity.ok(result);
    }
}
