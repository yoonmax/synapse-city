package com.synapsecity.websocket.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON reader for command payloads, with no library dependencies so it runs in the
 * browser build too. Objects become {@link Map}, arrays {@link List}, and numbers {@link Integer},
 * {@link Long} or {@link Double} by their written form. Throws {@link CommandException} on bad input.
 */
final class MiniJson {

    private static final int MAX_DEPTH = 16;

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw malformed();
        }
        return v;
    }

    private static CommandException malformed() {
        return new CommandException("Malformed JSON");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH || i >= s.length()) {
            throw malformed();
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw malformed();
        }
        i += word.length();
        return value;
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw malformed();
            }
            String key = string();
            ws();
            if (peek() != ':') {
                throw malformed();
            }
            i++;
            ws();
            map.put(key, value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw malformed();
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw malformed();
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw malformed();
        }
        return s.charAt(i);
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw malformed();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw malformed();
                    }
                    int code = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(s.charAt(i + k), 16);
                        if (d < 0) {
                            throw malformed();
                        }
                        code = code * 16 + d;
                    }
                    out.append((char) code);
                    i += 4;
                }
                default -> throw malformed();
            }
        }
    }

    private Object number() {
        int start = i;
        boolean fractional = false;
        if (peek() == '-') {
            i++;
        }
        int intStart = i;
        digits();
        if (s.charAt(intStart) == '0' && i - intStart > 1) {
            throw malformed();
        }
        if (i < s.length() && s.charAt(i) == '.') {
            fractional = true;
            i++;
            digits();
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            fractional = true;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            digits();
        }
        String text = s.substring(start, i);
        if (fractional) {
            return Double.parseDouble(text);
        }
        // More than 18 digits cannot be a valid id or count; keep it as a double so range checks reject it.
        if (text.length() > 18) {
            return Double.parseDouble(text);
        }
        long v = Long.parseLong(text);
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            return (int) v;
        }
        return v;
    }

    private void digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            throw malformed();
        }
    }
}
