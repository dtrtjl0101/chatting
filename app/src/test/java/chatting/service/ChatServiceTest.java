package chatting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import chatting.repository.ConversationRepository;
import chatting.repository.MessageRepository;
import chatting.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
    @Mock UserRepository users;
    @Mock ConversationRepository conversations;
    @Mock MessageRepository messages;
    @Mock FileStorageService files;

    private ChatService chats;
    private UUID conversationId;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        chats = new ChatService(users, conversations, messages, files);
        conversationId = UUID.randomUUID();
        senderId = UUID.randomUUID();
    }

    @Test
    void rejectsMessageFromNonMemberBeforeSaving() {
        when(conversations.isMember(conversationId, senderId)).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> chats.saveMessage(conversationId, senderId, "hello"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(messages, never()).insertAndFind(any(), any(), any(), any());
    }

    @Test
    void rejectsMessageWhenDirectConversationIsClosed() {
        when(conversations.isMember(conversationId, senderId)).thenReturn(true);
        when(conversations.isClosedForUpdate(conversationId)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> chats.saveMessage(conversationId, senderId, "hello"));

        assertEquals(HttpStatus.GONE, error.getStatusCode());
        verify(messages, never()).insertAndFind(any(), any(), any(), any());
    }

    @Test
    void ignoresBlankMessage() {
        when(conversations.isMember(conversationId, senderId)).thenReturn(true);
        when(conversations.isClosedForUpdate(conversationId)).thenReturn(false);

        assertNull(chats.saveMessage(conversationId, senderId, "   "));

        verify(messages, never()).insertAndFind(any(), any(), any(), any());
    }
}
