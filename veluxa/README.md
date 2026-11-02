# Veluxa

Run: `mvn spring-boot:run`, wait for "Started VeluxaApplication", then open http://127.0.0.1:8080
Requires Java 17+, Maven 3.9+, Google Chrome. Reports are written to `reports/<run-id>/`.
Optional AI ideas: set `OPENAI_API_KEY` (and optionally `OPENAI_MODEL`) before starting.
To test an app on localhost, set `veluxa.allow-private=true` in `src/main/resources/application.properties`.
