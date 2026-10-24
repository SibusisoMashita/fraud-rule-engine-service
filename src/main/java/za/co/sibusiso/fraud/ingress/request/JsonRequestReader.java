package za.co.sibusiso.fraud.ingress.request;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Shared HTTP JSON bounds and exact decimal parsing, independent of each request schema. */
public final class JsonRequestReader {
    private final ServiceLimits.Api limits;
    private final JsonMapper json;

    public JsonRequestReader(ServiceLimits.Api limits) {
        this.limits = limits;
        json =
                JsonMapper.builder(
                                JsonFactory.builder()
                                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                        .streamReadConstraints(
                                                StreamReadConstraints.builder()
                                                        .maxNestingDepth(limits.maxJsonDepth())
                                                        .maxNumberLength(
                                                                limits.maxJsonNumberLength())
                                                        .maxStringLength(limits.maxBodyBytes())
                                                        .maxNameLength(limits.maxBodyBytes())
                                                        .maxDocumentLength(limits.maxBodyBytes())
                                                        .build())
                                        .build())
                        .enable(
                                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .build();
    }

    public JsonNode read(InputStream input, long contentLength, String contentType, String encoding)
            throws IOException {
        try {
            MediaType type = MediaType.parseMediaType(contentType == null ? "" : contentType);
            if (!type.getType().equals(MediaType.APPLICATION_JSON.getType())
                    || !type.getSubtype().equals(MediaType.APPLICATION_JSON.getSubtype())
                    || (type.getCharset() != null
                            && !type.getCharset().equals(StandardCharsets.UTF_8))
                    || (encoding != null && !encoding.equalsIgnoreCase("identity")))
                throw new IllegalArgumentException("Unsupported JSON media type");
        } catch (IllegalArgumentException invalid) {
            throw new ApiFailure(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (contentLength > limits.maxBodyBytes())
            throw new ApiFailure(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.BODY_TOO_LARGE);
        byte[] bytes = input.readNBytes(limits.maxBodyBytes() + 1);
        if (bytes.length > limits.maxBodyBytes())
            throw new ApiFailure(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.BODY_TOO_LARGE);
        try {
            String text =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return json.readTree(text);
        } catch (RuntimeException | CharacterCodingException malformed) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_JSON);
        }
    }
}
