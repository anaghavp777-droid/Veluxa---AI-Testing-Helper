package com.veluxa;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class RunState {
    private final String id;
    private volatile String status = "queued";
    private volatile String error;
    private volatile Map<String, Object> report;
    private final List<String> log = new CopyOnWriteArrayList<>();

    public RunState(String id) { this.id = id; }

    public String getId() { return id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Map<String, Object> getReport() { return report; }
    public void setReport(Map<String, Object> report) { this.report = report; }
    public List<String> getLog() { return log; }
    public void log(String message) { log.add(message); }
}
