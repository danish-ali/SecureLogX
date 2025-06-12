package com.securelogx.ner.impl;

import com.securelogx.ner.TokenizedInput;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Pure Java Tokenizer compatible with BERT-base-cased WordPiece tokenizer.
 * Updated to include smarter pre-tokenization (punctuation-aware).
 */
public class PureJavaTokenizer {

    private final Map<String, Integer> vocab;
    private final int clsTokenId;
    private final int sepTokenId;
    private final int unkTokenId;
    private final boolean doLowerCase = false; // because it's bert-base-cased

    public PureJavaTokenizer(String tokenizerJsonPath) throws IOException {
        this.vocab = loadVocab(tokenizerJsonPath);
        this.clsTokenId = vocab.getOrDefault("[CLS]", 101);
        this.sepTokenId = vocab.getOrDefault("[SEP]", 102);
        this.unkTokenId = vocab.getOrDefault("[UNK]", 100);
    }

    private Map<String, Integer> loadVocab(String tokenizerJsonPath) throws IOException {
        String content = Files.readString(Path.of(tokenizerJsonPath));
        JSONObject json = new JSONObject(content);
        JSONObject vocabJson = json.getJSONObject("model").getJSONObject("vocab");

        Map<String, Integer> vocab = new HashMap<>();
        for (String key : vocabJson.keySet()) {
            vocab.put(key, vocabJson.getInt(key));
        }
        return vocab;
    }

    public TokenizedInput encode(String text) {
        if (doLowerCase) {
            text = text.toLowerCase();
        }

        List<Integer> tokenIds = new ArrayList<>();
        List<int[]> offsets = new ArrayList<>();

        tokenIds.add(clsTokenId);
        offsets.add(new int[]{-1, -1}); // [CLS]

        List<String> words = preTokenize(text);

        int cursor = 0;
        for (String word : words) {
            int start = text.indexOf(word, cursor);
            int end = start + word.length();
            cursor = end;

            List<String> wordPieces = wordpieceTokenize(word);
            for (String piece : wordPieces) {
                int tokenId = vocab.getOrDefault(piece, unkTokenId);
                tokenIds.add(tokenId);
                if (start >= 0 && end > start) {
                    offsets.add(new int[]{start, end});
                } else {
                    offsets.add(new int[]{-1, -1});
                }
            }
        }

        tokenIds.add(sepTokenId);
        offsets.add(new int[]{-1, -1}); // [SEP]

        int[] inputIds = tokenIds.stream().mapToInt(i -> i).toArray();
        int[] attentionMask = new int[inputIds.length];
        Arrays.fill(attentionMask, 1);

        return new TokenizedInput(inputIds, attentionMask, offsets);
    }

    private List<String> preTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            StringBuilder current = new StringBuilder();
            for (char c : token.toCharArray()) {
                if (Character.isLetterOrDigit(c)) {
                    current.append(c);
                } else {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                }
            }
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
        }
        return tokens;
    }

    private List<String> wordpieceTokenize(String word) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String sub = null;

            while (start < end) {
                String candidate = word.substring(start, end);
                if (start > 0) {
                    candidate = "##" + candidate;
                }
                if (vocab.containsKey(candidate)) {
                    sub = candidate;
                    break;
                }
                end--;
            }

            if (sub == null) {
                tokens.add("[UNK]");
                break;
            }

            tokens.add(sub);
            start = end;
        }
        return tokens;
    }
}
