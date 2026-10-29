package chatting.controller;

import java.util.UUID;
import chatting.service.ConversationAssistantService;
import chatting.service.ConversationAssistantService.AssistantResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

/** 로그인한 대화방 참여자에게 AI 요약 및 대화 내용 질문 API를 제공한다. */
@RestController
@RequestMapping("/api/conversations/{conversationId}/assistant")
public class ConversationAssistantController {
    private final ConversationAssistantService assistant;

    public ConversationAssistantController(ConversationAssistantService assistant) {
        this.assistant = assistant;
    }

    /** 현재 방의 최근 메시지를 요약한다. */
    @PostMapping("/summary")
    public AssistantResponse summarize(@SessionAttribute("appUserId") String id,
                                       @PathVariable UUID conversationId) {
        return assistant.summarize(conversationId, UUID.fromString(id));
    }

    /** 현재 방의 관련 메시지를 찾아 질문 답변과 근거 메시지를 반환한다. */
    @PostMapping("/ask")
    public AssistantResponse ask(@SessionAttribute("appUserId") String id,
                                 @PathVariable UUID conversationId,
                                 @RequestBody AskRequest request) {
        return assistant.ask(conversationId, UUID.fromString(id), request.question());
    }

    public record AskRequest(String question) {}
}
