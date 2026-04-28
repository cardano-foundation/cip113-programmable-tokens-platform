package org.cardanofoundation.cip113.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for parsing and reconstructing CESR (Composable Event Streaming Representation) streams.
 *
 * Replaces {@code org.cardanofoundation.signify.cesr.util.CESRStreamUtil} which was removed when
 * the signify-java library moved to a typed credential model. CIP-170 still requires the raw
 * vcp+iss+acdc CESR chain, so we keep this parser locally.
 */
public final class CESRStreamUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private CESRStreamUtil() {}

    /**
     * Parse a CESR string of the form {@code {json_event}{attachment}{json_event}{attachment}...}
     * into a list of maps each containing {@code "event"} (parsed JSON map) and {@code "atc"}
     * (the trailing attachment string).
     */
    public static List<Map<String, Object>> parseCESRData(String cesrData) {
        List<Map<String, Object>> result = new ArrayList<>();

        int index = 0;
        while (index < cesrData.length()) {
            if (cesrData.charAt(index) != '{') {
                index++;
                continue;
            }

            int braceCount = 0;
            int jsonStart = index;
            int jsonEnd = index;

            for (int i = index; i < cesrData.length(); i++) {
                char ch = cesrData.charAt(i);
                if (ch == '{') {
                    braceCount++;
                } else if (ch == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        jsonEnd = i + 1;
                        break;
                    }
                }
            }

            String jsonEvent = cesrData.substring(jsonStart, jsonEnd);

            int attachmentEnd = cesrData.length();
            for (int i = jsonEnd; i < cesrData.length(); i++) {
                if (cesrData.charAt(i) == '{') {
                    attachmentEnd = i;
                    break;
                }
            }
            String attachment = cesrData.substring(jsonEnd, attachmentEnd);

            try {
                Map<String, Object> eventObj = MAPPER.readValue(jsonEvent, MAP_TYPE);
                Map<String, Object> eventMap = new LinkedHashMap<>();
                eventMap.put("event", eventObj);
                eventMap.put("atc", attachment);
                result.add(eventMap);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse CESR event: " + jsonEvent, e);
            }

            index = attachmentEnd;
        }

        return result;
    }

    /**
     * Build a CESR stream from parallel lists of events and their attachments.
     */
    public static String makeCESRStream(List<Map<String, Object>> events, List<String> attachments) {
        if (events.size() != attachments.size()) {
            throw new IllegalArgumentException(
                    "Events and attachments lists must have the same size. " +
                            "Events: " + events.size() + ", Attachments: " + attachments.size());
        }
        StringBuilder cesrStream = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            String attachment = attachments.get(i);
            try {
                cesrStream.append(MAPPER.writeValueAsString(event));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize CESR event", e);
            }
            if (attachment != null) {
                cesrStream.append(attachment);
            }
        }
        return cesrStream.toString();
    }
}
