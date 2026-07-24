package com.aegis.merged.assistant;

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

/**
 * Code-enforced scope boundary. A prompt is a request the model can ignore (qwen still
 * wrote Python when only told not to); this gate is enforcement — an out-of-domain
 * message is short-circuited BEFORE the main assistant call, with a canned redirect.
 *
 * The model is used as a cheap classifier (its strength), not asked to police itself.
 * Crucially it classifies WITH the recent conversation, so a context-dependent follow-up
 * ("the 3rd one", "yes") is judged as a continuation of the in-scope thread rather than
 * misread as off-domain noise. (Earlier hard-coded ordinal/keyword lists were brittle;
 * letting the LLM decide with history is both simpler and more robust.)
 * Greetings fast-path to in-scope so trivial turns pay no latency.
 */
@Component
public class ScopeGate {

    private static final Logger log = LoggerFactory.getLogger(ScopeGate.class);

    public static final String REDIRECT =
            "I can only help with your banking — your accounts, cards, transactions, "
            + "disputes, and our policies. Is there something in that area I can help with?";

    // Pleasantries and meta-questions are in-scope by definition; skip the model call.
    // Matched AFTER canonicalization (u→you, ur→your, trailing punctuation stripped),
    // so informal spellings like "what can u do?" resolve here instead of reaching the
    // classifier (which over-blocks borderline meta-questions).
    private static final Set<String> FASTPATH_IN = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "ok", "okay", "bye",
            "good morning", "good afternoon", "good evening", "help",
            "who are you", "what are you", "what can you do", "what do you do",
            "what can you help with", "what are your capabilities", "how can you help",
            "what is this", "whats this", "what is achu", "what is finbot",
            "what is achu finbot", "whats achu finbot");

    // Entity/domain fast-path: a message naming a banking entity id or an unambiguous
    // banking term is in-scope by construction — skip the ~0.8s classifier round-trip.
    // (Injection/PII concerns are handled by their own gates; this only decides TOPIC.)
    private static final java.util.regex.Pattern DOMAIN_FASTPATH = java.util.regex.Pattern.compile(
            "\\b(acc|txn|case|apr|crd)-\\d+\\b"
            + "|\\b(balance|transactions?|disputes?|chargebacks?|representment|kyc|edd"
            + "|provisional credit|dispute case|refunds?|account|merchant"
            + "|cards?|overdrafts?|wire|transfers?|remittance|statements?|cheque|atm"
            + "|interest rate|deposits?|withdrawals?|standing order|direct debit)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String CLASSIFIER_PROMPT = """
            You are a scope classifier for a bank's customer-service assistant.
            IN scope: the customer's accounts, balances, transactions, disputes,
            chargebacks, cards, payments, transfers, loans, mortgages, fees, interest
            rates, overdrafts, foreign exchange, online banking, complaints, this bank's
            policies, and greetings or questions about the assistant itself.
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

    // Scope-verdict cache: for a STANDALONE message (no prior turn) the verdict is a stable
    // property of the text, so we memoize it and skip the ~250ms classifier on repeats.
    // Follow-ups depend on context, so we never cache those.
    private record CachedVerdict(boolean inScope, Instant expiresAt) {
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }
    private static final Duration VERDICT_TTL = Duration.ofHours(1);
    private static final int MAX_VERDICTS = 5_000;
    private final Map<String, CachedVerdict> verdicts = new ConcurrentHashMap<>();

    public ScopeGate(ChatClient.Builder builder, ChatMemory chatMemory,
                     @Value("${aegis.scope-gate.classifier-model:}") String classifierModel,
                     @Value("${aegis.llm.think:}") String think) {
        // A bare client: no tools, memory, or guardrail advisors — pure classification.
        // temperature 0 → deterministic (same question always gets the same verdict, fixing
        // the flaky "legit question occasionally blocked" case); maxTokens 4 → it emits just
        // IN_SCOPE/OUT_OF_SCOPE and stops, so the classifier round-trip is as cheap as possible.
        //
        // Two Ollama-specific knobs, both optional so other providers see portable options:
        //  - aegis.llm.think=false lets the classifier run on a thinking model (qwen3.5):
        //    without it, reasoning eats the 4-token budget → EMPTY verdict → gate fails open.
        //    Sharing the assistant's model also keeps ONE model resident (no eviction thrash).
        //  - classifier-model optionally pins a different (e.g. smaller) model instead.
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

    /**
     * True if the message belongs to the assistant's domain.
     * @param conversationKey the ChatMemory conversation id, so a follow-up is judged in context.
     */
    public boolean inScope(String message, String conversationKey) {
        if (message == null || message.isBlank()) return true;   // let the main path handle
        String norm = message.trim().toLowerCase().replaceAll("[.!?]+$", "");
        // Canonicalize common chat shorthand so "what can u do?" matches "what can you do".
        String canon = norm.replaceAll("\\bu\\b", "you").replaceAll("\\bur\\b", "your")
                           .replaceAll("\\s+", " ");
        if (FASTPATH_IN.contains(norm) || FASTPATH_IN.contains(canon)) return true;
        if (DOMAIN_FASTPATH.matcher(canon).find()) return true;   // clearly banking — no model call

        String context = recentContext(conversationKey);
        boolean standalone = context.isBlank();

        // Verdict cache only for standalone messages (follow-ups depend on context).
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
            // Fail OPEN: a classifier hiccup must not block legitimate banking work.
            log.warn("scope.gate.error fail-open: {}", e.getMessage());
            return true;
        }
    }

    /** The last assistant + user turn from memory, as plain text, to ground a follow-up. */
    private String recentContext(String conversationKey) {
        if (conversationKey == null) return "";
        try {
            List<Message> messages = chatMemory.get(conversationKey);
            if (messages == null || messages.isEmpty()) return "";
            // Take the last up-to-2 messages (usually the prior assistant reply + user turn).
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, messages.size() - 2);
            for (int i = from; i < messages.size(); i++) {
                Message m = messages.get(i);
                String who = m.getMessageType() == MessageType.ASSISTANT ? "Assistant" : "User";
                sb.append(who).append(": ").append(m.getText()).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";   // no context is fine — falls back to standalone classification
        }
    }

    private void putVerdict(String key, boolean inScope) {
        if (verdicts.size() >= MAX_VERDICTS) verdicts.clear();   // simple bound
        verdicts.put(key, new CachedVerdict(inScope, Instant.now().plus(VERDICT_TTL)));
    }
}
