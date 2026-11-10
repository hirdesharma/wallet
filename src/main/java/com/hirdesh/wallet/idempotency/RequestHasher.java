package com.hirdesh.wallet.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Fingerprint of "what was asked": operation + its parameters in a fixed order.
 * Same key + different fingerprint ⇒ IDEMPOTENCY_KEY_REUSED.
 *
 * We hash the parsed parameters, not the raw JSON, so whitespace or field order in
 * the client's body doesn't matter.
 */
@Component
public class RequestHasher {

    public String hash(String operation, Object... params) {
        String canonical = operation + "\n" + Arrays.stream(params)
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available on the JVM
        }
    }
}
