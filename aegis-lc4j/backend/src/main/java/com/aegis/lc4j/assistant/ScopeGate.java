package com.aegis.lc4j.assistant;

import com.aegis.lc4j.memory.PgChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class ScopeGate {

    private static final Logger log = LoggerFactory.getLogger(ScopeGate.class);

    public static final String REDIRECT =
            "I can only help with your banking — your accounts, cards, transactions, "
            + "disputes, and our policies. Is there something in that area I can help with?";

    private static final Set<String> FASTPATH_IN = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "ok", "okay", "bye",
            "good morning", "good afternoon", "good evening", "help",
            "who are you", "what are you", "what can you do", "what do you do",
            "what can you help with", "what are your capabilities", "how can you help",
            "what is this", "whats this", "what is aegis", "what is finbot");

    private static final Pattern DOMAIN_FASTPATH = Pattern.compile(
            "\\b(acc|txn|case|apr|crd)-\\d+\\b"
            + "|\\b(balance|transactions?|disputes?|chargebacks?|representment|kyc|edd"
            + "|provisional credit|dispute case|refunds?|account|merchant"
            + "|cards?|overdrafts?|wire|transfers?|remittance|statements?|cheque|atm"
            + "|interest rate|deposits?|withdrawals?|standing order|direct debit)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String CLASSIFIER_PROMPT = """
            You are a scope classifier for a bank's customer-service assistant.
            IN scope: the customer's accounts, balances, transactions, disputes,
            chargebacks, cards, payments, transfers, loans, mortgages, fees, interest
            rates, overdrafts, foreign exchange, online banking, complaints, this bank's
            policies, and greetings or questions about the assistant itself.
            OUT of scope: general knowledge, philosophy, coding or programming, math help,
            investment/trading/financial advice, or anything not about this bank.

            The new message may be a SHORT FOLLOW-UP ("the 3rd one", "yes", "that account")
            that only makes sense given the recent conversation shown to you — if the thread
            is about banking, such a follow-up is IN_SCOPE. Judge the new message in the
            context of the conversation, not in isolation.

            Bias toward IN_SCOPE: greetings, small talk, and ANY question about you or what
            you can do are IN_SCOPE. When you are unsure, answer IN_SCOPE. Only answer
            OUT_OF_SCOPE when the message is CLEARLY about an unrelated topic.

            Reply with EXACTLY one word: IN_SCOPE or OUT_OF_SCOPE. No other text.
            """;

    private record CachedVerdict(boolean inScope, Instant expiresAt) {
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }

    private static final Duration VERDICT_TTL = Duration.ofHours(1);
    private static final int MAX_VERDICTS = 5_000;
    private final Map<String, CachedVerdict> verdicts = new ConcurrentHashMap<>();

    private final ChatModel classifier;
    private final PgChatMemoryStore memoryStore;

    public ScopeGate(@Qualifier("classifierModel") ChatModel classifier,
                     PgChatMemoryStore memoryStore) {
        this.classifier = classifier;
        this.memoryStore = memoryStore;
    }

    public boolean inScope(String message, String memoryKey) {
        if (message == null || message.isBlank()) return true;   
        String norm = message.trim().toLowerCase().replaceAll("[.!?]+$", "");
        String canon = norm.replaceAll("\\bu\\b", "you").replaceAll("\\bur\\b", "your")
                           .replaceAll("\\s+", " ");
        if (FASTPATH_IN.contains(norm) || FASTPATH_IN.contains(canon)) return true;
        if (DOMAIN_FASTPATH.matcher(canon).find()) return true;   

        String context = recentContext(memoryKey);
        boolean standalone = context.isBlank();

        if (standalone) {
            CachedVerdict cached = verdicts.get(norm);
            if (cached != null && !cached.expired()) return cached.inScope();
        }

        try {
            String user = standalone ? message
                    : "Recent conversation:\n" + context + "\n\nNew user message: " + message;
            String verdict = classifier.chat(SystemMessage.from(CLASSIFIER_PROMPT),
                    UserMessage.from(user)).aiMessage().text();
            boolean out = verdict != null && verdict.toUpperCase().contains("OUT");
            if (out) log.info("scope.gate.blocked msg='{}' verdict='{}'", message, verdict);
            if (standalone) putVerdict(norm, !out);
            return !out;
        } catch (Exception e) {
            
            log.warn("scope.gate.error fail-open: {}", e.getMessage());
            return true;
        }
    }

    private String recentContext(String memoryKey) {
        if (memoryKey == null) return "";
        try {
            List<ChatMessage> messages = memoryStore.getMessages(memoryKey);
            if (messages.isEmpty()) return "";

            String lastUser = null, lastAssistant = null;
            for (int i = messages.size() - 1; i >= 0 && (lastUser == null || lastAssistant == null); i--) {
                ChatMessage m = messages.get(i);
                if (lastUser == null && m instanceof UserMessage um && um.hasSingleText()) {
                    lastUser = um.singleText();
                } else if (lastAssistant == null && m instanceof AiMessage am
                        && am.text() != null && !am.text().isBlank()) {
                    lastAssistant = am.text();
                }
            }
            StringBuilder sb = new StringBuilder();
            if (lastUser != null) sb.append("User: ").append(lastUser).append("\n");
            if (lastAssistant != null) sb.append("Assistant: ").append(lastAssistant).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            return "";   
        }
    }

    private void putVerdict(String key, boolean inScope) {
        if (verdicts.size() >= MAX_VERDICTS) verdicts.clear();   
        verdicts.put(key, new CachedVerdict(inScope, Instant.now().plus(VERDICT_TTL)));
    }
}
