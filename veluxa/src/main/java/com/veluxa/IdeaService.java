package com.veluxa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Produces suggested test ideas. Ideas are suggestions only; nothing here is executed.
 * Uses OpenAI when OPENAI_API_KEY is set, otherwise deterministic rules.
 */
@Service
public class IdeaService {

    @Value("${OPENAI_API_KEY:}")
    private String apiKey;

    @Value("${OPENAI_MODEL:gpt-4.1-mini}")
    private String model;

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public IdeaService(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> generate(String startUrl, List<Map<String, Object>> pages) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> rules = ruleBased(pages);
        if (apiKey == null || apiKey.isBlank()) {
            out.put("source", "rule-based");
            out.put("ideas", rules);
            return out;
        }
        try {
            out.put("ideas", askModel(startUrl, pages));
            out.put("source", "AI (" + model + ")");
        } catch (Exception e) {
            out.put("source", "rule-based");
            out.put("note", "The AI request failed (" + e.getClass().getSimpleName() + "), so these ideas are rule-based.");
            out.put("ideas", rules);
        }
        return out;
    }

    private List<String> askModel(String startUrl, List<Map<String, Object>> pages) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("Site: ").append(startUrl).append("\nPages crawled:\n");
        for (Map<String, Object> p : pages) {
            sb.append("- ").append(p.get("url")).append(" | title: ").append(p.get("title"))
              .append(" | interactive elements: ").append(num(p, "interactive"))
              .append(" | forms: ").append(num(p, "forms"))
              .append(" | password fields: ").append(num(p, "passwordFields"))
              .append(" | images without alt: ").append(num(p, "imagesMissingAlt"))
              .append(" | console errors: ").append(num(p, "consoleErrorCount")).append("\n");
        }
        Map<String, Object> body = Map.of(
            "model", model,
            "temperature", 0.3,
            "response_format", Map.of("type", "json_object"),
            "messages", List.of(
                Map.of("role", "system", "content",
                    "You are a QA engineer. Given a crawl summary, suggest up to 10 specific, safe, human-reviewable "
                    + "test ideas (functional, accessibility, responsive, error handling). Never suggest destructive actions. "
                    + "Reply as JSON: {\"ideas\": [\"...\"]}."),
                Map.of("role", "user", "content", sb.toString())
            )
        );
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        String content = mapper.readTree(res.body()).path("choices").path(0).path("message").path("content").asText();
        JsonNode ideas = mapper.readTree(content).path("ideas");
        List<String> list = new ArrayList<>();
        for (JsonNode n : ideas) {
            if (n.isTextual() && !n.asText().isBlank() && list.size() < 12) list.add(n.asText());
        }
        if (list.isEmpty()) throw new IOException("No ideas returned");
        return list;
    }

    private List<String> ruleBased(List<Map<String, Object>> pages) {
        Set<String> ideas = new LinkedHashSet<>();
        for (Map<String, Object> p : pages) {
            String path = path(String.valueOf(p.get("url")));
            if (num(p, "forms") > 0)
                ideas.add("On " + path + ": submit the form empty and with invalid data, and confirm the validation messages are clear.");
            if (num(p, "passwordFields") > 0)
                ideas.add("On " + path + ": try login with a wrong password and with empty fields; confirm errors don't reveal whether an account exists.");
            if (num(p, "imagesMissingAlt") > 0)
                ideas.add("On " + path + ": add alt text to the " + num(p, "imagesMissingAlt") + " image(s) that have none.");
            if (num(p, "consoleErrorCount") > 0)
                ideas.add("On " + path + ": investigate the " + num(p, "consoleErrorCount") + " browser console error(s).");
            if (p.get("interactive") != null && num(p, "interactive") == 0)
                ideas.add("On " + path + ": no interactive elements were found; confirm that's expected.");
        }
        ideas.add("Check that every page has a unique title that describes it.");
        ideas.add("Resize to a phone width (375 px) and confirm navigation, text and buttons stay usable.");
        ideas.add("Open a page that doesn't exist and confirm a helpful 404 page appears.");
        ideas.add("Tab through each page and confirm keyboard focus is always visible and in a sensible order.");
        return new ArrayList<>(ideas);
    }

    private static int num(Map<String, Object> p, String key) {
        Object o = p.get(key);
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String path(String url) {
        try {
            String p = URI.create(url).getPath();
            return p == null || p.isBlank() ? "/" : p;
        } catch (Exception e) {
            return url;
        }
    }
}
