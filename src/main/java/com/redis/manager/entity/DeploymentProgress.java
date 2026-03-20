package com.redis.manager.entity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 集群部署进度追踪
 */
public class DeploymentProgress {
    
    private Long clusterId;
    private String clusterName;
    private String status; // running, success, error
    private Integer percent;
    private String currentStep;
    private String error;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, StepStatus> steps = new HashMap<>();
    
    public static class StepStatus {
        private String status; // pending, running, success, error
        private String message;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        
        public StepStatus() {}
        
        public StepStatus(String status, String message) {
            this.status = status;
            this.message = message;
        }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    }
    
    public DeploymentProgress(Long clusterId, String clusterName) {
        this.clusterId = clusterId;
        this.clusterName = clusterName;
        this.status = "running";
        this.percent = 0;
        this.startTime = LocalDateTime.now();
        
        // 初始化所有步骤
        String[] stepKeys = {"upload_config", "start_nodes", "create_cluster", "assign_slots", "setup_replica", "verify"};
        for (String key : stepKeys) {
            steps.put(key, new StepStatus("pending", "等待中..."));
        }
    }
    
    public void startStep(String stepKey, String message) {
        StepStatus step = steps.get(stepKey);
        if (step != null) {
            step.setStatus("running");
            step.setMessage(message);
            step.setStartTime(LocalDateTime.now());
        }
        this.currentStep = stepKey;
        updatePercent();
    }
    
    public void completeStep(String stepKey, String message) {
        StepStatus step = steps.get(stepKey);
        if (step != null) {
            step.setStatus("success");
            step.setMessage(message);
            step.setEndTime(LocalDateTime.now());
        }
        updatePercent();
    }
    
    public void failStep(String stepKey, String error) {
        StepStatus step = steps.get(stepKey);
        if (step != null) {
            step.setStatus("error");
            step.setMessage(error);
            step.setEndTime(LocalDateTime.now());
        }
        this.status = "error";
        this.error = error;
        this.endTime = LocalDateTime.now();
    }
    
    public void complete() {
        this.status = "success";
        this.percent = 100;
        this.endTime = LocalDateTime.now();
    }
    
    private void updatePercent() {
        long completed = steps.values().stream().filter(s -> "success".equals(s.getStatus())).count();
        long running = steps.values().stream().filter(s -> "running".equals(s.getStatus())).count();
        this.percent = (int) ((completed * 100 + running * 50) / steps.size());
    }
    
    // Getters and Setters
    public Long getClusterId() { return clusterId; }
    public void setClusterId(Long clusterId) { this.clusterId = clusterId; }
    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPercent() { return percent; }
    public void setPercent(Integer percent) { this.percent = percent; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public Map<String, StepStatus> getSteps() { return steps; }
    public void setSteps(Map<String, StepStatus> steps) { this.steps = steps; }
}
