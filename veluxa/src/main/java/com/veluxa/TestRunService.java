package com.veluxa;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

@Service
public class TestRunService {

    static final int MAX_PAGES = 10;

    private static final Pattern SKIP_EXT = Pattern.compile(
        ".*\\.(pdf|zip|rar|gz|png|jpe?g|gif|svg|webp|ico|mp4|mp3|css|js|xml|docx?|xlsx?|pptx?|exe|dmg)$",
        Pattern.CASE_INSENSITIVE);
    // Links that could change state with a plain GET request are never followed.
    private static final Pattern RISKY = Pattern.compile(
        "(log-?out|sign-?out|delete|remove|unsubscribe|checkout)", Pattern.CASE_INSENSITIVE);
    private static final String[] USER_FIELDS = {
        "input[type=email]", "input[name*=email i]", "input[id*=email i]",
        "input[name*=user i]", "input[id*=user i]", "input[name*=login i]",
        "input[autocomplete=username]", "input[type=text]"
    };

    private final ObjectMapper mapper;
    private final UrlValidator validator;
    private final IdeaService ideas;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public TestRunService(ObjectMapper mapper, UrlValidator validator, IdeaService ideas) {
        this.mapper = mapper;
        this.validator = validator;
        this.ideas = ideas;
    }

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }

    public RunState get(String id) { return runs.get(id); }

    public RunState start(RunRequest req) {
        if (req == null) throw new IllegalArgumentException("Enter a website address.");
        URI start = validator.validate(req.url());
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-" + UUID.randomUUID().toString().substring(0, 6);
        RunState state = new RunState(id);
        runs.put(id, state);
        pool.submit(() -> execute(state, req, start));
        return state;
    }

    private void execute(RunState st, RunRequest req, URI start) {
        Path dir = Path.of("reports", st.getId());
        WebDriver driver = null;
        try {
            Files.createDirectories(dir);
            st.setStatus("running");
            st.log("Starting Chrome in the background...");
            driver = newDriver();
            String origin = origin(start.toString());

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("runId", st.getId());
            report.put("startUrl", start.toString());
            report.put("startedAt", Instant.now().toString());

            if (hasText(req.username()) && hasText(req.password())) {
                report.put("login", doLogin(driver, req, start, origin, st));
            }

            Deque<String> queue = new ArrayDeque<>();
            Set<String> seen = new LinkedHashSet<>();
            String first = stripFragment(start.toString());
            queue.add(first);
            seen.add(first);

            List<Map<String, Object>> pages = new ArrayList<>();
            while (!queue.isEmpty() && pages.size() < MAX_PAGES) {
                String url = queue.poll();
                int n = pages.size() + 1;
                st.log("Testing page " + n + ": " + url);
                pages.add(inspect(driver, url, n, st.getId(), dir, origin, queue, seen));
            }

            int totalChecks = 0, passedChecks = 0, pagesPassed = 0;
            for (Map<String, Object> p : pages) {
                if (Boolean.TRUE.equals(p.get("passed"))) pagesPassed++;
                for (Object c : (List<?>) p.get("checks")) {
                    totalChecks++;
                    if (Boolean.TRUE.equals(((Map<?, ?>) c).get("passed"))) passedChecks++;
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("pagesTested", pages.size());
            summary.put("pagesPassed", pagesPassed);
            summary.put("checksRun", totalChecks);
            summary.put("checksPassed", passedChecks);
            report.put("summary", summary);
            report.put("pages", pages);

            st.log("Writing test ideas...");
            report.put("testIdeas", ideas.generate(start.toString(), pages));
            report.put("finishedAt", Instant.now().toString());
            report.put("limits", "Same-origin crawl, max " + MAX_PAGES
                + " pages. No buttons were clicked and no forms were submitted (except the optional login).");

            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("report.json").toFile(), report);
            st.setReport(report);
            st.setStatus("done");
            st.log("Finished.");
        } catch (Exception e) {
            String msg = firstLine(e.getMessage());
            if (e instanceof org.openqa.selenium.SessionNotCreatedException
                    || msg.toLowerCase().contains("chrome")) {
                msg = "Couldn't start Chrome. Make sure Google Chrome is installed and that Maven can "
                    + "download its driver (internet access). Details: " + msg;
            }
            st.setError(msg.isBlank() ? e.getClass().getSimpleName() : msg);
            st.setStatus("failed");
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) { }
            }
        }
    }

    private WebDriver newDriver() {
        ChromeOptions o = new ChromeOptions();
        o.addArguments("--headless=new", "--no-sandbox", "--disable-gpu",
            "--disable-dev-shm-usage", "--window-size=1366,900", "--hide-scrollbars");
        LoggingPreferences lp = new LoggingPreferences();
        lp.enable(LogType.BROWSER, Level.SEVERE);
        o.setCapability("goog:loggingPrefs", lp);
        WebDriver d = new ChromeDriver(o);
        d.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        return d;
    }

    private Map<String, Object> doLogin(WebDriver d, RunRequest req, URI start, String origin, RunState st) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("attempted", true);
        try {
            String raw = hasText(req.loginUrl()) ? req.loginUrl().trim() : start.toString();
            URI loginUri = validator.validate(raw);
            if (!origin(loginUri.toString()).equals(origin)) {
                return fail(info, "The login page must be on the same site as the start address.");
            }
            st.log("Trying the test login...");
            d.get(loginUri.toString());
            waitReady(d);

            List<WebElement> pw = d.findElements(By.cssSelector("input[type=password]"));
            if (pw.isEmpty()) return fail(info, "No password field found on the login page.");
            WebElement user = findUserField(d);
            if (user == null) return fail(info, "No username or email field found on the login page.");

            user.clear();
            user.sendKeys(req.username());
            pw.get(0).clear();
            pw.get(0).sendKeys(req.password());
            List<WebElement> submit = d.findElements(By.cssSelector("button[type=submit], input[type=submit]"));
            if (!submit.isEmpty()) submit.get(0).click(); else pw.get(0).sendKeys(Keys.ENTER);

            String before = loginUri.toString();
            try {
                new WebDriverWait(d, Duration.ofSeconds(8)).until(x ->
                    x.findElements(By.cssSelector("input[type=password]")).isEmpty()
                        || !x.getCurrentUrl().equals(before));
            } catch (TimeoutException ignored) { }
            waitReady(d);

            boolean ok = d.findElements(By.cssSelector("input[type=password]")).isEmpty();
            info.put("success", ok);
            info.put("detail", ok
                ? "The password field disappeared after submitting, so the login looks accepted."
                : "The login page still shows a password field. The credentials may be wrong or this login flow isn't supported.");
        } catch (IllegalArgumentException e) {
            return fail(info, e.getMessage());
        } catch (Exception e) {
            return fail(info, "Login attempt failed: " + firstLine(e.getMessage()));
        }
        return info;
    }

    private static Map<String, Object> fail(Map<String, Object> info, String detail) {
        info.put("success", false);
        info.put("detail", detail);
        return info;
    }

    private WebElement findUserField(WebDriver d) {
        for (String css : USER_FIELDS) {
            for (WebElement e : d.findElements(By.cssSelector(css))) {
                try { if (e.isDisplayed()) return e; } catch (WebDriverException ignored) { }
            }
        }
        return null;
    }

    private Map<String, Object> inspect(WebDriver d, String url, int n, String runId, Path dir,
                                        String origin, Deque<String> queue, Set<String> seen) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        p.put("index", n);
        p.put("url", url);
        p.put("checks", checks);

        long t0 = System.nanoTime();
        try {
            d.get(url);
            waitReady(d);
        } catch (WebDriverException e) {
            checks.add(check("Page loads", false, "Failed to load: " + firstLine(e.getMessage())));
            p.put("passed", false);
            return p;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String current = d.getCurrentUrl();
        p.put("url", current);
        p.put("loadMs", ms);
        checks.add(check("Page loads", true, "Loaded in " + ms + " ms"));

        if (!origin(current).equals(origin)) {
            checks.add(check("Stays on the same site", false, "Redirected to " + current));
            p.put("passed", false);
            return p;
        }

        String title = d.getTitle();
        p.put("title", title == null ? "" : title);
        checks.add(check("Page has a title", title != null && !title.isBlank(),
            title == null || title.isBlank() ? "The page title is empty." : title));

        int interactive = d.findElements(By.cssSelector(
            "a[href], button, input:not([type=hidden]), select, textarea, [role=button]")).size();
        p.put("interactive", interactive);
        checks.add(check("Interactive elements found", interactive > 0, interactive + " found"));

        List<String> errors = new ArrayList<>();
        try {
            for (LogEntry e : d.manage().logs().get(LogType.BROWSER).getAll()) {
                String m = e.getMessage();
                if (m.contains("favicon.ico")) continue;
                errors.add(m.length() > 300 ? m.substring(0, 300) + "..." : m);
            }
        } catch (Exception ignored) { }
        p.put("consoleErrorCount", errors.size());
        p.put("consoleErrors", errors.size() > 5 ? errors.subList(0, 5) : errors);
        checks.add(check("No severe console errors", errors.isEmpty(),
            errors.isEmpty() ? "None" : errors.size() + " error(s). First: " + errors.get(0)));

        int forms = 0, passwords = 0, noAlt = 0;
        try {
            Object r = ((JavascriptExecutor) d).executeScript(
                "return [document.forms.length,"
                + "document.querySelectorAll('input[type=password]').length,"
                + "Array.from(document.images).filter(i=>!i.hasAttribute('alt')).length];");
            List<?> l = (List<?>) r;
            forms = ((Number) l.get(0)).intValue();
            passwords = ((Number) l.get(1)).intValue();
            noAlt = ((Number) l.get(2)).intValue();
        } catch (Exception ignored) { }
        p.put("forms", forms);
        p.put("passwordFields", passwords);
        p.put("imagesMissingAlt", noAlt);

        try {
            byte[] png = ((TakesScreenshot) d).getScreenshotAs(OutputType.BYTES);
            String file = "page-" + n + ".png";
            Files.write(dir.resolve(file), png);
            p.put("screenshot", "/reports/" + runId + "/" + file);
        } catch (IOException | WebDriverException ignored) { }

        // Collect same-origin links to visit next.
        for (WebElement a : d.findElements(By.cssSelector("a[href]"))) {
            if (seen.size() >= 200) break;
            try {
                String href = a.getAttribute("href");
                if (href == null || !href.regionMatches(true, 0, "http", 0, 4)) continue;
                href = stripFragment(href);
                if (!origin(href).equals(origin)) continue;
                String path = URI.create(href).getPath();
                if (path != null && SKIP_EXT.matcher(path).matches()) continue;
                if (RISKY.matcher(href).find()) continue;
                if (seen.add(href)) queue.add(href);
            } catch (Exception ignored) { }
        }

        boolean all = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        p.put("passed", all);
        return p;
    }

    private void waitReady(WebDriver d) {
        try {
            new WebDriverWait(d, Duration.ofSeconds(10)).until(x ->
                "complete".equals(((JavascriptExecutor) x).executeScript("return document.readyState")));
        } catch (TimeoutException ignored) { }
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", ok);
        m.put("detail", detail);
        return m;
    }

    static String origin(String url) {
        try {
            URI u = URI.create(url);
            int port = u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }
}
