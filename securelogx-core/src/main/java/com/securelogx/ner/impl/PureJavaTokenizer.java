package com.securelogx.ner.impl;

import com.securelogx.ner.TokenizedInput;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java tokenizer for bert-base-cased WordPiece.
 *
 * The implementation preserves character offsets for every WordPiece so the
 * Java runtime can reproduce the Python/Hugging Face entity spans.
 */
public class PureJavaTokenizer {

    private static final int DEFAULT_MAX_SEQUENCE_LENGTH = 384;
    private static final int MAX_INPUT_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int clsTokenId;
    private final int sepTokenId;
    private final int unkTokenId;
    private final int maxSequenceLength;

    public PureJavaTokenizer(String tokenizerJsonPath) throws IOException {
        this(tokenizerJsonPath, DEFAULT_MAX_SEQUENCE_LENGTH);
    }

    public PureJavaTokenizer(String tokenizerJsonPath, int maxSequenceLength) throws IOException {
        if (maxSequenceLength < 2) {
            throw new IllegalArgumentException("maxSequenceLength must be at least 2");
        }
        this.vocab = loadVocab(tokenizerJsonPath);
        this.clsTokenId = vocab.getOrDefault("[CLS]", 101);
        this.sepTokenId = vocab.getOrDefault("[SEP]", 102);
        this.unkTokenId = vocab.getOrDefault("[UNK]", 100);
        this.maxSequenceLength = maxSequenceLength;
    }

    private Map<String, Integer> loadVocab(String tokenizerJsonPath) throws IOException {
        String content = Files.readString(Path.of(tokenizerJsonPath));
        JSONObject json = new JSONObject(content);
        JSONObject vocabJson = json.getJSONObject("model").getJSONObject("vocab");

        Map<String, Integer> result = new HashMap<>();
        for (String key : vocabJson.keySet()) {
            result.put(key, vocabJson.getInt(key));
        }
        return result;
    }

    public TokenizedInput encode(String text) {
        List<Integer> tokenIds = new ArrayList<>();
        List<int[]> offsets = new ArrayList<>();

        tokenIds.add(clsTokenId);
        offsets.add(new int[]{-1, -1});

        int contentLimit = maxSequenceLength - 2;
        outer:
        for (BasicToken token : preTokenize(text)) {
            for (WordPiece piece : wordpieceTokenize(token)) {
                if (tokenIds.size() - 1 >= contentLimit) {
                    break outer;
                }
                tokenIds.add(vocab.getOrDefault(piece.text(), unkTokenId));
                offsets.add(new int[]{piece.start(), piece.end()});
            }
        }

        tokenIds.add(sepTokenId);
        offsets.add(new int[]{-1, -1});

        int[] inputIds = tokenIds.stream().mapToInt(Integer::intValue).toArray();
        int[] attentionMask = new int[inputIds.length];
        Arrays.fill(attentionMask, 1);

        return new TokenizedInput(inputIds, attentionMask, offsets);
    }

    private List<BasicToken> preTokenize(String text) {
        List<BasicToken> tokens = new ArrayList<>();
        int index = 0;

        while (index < text.length()) {
            char c = text.charAt(index);

            if (isWhitespace(c) || isControl(c)) {
                index++;
                continue;
            }

            if (isChineseCharacter(c) || isPunctuation(c)) {
                tokens.add(new BasicToken(String.valueOf(c), index, index + 1));
                index++;
                continue;
            }

            int start = index;
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                c = text.charAt(index);
                if (isWhitespace(c) || isControl(c) || isChineseCharacter(c) || isPunctuation(c)) {
                    break;
                }
                value.append(c);
                index++;
            }
            if (!value.isEmpty()) {
                tokens.add(new BasicToken(value.toString(), start, index));
            }
        }

        return tokens;
    }

    private List<WordPiece> wordpieceTokenize(BasicToken token) {
        String word = token.text();
        List<WordPiece> pieces = new ArrayList<>();

        if (word.length() > MAX_INPUT_CHARS_PER_WORD) {
            pieces.add(new WordPiece("[UNK]", token.start(), token.end()));
            return pieces;
        }

        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String matched = null;
            int matchedEnd = -1;

            while (start < end) {
                String candidate = word.substring(start, end);
                if (start > 0) {
                    candidate = "##" + candidate;
                }
                if (vocab.containsKey(candidate)) {
                    matched = candidate;
                    matchedEnd = end;
                    break;
                }
                end--;
            }

            if (matched == null) {
                pieces.clear();
                pieces.add(new WordPiece("[UNK]", token.start(), token.end()));
                return pieces;
            }

            pieces.add(
                    new WordPiece(
                            matched,
                            token.start() + start,
                            token.start() + matchedEnd
                    )
            );
            start = matchedEnd;
        }

        return pieces;
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || c == '\u00A0';
    }

    private static boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private static boolean isPunctuation(char c) {
        int cp = c;
        if ((cp >= 33 && cp <= 47)
                || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96)
                || (cp >= 123 && cp <= 126)) {
            return true;
        }

        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isChineseCharacter(char c) {
        int cp = c;
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF);
    }

    private record BasicToken(String text, int start, int end) {
    }

    private record WordPiece(String text, int start, int end) {
    }
}
