package chatting;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration//스프링 설정 클래스로 지정하여 웹소켓 관련 설정을 정의
@EnableWebSocket//웹소켓을 활성화하고, WebSocketConfigurer를 구현하여 웹소켓 핸들러를 등록하는 설정 클래스
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;
    private final String[] allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler handler,
                           @Value("${app.websocket.allowed-origins:http://localhost:*,https://chatting.ljbhomeserver.date}") String origins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")//websocket 연결을 처리할 핸들러를 등록하고, "/ws" 경로로 들어오는 요청을 처리하도록 설정
                .addInterceptors(new HttpSessionHandshakeInterceptor())//웹소켓 핸드셰이크 과정에서 HTTP 세션 정보를 전달하기 위해 HttpSessionHandshakeInterceptor를 추가
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
