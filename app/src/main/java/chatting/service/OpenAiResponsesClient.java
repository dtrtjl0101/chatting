package chatting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** OpenAI Responses API에 서버 측으로 요청을 보내고 결과 텍스트만 추출한다. */
@Component
public class OpenAiResponsesClient {
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public OpenAiResponsesClient(@Value("${app.ai.openai.api-key:}") String apiKey,
                                 @Value("${app.ai.openai.model:}") String model) {
        this.client = RestClient.builder().baseUrl("https://api.openai.com/v1").build();
        this.apiKey = apiKey;
        this.model = model;
    }

    /** 지시문과 제한된 대화 내용을 전송하고 모델의 출력 텍스트를 반환한다. */
    public String generate(String instructions, String input, int maxOutputTokens) {
        if (apiKey.isBlank() || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 기능 설정이 필요합니다. 서버에 OPENAI_API_KEY와 OPENAI_MODEL을 설정해 주세요.");
        }
        Map<String, Object> payload = Map.of(
                "model", model,
                "instructions", instructions,
                "input", input,
                "max_output_tokens", maxOutputTokens,
                "store", false);
        try {
            Map<?, ?> response = client.post().uri("/responses")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(payload).retrieve().body(Map.class);
            String text = extractOutputText(response);
            if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 응답에서 결과를 읽지 못했습니다.");
            return text;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI 서비스 요청에 실패했습니다. 서버 설정과 제공자 상태를 확인해 주세요.");
        }
    }

    private String extractOutputText(Map<?, ?> response) {
        if (response == null || !(response.get("output") instanceof List<?> output)) return "";
        List<String> texts = new ArrayList<>();
        for (Object item : output) {
            if (!(item instanceof Map<?, ?> message) || !(message.get("content") instanceof List<?> content)) continue;
            for (Object part : content) {
                if (part instanceof Map<?, ?> value && "output_text".equals(value.get("type"))
                        && value.get("text") instanceof String text && !text.isBlank()) texts.add(text);
            }
        }
        return String.join("\n", texts).strip();
    }
}
