package com.aegis.ai.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
            "I can only help with this bank's operations — its policies, accounts, "
            + "transactions, and disputes. Is there something in that area I can help with?";

    private static final Set<String> FASTPATH_IN = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "ok", "okay", "bye",
            "good morning", "good afternoon", "good evening", "help",
            "who are you", "what are you", "what can you do", "what do you do",
            "what can you help with", "what are your capabilities", "how can you help",
            "what is this", "whats this", "what is achu", "what is finbot",
            "what is achu finbot", "whats achu finbot");

    private static final Pattern DOMAIN_FASTPATH = Pattern.compile(
            "\\b(acc|txn|case|apr|crd)-\\d+\\b"
            + "|\\b(balance|transactions?|disputes?|chargebacks?|representment|kyc|edd"
            + "|provisional credit|dispute case|refunds?|account|merchant"
            + "|cards?|overdrafts?|wire|transfers?|remittance|statements?|cheque|atm"
            + "|interest rate|deposits?|withdrawals?|standing order|direct debit)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String CLASSIFIER_PROMPT = """
            You are a scope classifier for a bank operations assistant used by bank staff.
            IN scope: this bank's policies, accounts, balances, transactions, disputes,
            chargebacks, KYC, cards, payments, transfers, loans, mortgages, fees, interest
            rates, overdrafts, foreign exchange, online banking, complaints, and greetings
            or questions about the assistant itself.
            OUT of scope: general knowledge, philosophy, coding or programming, math help,
            investment/trading/financial advice, or anything not about this bank's operations.

            The new message may be a SHORT FOLLOW-UP ("the 3rd one", "yes", "that account")
            that only makes sense given the recent conversation shown to you — if the thread
            is about bank operations, such a follow-up is IN_SCOPE. Judge the new message in
            the context of the conversation, not in isolation.

            Bias toward IN_SCOPE: greetings, small talk, and ANY question about you or what
            you can do are IN_SCOPE. When you are unsure, answer IN_SCOPE. Only answer
            OUT_OF_SCOPE when the message is CLEARLY about an unrelated topic — coding,
            general trivia, philosophy, math homework, or investment/trading advice.

            Reply with EXACTLY one word: IN_SCOPE or OUT_OF_SCOPE. No other text.
            """;

    private final ChatClient classifier;
    private final ChatMemory chatMemory;

    private record CachedVerdict(boolean inScope, Instant expiresAt) {
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }
    private static final Duration VERDICT_TTL = Duration.ofHours(1);
    private static final int MAX_VERDICTS = 5_000;
    private final Map<String, CachedVerdict> verdicts = new ConcurrentHashMap<>();

    public ScopeGate(ChatClient.Builder builder, ChatMemory chatMemory,
                     @Value("${aegis.scope-gate.classifier-model:}") String classifierModel,
                     @Value("${aegis.llm.think:}") String think) {

        ChatOptions options;
        if (think != null && !think.isBlank()) {
            var b = org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                    .temperature(0.0).numPredict(4)
                    .thinkOption(new org.springframework.ai.ollama.api.ThinkOption.ThinkBoolean(
                            Boolean.parseBoolean(think)));
            if (classifierModel != null && !classifierModel.isBlank()) b.model(classifierModel);
            options = b.build();
        } else {
            var b = ChatOptions.builder().temperature(0.0).maxTokens(4);
            if (classifierModel != null && !classifierModel.isBlank()) b.model(classifierModel);
            options = b.build();
        }
        this.classifier = builder
                .defaultSystem(CLASSIFIER_PROMPT)
                .defaultOptions(options)
                .build();
        this.chatMemory = chatMemory;
    }

    public boolean inScope(String message, String conversationKey) {
        if (message == null || message.isBlank()) return true;   
        String norm = message.trim().toLowerCase().replaceAll("[.!?]+$", "");
        
        String canon = norm.replaceAll("\\bu\\b", "you").replaceAll("\\bur\\b", "your")
                           .replaceAll("\\s+", " ");
        if (FASTPATH_IN.contains(norm) || FASTPATH_IN.contains(canon)) return true;
        if (DOMAIN_FASTPATH.matcher(canon).find()) return true;   

        String context = recentContext(conversationKey);
        boolean standalone = context.isBlank();

        if (standalone) {
            CachedVerdict cached = verdicts.get(norm);
            if (cached != null && !cached.expired()) return cached.inScope();
        }

        try {
            String user = standalone ? message
                    : "Recent conversation:\n" + context + "\n\nNew user message: " + message;
            String verdict = classifier.prompt().user(user).call().content();
            boolean out = verdict != null && verdict.toUpperCase().contains("OUT");
            if (out) log.info("scope.gate.blocked msg='{}' verdict='{}'", message, verdict);
            if (standalone) putVerdict(norm, !out);
            return !out;
        } catch (Exception e) {
            
            log.warn("scope.gate.error fail-open: {}", e.getMessage());
            return true;
        }
    }

    private String recentContext(String conversationKey) {
        if (conversationKey == null) return "";
        try {
            List<Message> messages = chatMemory.get(conversationKey);
            if (messages == null || messages.isEmpty()) return "";
            
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, messages.size() - 2);
            for (int i = from; i < messages.size(); i++) {
                Message m = messages.get(i);
                String who = m.getMessageType() == MessageType.ASSISTANT ? "Assistant" : "User";
                sb.append(who).append(": ").append(m.getText()).append("\n");
            }
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
