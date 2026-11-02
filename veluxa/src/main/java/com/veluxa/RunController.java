package com.veluxa;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final TestRunService service;

    public RunController(TestRunService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody RunRequest request) {
        try {
            RunState state = service.start(request);
            return ResponseEntity.accepted().body(Map.of("id", state.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        RunState state = service.get(id);
        return state == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(state);
    }
}
