package com.hirdesh.wallet.support;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.jayway.jsonpath.JsonPath;

/** Thin HTTP client for tests. Never throws on 4xx/5xx — tests assert on status instead. */
public class ApiClient {

    public record Response(int status, String body, HttpHeaders headers) {

        public long num(String jsonPath) {
            return ((Number) JsonPath.read(body, jsonPath)).longValue();
        }

        public String str(String jsonPath) {
            return JsonPath.read(body, jsonPath);
        }

        public String code() {
            return str("$.code");
        }

        public boolean replayed() {
            return "true".equals(headers.getFirst("Idempotent-Replayed"));
        }
    }

    private final RestClient http;

    public ApiClient(int port) {
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1")
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    // ── convenience ──

    public long createWallet(String userId) {
        return post("/wallets", null, "{\"userId\":\"" + userId + "\"}").num("$.walletId");
    }

    public long balance(long walletId) {
        return get("/wallets/" + walletId).num("$.balance");
    }

    public Response topUp(long walletId, long amount) {
        return topUp(walletId, amount, newKey());
    }

    public Response topUp(long walletId, long amount, String key) {
        return post("/wallets/" + walletId + "/top-ups", key, "{\"amount\":" + amount + "}");
    }

    public Response withdraw(long walletId, long amount) {
        return post("/wallets/" + walletId + "/withdrawals", newKey(), "{\"amount\":" + amount + "}");
    }

    public Response transfer(long from, long to, long amount) {
        return transfer(from, to, amount, newKey());
    }

    public Response transfer(long from, long to, long amount, String key) {
        return post("/transfers", key,
                "{\"fromWalletId\":" + from + ",\"toWalletId\":" + to + ",\"amount\":" + amount + "}");
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ── raw ──

    public Response get(String path) {
        var entity = http.get().uri(path).retrieve().toEntity(String.class);
        return new Response(entity.getStatusCode().value(), entity.getBody(), entity.getHeaders());
    }

    public Response post(String path, String idempotencyKey, String jsonBody) {
        var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        var entity = spec.retrieve().toEntity(String.class);
        return new Response(entity.getStatusCode().value(), entity.getBody(), entity.getHeaders());
    }
}
