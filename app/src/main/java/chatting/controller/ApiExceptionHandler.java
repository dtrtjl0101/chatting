package chatting.controller;

import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** REST API 예외를 브라우저가 일관되게 읽을 수 있는 JSON 응답으로 바꾼다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** ResponseStatusException의 상태 코드와 reason 문구를 JSON message로 반환한다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason() == null ? "요청을 처리하지 못했습니다." : exception.getReason();
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
