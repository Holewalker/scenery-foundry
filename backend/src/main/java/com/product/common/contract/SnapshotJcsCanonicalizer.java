package com.product.common.contract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.erdtman.jcs.JsonCanonicalizer;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;

/** Strict raw-UTF-8 boundary for scenery-foundry.snapshot-jcs/v1. */
public final class SnapshotJcsCanonicalizer {
    public static final String CONTRACT = "scenery-foundry.snapshot-jcs/v1";

    private final tools.jackson.core.json.JsonFactory jsonFactory = tools.jackson.core.json.JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();

    public CanonicalResult canonicalize(byte[] rawUtf8) {
        String json = decodeUtf8(rawUtf8);
        validate(json);
        try {
            byte[] canonical = new JsonCanonicalizer(json.getBytes(StandardCharsets.UTF_8)).getEncodedUTF8();
            return new CanonicalResult(CONTRACT, canonical, sha256(canonical));
        } catch (IOException exception) {
            throw new CanonicalizationException("CANONICALIZATION_FAILED", exception);
        }
    }

    private String decodeUtf8(byte[] rawUtf8) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawUtf8));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new CanonicalizationException("INVALID_UTF8", exception);
        }
    }

    private void validate(String json) {
        try (JsonParser parser = jsonFactory.createParser(json)) {
            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    double value = Double.parseDouble(parser.getText());
                    if (!Double.isFinite(value)) {
                        throw new CanonicalizationException("NUMBER_OUT_OF_RANGE");
                    }
                }
                if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
                    validateUnicode(parser.getText());
                }
            }
        } catch (CanonicalizationException exception) {
            throw exception;
        } catch (JacksonException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            throw new CanonicalizationException(
                message.contains("duplicate") || message.contains("Duplicate") ? "DUPLICATE_NAME" : "INVALID_JSON",
                exception);
        } catch (NumberFormatException exception) {
            throw new CanonicalizationException("NUMBER_OUT_OF_RANGE", exception);
        }
    }

    private void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new CanonicalizationException("INVALID_UNICODE");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new CanonicalizationException("INVALID_UNICODE");
            }
        }
    }

    private String sha256(byte[] canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public record CanonicalResult(String contract, byte[] canonicalBytes, String sha256) {
        public CanonicalResult {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    public static final class CanonicalizationException extends RuntimeException {
        private final String code;

        CanonicalizationException(String code) {
            this(code, null);
        }

        CanonicalizationException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
