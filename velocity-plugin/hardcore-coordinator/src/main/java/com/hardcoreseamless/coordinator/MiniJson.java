package com.hardcoreseamless.coordinator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal hand-rolled JSON parser - objects/strings/numbers/null only, no arrays (neither
 * state.json nor the death-event files need them). Shared by {@link StateJson} and the death
 * event reader in {@link DeathCoordinator}. See StateJson's javadoc for why this isn't a library
 * dependency.
 */
final class MiniJson {

    private MiniJson() {
    }

    static Map<String, Object> parseObject(String json) {
        Object parsed = new Parser(json).parseValue();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("JSON root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) root;
        return typed;
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Small recursive-descent parser for the subset of JSON we need: objects, strings, numbers, null. */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '"' -> parseString();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char next = s.charAt(pos++);
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
                }
            }
            return map;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Expected 'null' at position " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '-' || s.charAt(pos) == '.')) {
                pos++;
            }
            String numStr = s.substring(start, pos);
            if (numStr.contains(".")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return s.charAt(pos);
        }

        private void expect(char c) {
            skipWhitespace();
            if (s.charAt(pos) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }
}
