package io.github.jqssun.airplay.reporting;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A deliberately small curl importer. No shell, expansion, files or subprocesses. */
public final class CurlRequest {
    public final String method, url, body;
    public final Map<String, String> headers;
    private CurlRequest(String method, String url, Map<String, String> headers, String body) {
        this.method = method; this.url = url; this.body = body;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
    public static CurlRequest parse(String command) {
        if (command == null || command.length() > 65536) throw invalid();
        List<String> args = words(command);
        if (args.isEmpty() || !(args.get(0).equals("curl") || args.get(0).equals("/usr/bin/curl"))) throw invalid();
        String method = null, url = null, body = null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < args.size(); i++) {
            String option = args.get(i), value = null;
            if (option.startsWith("--") && option.contains("=")) {
                int at = option.indexOf('='); value = option.substring(at + 1); option = option.substring(0, at);
            } else if (option.length() > 2 && (option.startsWith("-X") || option.startsWith("-H") || option.startsWith("-d"))) {
                value = option.substring(2); option = option.substring(0, 2);
            }
            boolean valued = option.equals("-X") || option.equals("--request") || option.equals("-H") || option.equals("--header")
                || option.equals("-d") || option.equals("--data") || option.equals("--data-raw")
                || option.equals("--data-binary") || option.equals("--json") || option.equals("--url");
            if (valued && value == null) { if (++i == args.size()) throw invalid(); value = args.get(i); }
            if (option.equals("-X") || option.equals("--request")) {
                method = value.toUpperCase(Locale.ROOT);
                if (!method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")) throw invalid();
            } else if (option.equals("-H") || option.equals("--header")) {
                int at = value.indexOf(':');
                if (at <= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw invalid();
                String name = value.substring(0, at).trim().toLowerCase(Locale.ROOT);
                if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || headers.size() >= 32) throw invalid();
                // Connection framing is owned by HttpURLConnection.
                if (name.equals("content-length") || name.equals("transfer-encoding") || name.equals("host")) throw invalid();
                headers.put(name, value.substring(at + 1).trim());
            } else if (option.equals("-d") || option.equals("--data") || option.equals("--data-raw")
                || option.equals("--data-binary") || option.equals("--json")) {
                if (body != null || (!option.equals("--data-raw") && value.startsWith("@"))) throw invalid();
                body = value;
                if (option.equals("--json")) headers.putIfAbsent("content-type", "application/json");
            } else if (option.equals("--url")) {
                if (url != null) throw invalid(); url = value;
            } else if (option.equals("-s") || option.equals("-S") || option.equals("-sS") || option.equals("--silent")
                || option.equals("--show-error") || option.equals("--compressed")) {
                if (value != null) throw invalid();
            } else {
                if (option.startsWith("-") || url != null || value != null) throw invalid(); url = option;
            }
        }
        try {
            URI uri = new URI(url == null ? "" : url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
        } catch (java.net.URISyntaxException e) { throw invalid(); }
        if (body != null) {
            if ("GET".equals(method) || "HEAD".equals(method)) throw invalid();
            if (body.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid();
            String trimmed = body.trim();
            // Home Assistant JSON bodies also work when curl omits Content-Type.
            headers.putIfAbsent("content-type", trimmed.startsWith("{") || trimmed.startsWith("[")
                ? "application/json" : "application/x-www-form-urlencoded");
        }
        return new CurlRequest(method != null ? method : body != null ? "POST" : "GET", url, headers, body);
    }
    private static List<String> words(String text) {
        List<String> result = new ArrayList<>(); StringBuilder word = new StringBuilder();
        char quote = 0; boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '\'') {
                if (++i == text.length()) throw invalid();
                char next = text.charAt(i);
                if (next == '\n') continue;
                if (next == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; continue; }
                if (quote == '"' && next != '"' && next != '\\' && next != '$' && next != '`') word.append('\\');
                word.append(next); started = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
            } else if (c == '\'' || c == '"') { quote = c; started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else {
                if (";|&<>`".indexOf(c) >= 0 || c == '$') throw invalid();
                word.append(c); started = true;
            }
        }
        if (quote != 0) throw invalid();
        if (started) result.add(word.toString());
        return result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or unsupported curl command"); }
    /** A preview without URL credentials, query tokens, request headers or body. */
    public String summary() {
        URI uri = URI.create(url);
        return method + " " + uri.getScheme() + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof CurlRequest)) return false;
        CurlRequest r = (CurlRequest) other;
        return method.equals(r.method) && url.equals(r.url) && headers.equals(r.headers) && Objects.equals(body, r.body);
    }
    @Override public int hashCode() { return Objects.hash(method, url, headers, body); }
}
