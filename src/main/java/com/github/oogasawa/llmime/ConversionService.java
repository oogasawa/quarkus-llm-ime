package com.github.oogasawa.llmime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Converts hiragana to kanji-kana mixed text using vLLM.
 */
@ApplicationScoped
public class ConversionService {

    private static final Logger LOG = Logger.getLogger(ConversionService.class.getName());

    @RestClient
    VllmClient vllmClient;

    @Inject
    MozcClient mozcClient;

    @ConfigProperty(name = "llm-ime.model")
    String model;

    @ConfigProperty(name = "llm-ime.max-tokens", defaultValue = "100")
    int maxTokens;

    @ConfigProperty(name = "llm-ime.temperature", defaultValue = "0.0")
    double temperature;

    private static final int HISTORY_SIZE = 20;
    private final Deque<ConversionEntry> conversionHistory = new ConcurrentLinkedDeque<>();

    public record ConversionEntry(String input, String output) {}

    /**
     * Record a conversion result chosen by the user.
     */
    public void recordConversion(String input, String output) {
        if (input == null || output == null || input.equals(output)) return;
        conversionHistory.addLast(new ConversionEntry(input, output));
        while (conversionHistory.size() > HISTORY_SIZE) {
            conversionHistory.pollFirst();
        }
    }

    /**
     * Get recent conversion history as a formatted string for prompt context.
     */
    private String historyContext() {
        if (conversionHistory.isEmpty()) return "";
        var sb = new StringBuilder("最近の変換履歴:\n");
        for (var entry : conversionHistory) {
            sb.append(entry.input()).append(" → ").append(entry.output()).append("\n");
        }
        return sb.toString();
    }

    // Characters that should never have spaces around them in Japanese text
    private static final String JP_NO_SPACE =
        "\u3000-\u303f"  // CJK punctuation (、。「」等)
        + "\u3040-\u309f" // Hiragana
        + "\u30a0-\u30ff" // Katakana
        + "\u4e00-\u9fff" // CJK Unified Ideographs (漢字)
        + "\uff00-\uffef"; // Fullwidth forms

    /**
     * Convert hiragana to kanji-kana mixed text.
     *
     * @param hiragana the hiragana input
     * @param context  preceding text for disambiguation (may be null or empty)
     * @return converted text
     */
    public String convert(String hiragana, String context) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String history = historyContext();
        String prompt;
        if (context != null && !context.isBlank()) {
            prompt = history + "前の文脈: " + context + "\n\n"
                + "以下を漢字に直してください。変換結果だけを1行で出力してください : " + hiragana;
        } else {
            prompt = history + "以下を漢字に直してください。変換結果だけを1行で出力してください : " + hiragana;
        }
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, maxTokens, temperature);

        try {
            var response = vllmClient.chatCompletions(request);
            if (response.choices() != null && !response.choices().isEmpty()) {
                String result = response.choices().get(0).message().content();
                return normalize(result);
            }
            return hiragana;
        } catch (Exception e) {
            LOG.warning("vLLM request failed: " + e.getMessage());
            return hiragana;
        }
    }

    /**
     * Convert hiragana to kanji-kana mixed text, returning multiple candidates.
     * Queries both Mozc (dictionary-based, fast) and vLLM (context-aware) in parallel,
     * then merges and deduplicates the results.
     */
    public List<String> convertMulti(String hiragana, String context, int n) {
        // Launch Mozc and LLM queries in parallel
        var mozcFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return mozcClient.getCandidates(hiragana);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Mozc candidate fetch failed", e);
                return List.<String>of();
            }
        });

        var llmFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return getLlmCandidates(hiragana, context, n);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "LLM candidate fetch failed", e);
                return List.<String>of();
            }
        });

        List<String> mozcCandidates;
        List<String> llmCandidates;
        try {
            mozcCandidates = mozcFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Mozc timed out: " + e.getMessage());
            mozcCandidates = List.of();
        }
        try {
            llmCandidates = llmFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("LLM timed out: " + e.getMessage());
            llmCandidates = List.of();
        }

        // Merge: Mozc first (reliable), then LLM (contextual), deduplicated
        var merged = new LinkedHashSet<String>();
        merged.addAll(mozcCandidates);
        merged.addAll(llmCandidates);
        // Always include raw hiragana as last fallback
        merged.add(hiragana);

        LOG.info("Candidates for '" + hiragana + "': mozc=" + mozcCandidates.size()
                + " llm=" + llmCandidates.size() + " merged=" + merged.size());

        return new ArrayList<>(merged);
    }

    /**
     * Get LLM-based candidates using prompt engineering.
     */
    private List<String> getLlmCandidates(String hiragana, String context, int n) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String history = historyContext();
        String contextPart = (context != null && !context.isBlank())
            ? "文脈:\n" + context + "\n\n" : "";
        String prompt = history + contextPart
            + "「" + hiragana + "」の漢字仮名混じり表現の候補を" + n + "個出してください。\n"
            + "1行に1候補、候補だけを出力してください。最も適切な候補を最初に出してください。";
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, maxTokens, temperature);

        var response = vllmClient.chatCompletions(request);
        if (response.choices() != null && !response.choices().isEmpty()) {
            String raw = response.choices().get(0).message().content();
            var seen = new LinkedHashSet<String>();
            if (raw != null) {
                for (String line : raw.split("\n")) {
                    String candidate = line.strip()
                        .replaceAll("^\\d+[.\\)\\]、．）】]\\s*", "");
                    candidate = SPACE_BETWEEN_JP.matcher(candidate).replaceAll("$1$2").strip();
                    if (!candidate.isEmpty()) {
                        seen.add(candidate);
                    }
                }
            }
            return new ArrayList<>(seen);
        }
        return List.of();
    }

    /**
     * Predict multiple completions for text continuation.
     * Context may contain [CURSOR] marker indicating where completion should happen.
     */
    public List<String> completeMulti(String context, String partial, int n) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String prompt;
        if (context != null && context.contains("[CURSOR]")) {
            // Rich context mode: file head + before cursor + [CURSOR] + after cursor
            prompt = "以下の文書の[CURSOR]の位置に入る続きの文章を書いてください。"
                + "[CURSOR]の位置に挿入する文章だけを出力してください。\n\n" + context;
        } else if (partial != null && !partial.isBlank()) {
            prompt = "以下の文章の続きを書いてください。続きだけを出力してください。\n\n"
                + context + partial;
        } else {
            prompt = "以下の文章の続きを書いてください。続きだけを出力してください。\n\n"
                + context;
        }
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, completionMaxTokens, 0.8, n);

        try {
            var response = vllmClient.chatCompletions(request);
            if (response.choices() != null && !response.choices().isEmpty()) {
                var seen = new java.util.LinkedHashSet<String>();
                for (var choice : response.choices()) {
                    String result = normalize(choice.message().content());
                    if (result != null && !result.isEmpty()) {
                        seen.add(result);
                    }
                }
                if (!seen.isEmpty()) {
                    return new ArrayList<>(seen);
                }
            }
        } catch (Exception e) {
            LOG.warning("vLLM multi-complete request failed: " + e.getMessage());
        }
        return List.of();
    }

    @ConfigProperty(name = "llm-ime.completion.max-tokens", defaultValue = "30")
    int completionMaxTokens;

    /**
     * Predict and complete text based on context and optional partial input.
     *
     * @param context preceding text (what the user has already written)
     * @param partial partial input being typed (may be null or empty)
     * @return predicted continuation
     */
    public String complete(String context, String partial) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String prompt;
        if (partial != null && !partial.isBlank()) {
            prompt = "以下の文章の続きを書いてください。続きだけを出力してください。\n\n"
                + context + partial;
        } else {
            prompt = "以下の文章の続きを書いてください。続きだけを出力してください。\n\n"
                + context;
        }
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, completionMaxTokens, temperature);

        try {
            var response = vllmClient.chatCompletions(request);
            if (response.choices() != null && !response.choices().isEmpty()) {
                String result = response.choices().get(0).message().content();
                return normalize(result);
            }
            return "";
        } catch (Exception e) {
            LOG.warning("vLLM completion request failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Segment hiragana into bunsetsu and convert each segment.
     * Returns a list of segments, each with reading (hiragana) and candidates.
     *
     * @param hiragana the full hiragana input
     * @param context  preceding text for disambiguation
     * @return list of segments
     */
    public List<Segment> segment(String hiragana, String context) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String contextPart = (context != null && !context.isBlank())
            ? "前の文脈: " + context + "\n\n" : "";
        String prompt = contextPart
            + "以下のひらがなを文節に区切り、それぞれ漢字に変換してください。\n"
            + "出力形式: 各文節を「読み|変換結果」の形式で、スラッシュ区切りで1行に出力してください。\n"
            + "例: たべものをかう → たべもの|食べ物/を|を/かう|買う\n\n"
            + hiragana;
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, maxTokens, temperature);

        try {
            var response = vllmClient.chatCompletions(request);
            if (response.choices() != null && !response.choices().isEmpty()) {
                String raw = response.choices().get(0).message().content();
                return parseSegments(normalize(raw));
            }
        } catch (Exception e) {
            LOG.warning("vLLM segment request failed: " + e.getMessage());
        }
        // Fallback: return whole input as single segment
        return List.of(new Segment(hiragana, List.of(hiragana)));
    }

    /**
     * Get alternative candidates for a single bunsetsu reading.
     *
     * @param reading  the hiragana reading of the segment
     * @param context  surrounding text for disambiguation
     * @return list of candidate conversions
     */
    public List<String> candidates(String reading, String context) {
        var messages = new ArrayList<ChatCompletionRequest.Message>();

        String contextPart = (context != null && !context.isBlank())
            ? "前の文脈: " + context + "\n\n" : "";
        String prompt = contextPart
            + "「" + reading + "」の漢字変換候補を最大5個、カンマ区切りで出力してください。"
            + "候補だけを出力してください。\n"
            + "例: きしゃ → 記者,汽車,帰社,貴社,騎射";
        messages.add(new ChatCompletionRequest.Message("user", prompt));

        var request = new ChatCompletionRequest(model, messages, maxTokens, temperature);

        try {
            var response = vllmClient.chatCompletions(request);
            if (response.choices() != null && !response.choices().isEmpty()) {
                String raw = normalize(response.choices().get(0).message().content());
                var result = new ArrayList<String>();
                for (String c : raw.split("[,、，]")) {
                    String trimmed = c.strip();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception e) {
            LOG.warning("vLLM candidates request failed: " + e.getMessage());
        }
        return List.of(reading);
    }

    /**
     * Parse LLM segment output like "たべもの|食べ物/を|を/かう|買う"
     */
    List<Segment> parseSegments(String raw) {
        var segments = new ArrayList<Segment>();
        for (String part : raw.split("/")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            int pipe = trimmed.indexOf('|');
            if (pipe >= 0) {
                String reading = trimmed.substring(0, pipe).strip();
                String converted = trimmed.substring(pipe + 1).strip();
                if (!reading.isEmpty()) {
                    segments.add(new Segment(reading, List.of(converted, reading)));
                }
            } else {
                segments.add(new Segment(trimmed, List.of(trimmed)));
            }
        }
        return segments.isEmpty() ? List.of(new Segment(raw, List.of(raw))) : segments;
    }

    public record Segment(String reading, List<String> candidates) {}

    // Regex: space between two Japanese characters
    private static final Pattern SPACE_BETWEEN_JP =
        Pattern.compile("([" + JP_NO_SPACE + "])\\s+([" + JP_NO_SPACE + "])");

    /**
     * Normalize LLM output:
     * - Take first line only
     * - Remove spurious spaces between Japanese characters
     * - Strip leading/trailing whitespace
     */
    String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // Take first line
        int newline = raw.indexOf('\n');
        String line = (newline >= 0) ? raw.substring(0, newline) : raw;

        // Remove spaces between Japanese characters (repeatedly for "A B C" -> "ABC")
        String prev;
        String result = line;
        do {
            prev = result;
            result = SPACE_BETWEEN_JP.matcher(prev).replaceAll("$1$2");
        } while (!result.equals(prev));

        return result.strip();
    }
}
