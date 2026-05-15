package com.fiberify.dashboard.controller;

import com.fiberify.dashboard.model.BlockNode;
import com.fiberify.dashboard.service.ExcelProcessorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardApiController {

    @Autowired
    private ExcelProcessorService service;

    @GetMapping("/nodes")
    public ResponseEntity<Map<String, Object>> getNodes() {
        List<BlockNode> nodes = service.getCurrentData();

        Map<String, Object> response = new HashMap<>();

        response.put("data", nodes);
        response.put("dataVersion", service.getDataVersion());
        response.put("loading", !service.isInitialLoadComplete());
        return ResponseEntity.ok(response);
    }


    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("ready", service.isInitialLoadComplete());
        response.put("syncRunning", service.isSyncRunning());
        response.put("syncProgress", service.getSyncProgress());
        response.put("dataVersion", service.getDataVersion());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<Map<String, Object>>> getIncidents() {
        return ResponseEntity.ok(service.getLatestIncidents());
    }

    @PostMapping("/sync-live")
    public ResponseEntity<Map<String, String>> syncLiveApi() {
        Map<String, String> response = new HashMap<>();
        if (service.isSyncRunning()) {
            response.put("message", "Sync already in progress");
            return ResponseEntity.badRequest().body(response);
        }
        service.restartSync();
        response.put("message", "Sync restart initiated");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stop-sync")
    public ResponseEntity<Map<String, String>> stopSync() {
        service.stopSync();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Sync stop signaled");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sync-status")
    public Map<String, String> getSyncStatus() {
        Map<String, String> response = new HashMap<>();
        response.put("status", service.getSyncProgress());
        return response;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFiles(@RequestParam(value = "reportFile", required = false) MultipartFile reportFile,
                                              @RequestParam(value = "oltFile", required = false) MultipartFile oltFile) {
        try {
            File dir = new File("dashboard_files");
            if (!dir.exists()) dir.mkdirs();

            if (reportFile != null && !reportFile.isEmpty()) {
                File dest = new File(dir, "report.xlsx");
                reportFile.transferTo(dest);
            }

            if (oltFile != null && !oltFile.isEmpty()) {
                // Save it with original name or a specific format
                File dest = new File(dir, oltFile.getOriginalFilename());
                oltFile.transferTo(dest);
            }

            // Reprocess the files
            service.processLatestFiles();
            return ResponseEntity.ok("Files uploaded and processed successfully!");
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Error uploading files: " + e.getMessage());
        }
    }
}
