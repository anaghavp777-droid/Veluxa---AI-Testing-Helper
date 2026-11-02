Veluxa
A lightweight website tester that crawls your site in a headless browser, runs quick health checks on every page, and suggests what to test next.

Enter a URL, click run, and Veluxa opens your site in headless Chrome, visits up to 10 same-site pages, and produces a report with screenshots, pass/fail checks and human-reviewable test ideas. It runs locally on your machine and never clicks buttons or submits forms (except an optional test login you provide).

Features
Automated crawl: follows same-origin links, up to 10 pages per run
Per-page checks: page loads, has a title, interactive elements are present, no severe browser console errors
Accessibility and form signals: counts forms, password fields and images missing alt text
Screenshots: a full-page capture for every page tested
Optional test login: provide a username and password to crawl pages behind a login
Test ideas: suggestions for what to test next, either rule-based (default) or AI-generated via OpenAI
Simple web UI: start a run and watch live progress at http://127.0.0.1:8080
Saved reports: each run writes report.json and screenshots to reports/<run-id>/
Tech stack
Java 17
Spring Boot 3.3
Selenium 4 with headless Google Chrome
Plain HTML/CSS/JS frontend (no build step)
Getting started
Prerequisites
Java 17+
Maven 3.9+
Google Chrome installed
Run
git clone https://github.com/<your-username>/veluxa.git
cd veluxa
mvn spring-boot:run
When you see Started VeluxaApplication, open http://127.0.0.1:8080, enter a website address and start a run.

Optional: AI-generated test ideas
Set your OpenAI key before starting. Without it, Veluxa falls back to built-in rules.

export OPENAI_API_KEY=your-key
export OPENAI_MODEL=gpt-4.1-mini   # optional, this is the default
mvn spring-boot:run
Optional: test a local app
By default, private, loopback and reserved addresses are blocked. To test an app running on localhost, edit src/main/resources/application.properties:

veluxa.allow-private=true
How it works
UrlValidator checks the address (http/https only, no embedded credentials, private ranges blocked).
TestRunService launches headless Chrome, optionally logs in, then crawls pages breadth-first.
Each page is inspected, screenshotted and scored against the checks above.
IdeaService turns the crawl summary into suggested test ideas (OpenAI or rule-based).
The report is saved to disk and shown in the UI.
API
Method	Endpoint	Description
POST	/api/runs	Start a run. Body: { "url", "loginUrl", "username", "password" }
GET	/api/runs/{id}	Get status, live log and the final report
Safety and limits
Binds to 127.0.0.1 only. Do not expose it to the internet.
Same-origin crawl, maximum 10 pages per run.
No buttons are clicked and no forms are submitted, apart from the optional login.
Links that look state-changing (logout, delete, remove, unsubscribe, checkout) are never followed.
Credentials are never logged.
The URL validation is a prototype safeguard, not a production-grade SSRF defense.
Only test websites you own or have permission to test.
Project structure
src/main/java/com/veluxa/
├── VeluxaApplication.java   # Spring Boot entry point
├── RunController.java       # REST API
├── RunRequest.java          # Request payload (credentials hidden in toString)
├── RunState.java            # Live status, log and report for a run
├── TestRunService.java      # Selenium crawl, login and page checks
├── IdeaService.java         # AI and rule-based test ideas
├── UrlValidator.java        # URL and private-address safeguards
└── WebConfig.java           # Serves screenshots and reports
src/main/resources/
├── application.properties
└── static/index.html        # Web UI
Roadmap ideas
Configurable page limit and crawl depth
Responsive and keyboard-navigation checks
HTML/PDF report export
Broken link detection
Run history in the UI
