package com.fiberify.dashboard.service;

import com.fiberify.dashboard.model.BlockNode;
import com.fiberify.dashboard.model.GpEntry;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for processing Excel data and synchronizing with live APIs.
 * Uses in-memory caching for dashboard data.
 */
@Service
public class ExcelProcessorService {

    private static final Logger log = LoggerFactory.getLogger(ExcelProcessorService.class);
    private static final String DASHBOARD_FILES_DIR = "dashboard_files";
    private static final String LIVE_TOKEN = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJmaWJlcmlmeWluYyIsImF1dGgiOiJST0xFX0JBLFJPTEVfT0EsUk9MRV9QTEFOX0FETUlOLFJPTEVfUk9MTE9VVF9BRE1JTixST0xFX1JPTExPVVRfTUFOQUdFUixST0xFX1VTRVJfQURNSU4iLCJleHAiOjE3Nzg5MTExNjl9.8jWc2hshIU-6y4wFwUH5LTjuzLhIE3ThuNkUDV2fLCR6rz1ZiWTWg_bqRedzBK0m8CfLjCJlfGTvskUraVrl8A";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private List<BlockNode> cachedData = new ArrayList<>();

    private List<Map<String, Object>> latestIncidents = new ArrayList<>();
    private String reportDate = "Loading...";
    private volatile String syncProgress = "Idle";
    private volatile boolean stopSync = false;
    private volatile boolean isSyncRunning = false;
    private volatile boolean initialLoadComplete = false;

    @PostConstruct
    public void init() {
        Thread loader = new Thread(() -> {
            log.info("Background init: loading base Excel data...");
            long start = System.currentTimeMillis();
            processLatestFiles();
            initialLoadComplete = true;
            log.info("Background init complete in {}ms", System.currentTimeMillis() - start);
        }, "data-loader");
        loader.setDaemon(true);
        loader.start();
    }

    // --- Getters and Status ---

    public List<BlockNode> getCurrentData() {
        return cachedData;
    }

    public List<Map<String, Object>> getLatestIncidents() {
        return latestIncidents;
    }

    public String getReportDate() {
        return reportDate;
    }

    public String getSyncProgress() {
        return syncProgress;
    }

    public boolean isSyncRunning() {
        return isSyncRunning;
    }

    public boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }

    public void stopSync() {
        this.stopSync = true;
    }

    // --- Core Processing Logic ---

    public void processLatestFiles() {
        File latestOlt = getLatestOltFile();
        if (latestOlt == null) {
            reportDate = "No Base Excel Found";
            return;
        }

        try {
            // If we have live incidents already, re-apply them to the new file data
            if (latestIncidents != null && !latestIncidents.isEmpty()) {
                processIncidentsAndRefresh(latestIncidents);
            } else {
                parseAndPersistOltFile(latestOlt, new HashMap<>(), new HashMap<>(), new HashMap<>());
            }
            // Ensure the report date reflects the file source if no live sync happened yet
            if (reportDate == null || reportDate.equals("Loading...")) {
                this.reportDate = LocalDate.now().format(DATE_FORMATTER);
            }
        } catch (Exception e) {
            reportDate = "Error loading base data";
            log.error("Failed to process latest files", e);
        }
    }

    public void processLiveApi() {
        if (isSyncRunning) return;
        
        isSyncRunning = true;
        stopSync = false;
        syncProgress = "Fetching Live Data...";
        
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30000);
            factory.setReadTimeout(60000);
            RestTemplate restTemplate = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", LIVE_TOKEN);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            List<Map<String, Object>> allIncidents = new ArrayList<>();
            int page = 0, size = 100, maxPages = 20; // Reduced from 50 to 20 for faster response

            while (page < maxPages && !stopSync) {
                syncProgress = String.format("Syncing... (%d items fetched)", allIncidents.size());
                String url = String.format("https://sitpolycab.fiberify.com/api/tr-cases/searchvalue/olt alerts?page=%d&size=%d&sort=update_date,desc&cacheBuster=%d",
                        page, size, System.currentTimeMillis());
                
                log.info("Sync: Calling API Page {}: {}", page, url);
                ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.GET, entity, Object.class);
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    List<Map<String, Object>> pageItems = extractContent(response.getBody());
                    if (pageItems == null || pageItems.isEmpty()) break;
                    allIncidents.addAll(pageItems);
                    if (pageItems.size() < size) break;
                    page++;
                } else {
                    break;
                }
            }
            
            this.latestIncidents = allIncidents;
            processIncidentsAndRefresh(allIncidents);
            
            syncProgress = stopSync ? "Stopped" : "Completed";
            reportDate = LocalDate.now().format(DATE_FORMATTER);
            
        } catch (Exception e) {
            syncProgress = "Error: " + e.getMessage();
            log.error("Live API sync failed", e);
        } finally {
            isSyncRunning = false;
        }
    }

    private List<Map<String, Object>> extractContent(Object body) {
        if (body instanceof List) return (List<Map<String, Object>>) body;
        if (body instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) body;
            if (map.containsKey("content")) return (List<Map<String, Object>>) map.get("content");
        }
        return new ArrayList<>();
    }

    private void processIncidentsAndRefresh(List<Map<String, Object>> incidents) throws Exception {
        Map<String, String> idToStatus = new HashMap<>(); 
        Map<String, String> idToAlarm = new HashMap<>();
        Map<String, String> idToTime = new HashMap<>();

        for (Map<String, Object> item : incidents) {
            String status = String.valueOf(item.getOrDefault("status", item.getOrDefault("ticketStatus", ""))).toUpperCase();
            String title = String.valueOf(item.getOrDefault("title", "")).toUpperCase();
            String assetName = String.valueOf(item.getOrDefault("assetName", "")).toUpperCase();
            String alarm = String.valueOf(item.getOrDefault("alarmCause", "Network Issue"));
            String time = String.valueOf(item.getOrDefault("updatedDate", item.getOrDefault("createdDate", "--")));

            boolean isActive = !status.contains("RESOLVE") && !status.contains("CLOSE");
            boolean isResolved = status.contains("RESOLVE") || status.contains("CLOSE");
            
            boolean downDetected = isActive && (isFailureKeywordPresent(title) || status.contains("NEW") || status.contains("ASSIGN"));

            if (isActive) {
                downDetected |= checkAttributesForFailures(item, idToStatus, idToAlarm, idToTime, alarm, time);
            }

            if (downDetected) {
                recordStatus(idToStatus, idToAlarm, idToTime, "DOWN", assetName, alarm, time);
                recordStatus(idToStatus, idToAlarm, idToTime, "DOWN", String.valueOf(item.get("code")), alarm, time);
                extractAndRecordIds(title, "DOWN", idToStatus, idToAlarm, idToTime, alarm, time);
            } else if (isResolved) {
                recordStatus(idToStatus, idToAlarm, idToTime, "RESOLVED", assetName, alarm, time);
                recordStatus(idToStatus, idToAlarm, idToTime, "RESOLVED", String.valueOf(item.get("code")), alarm, time);
                extractAndRecordIds(title, "RESOLVED", idToStatus, idToAlarm, idToTime, alarm, time);
            }
        }

        File latestOlt = getLatestOltFile();
        if (latestOlt != null) {
            parseAndPersistOltFile(latestOlt, idToStatus, idToAlarm, idToTime);
        } else {
            log.warn("No base OLT file found in dashboard_files/. Cannot populate dashboard grid.");
        }
    }

    private boolean isFailureKeywordPresent(String text) {
        String t = text.toUpperCase();
        return t.contains("DOWN") || t.contains("UNKNOWN") || t.contains("UNREACHABLE") || t.contains("OFFLINE") 
                || t.contains("CRITICAL") || t.contains("OUTAGE") || t.contains("LOS") || t.contains("FAIL") || t.contains("POWER");
    }

    private boolean checkAttributesForFailures(Map<String, Object> item, Map<String, String> idToStatus, 
                                             Map<String, String> idToAlarm, Map<String, String> idToTime, 
                                             String alarm, String time) {
        boolean detected = false;
        Object attrsObj = item.get("caseTypeAttributeValues");
        if (attrsObj instanceof List) {
            List<Map<String, Object>> attrs = (List<Map<String, Object>>) attrsObj;
            for (Map<String, Object> attr : attrs) {
                String val = String.valueOf(attr.get("attributeValue")).toUpperCase();
                if (val != null && !val.equals("NULL") && !val.trim().isEmpty()) {
                    if (isFailureKeywordPresent(val)) detected = true;
                    if (val.length() > 3 && !isKeyword(val)) {
                        recordStatus(idToStatus, idToAlarm, idToTime, "DOWN", val, alarm, time);
                    }
                }
            }
        }
        return detected;
    }

    private void extractAndRecordIds(String text, String status, Map<String, String> statusMap, 
                                   Map<String, String> alarms, Map<String, String> times, String alarm, String time) {
        Matcher m = Pattern.compile("[A-Z0-9.-]{4,}").matcher(text);
        while (m.find()) {
            String match = m.group();
            if (!isKeyword(match)) {
                recordStatus(statusMap, alarms, times, status, match, alarm, time);
            }
        }
    }

    private void recordStatus(Map<String, String> statusMap, Map<String, String> alarms, Map<String, String> times, 
                            String status, String id, String alarm, String time) {
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("null")) {
            String key = id.trim().toUpperCase();
            if (!statusMap.containsKey(key)) {
                statusMap.put(key, status);
                alarms.put(key, alarm);
                times.put(key, time);
            }
        }
    }

    private boolean isKeyword(String s) {
        String k = s.toUpperCase();
        return k.equals("DOWN") || k.equals("UNKNOWN") || k.equals("UNREACHABLE") || k.equals("OFFLINE") ||
               k.equals("CRITICAL") || k.equals("OUTAGE") || k.equals("POWER") || k.equals("FAILURE") ||
               k.equals("LINK") || k.equals("FAIL") || k.equals("RESOLVED") || k.equals("CLOSED") ||
               k.equals("BLOCK") || k.equals("NODE") || k.equals("OLT") || k.equals("GP");
    }

    private File getLatestOltFile() {
        File dir = new File(DASHBOARD_FILES_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith("OLT_GP_") && name.endsWith(".xlsx"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private void parseAndPersistOltFile(File file, Map<String, String> idToStatus,
                                       Map<String, String> idToAlarm, Map<String, String> idToTime) throws Exception {
        
        // Map existing nodes by IP for lookup
        Map<String, BlockNode> existingNodes = new HashMap<>();
        for (BlockNode n : cachedData) existingNodes.put(n.getIp(), n);

        // Map to keep track of nodes in order
        Map<String, BlockNode> orderedNodeMap = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(file); Workbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheetAt(0);
            Row hdr = sheet.getRow(0);
            
            int distCol = findCol(hdr, "DISTRICT");
            int nameCol = findCol(hdr, "BLOCK NODE NAME");
            int ipCol = findCol(hdr, "BLOCK NODE IP");
            int codeCol = findCol(hdr, "BLOCK CODE", "BLOCK NODE LOCATION CODE", "LOCATION CODE", "NODE CODE", "CODE");
            int gpBlockCol = findCol(hdr, "GP BLOCK");
            int gpLocCol = findCol(hdr, "GP LOCATION");
            int gpCodeCol = findCol(hdr, "GP LOCATION CODE");

            String curIP = "", curName = "", curDist = "", curCode = "", curGpBlock = "";
            
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                
                String ip = getCellValue(row, ipCol);
                String name = getCellValue(row, nameCol);
                String dist = getCellValue(row, distCol);
                String code = getCellValue(row, codeCol);
                String gpBlock = getCellValue(row, gpBlockCol);
                String gpLoc = getCellValue(row, gpLocCol);
                String gpCode = getCellValue(row, gpCodeCol);
                
                if (!ip.isEmpty()) {
                    curIP = ip; curName = name; curDist = dist; curCode = code; curGpBlock = gpBlock;
                }

                if (!curIP.isEmpty()) {
                    // Check if we already have this node in our ordered map
                    BlockNode node = orderedNodeMap.get(curIP);
                    if (node == null) {
                        // If not, try to get from existing nodes or create new
                        node = existingNodes.get(curIP);
                        if (node == null) {
                            node = new BlockNode();
                            node.setIp(curIP);
                        }
                        orderedNodeMap.put(curIP, node);
                    }
                    
                    updateNodeFromExcel(node, curIP, curName, curDist, curCode, curGpBlock, idToStatus, idToAlarm);
                    
                    if (!gpLoc.isEmpty()) {
                        addOrUpdateGp(node, gpLoc, gpCode, idToStatus);
                    }
                }
            }
            // Update cachedData with the ordered values
            this.cachedData = new ArrayList<>(orderedNodeMap.values());
            log.info("Excel Processing: Processed {} block nodes (ordered).", cachedData.size());
        }
    }

    private void updateNodeFromExcel(BlockNode node, String ip, String name, String dist, String code, String gpBlock, 
                                    Map<String, String> idToStatus, Map<String, String> idToAlarm) {
        node.setIp(ip);
        node.setName(name);
        node.setDistrict(dist);
        node.setBlockCode(code);
        node.setGpBlock(gpBlock);

        String status = getLatestStatus(ip, name, code, idToStatus);
        if ("DOWN".equals(status)) {
            node.setStatus("UNREACHABLE");
            node.setAlarm(findAlarm(ip, name, code, idToAlarm));
        } else if ("RESOLVED".equals(status)) {
            node.setStatus("RESOLVED");
        } else {
            node.setStatus("UP");
            node.setAlarm("--");
        }
    }

    private void addOrUpdateGp(BlockNode node, String loc, String code, Map<String, String> idToStatus) {
        GpEntry gp = node.getGps().stream()
                .filter(g -> g.getLoc().equalsIgnoreCase(loc))
                .findFirst().orElse(null);
        
        if (gp == null) {
            gp = new GpEntry(loc, code);
            node.addGp(gp);
        }
        
        String status = getLatestStatus(loc, code, "", idToStatus);
        if ("DOWN".equals(status) || "UNREACHABLE".equals(node.getStatus())) {
            gp.setStatus("DOWN");
        } else if ("RESOLVED".equals(status) || "RESOLVED".equals(parentStatus(node))) {
            gp.setStatus("RESOLVED");
        } else {
            gp.setStatus("UP");
        }
    }

    private String parentStatus(BlockNode node) {
        return node.getStatus();
    }

    private String getLatestStatus(String ip, String name, String code, Map<String, String> idToStatus) {
        String[] keys = { ip, name, code };
        for (String k : keys) {
            if (k == null || k.isEmpty()) continue;
            String normK = k.toUpperCase().trim();
            if (idToStatus.containsKey(normK)) return idToStatus.get(normK);
            if (normK.length() > 5) {
                for (Map.Entry<String, String> entry : idToStatus.entrySet()) {
                    if (entry.getKey().length() > 5 && isDiscreteMatch(normK, entry.getKey())) {
                        return entry.getValue();
                    }
                }
            }
        }
        return "UP";
    }

    private boolean isDiscreteMatch(String s1, String s2) {
        String full, sub;
        if (s1.contains(s2)) { full = s1; sub = s2; }
        else if (s2.contains(s1)) { full = s2; sub = s1; }
        else return false;

        int idx = full.indexOf(sub);
        while (idx != -1) {
            boolean startOk = (idx == 0 || !Character.isLetterOrDigit(full.charAt(idx - 1)));
            int endIdx = idx + sub.length();
            boolean endOk = (endIdx == full.length() || !Character.isLetterOrDigit(full.charAt(endIdx)));
            if (startOk && endOk) return true;
            idx = full.indexOf(sub, idx + 1);
        }
        return false;
    }

    private String findAlarm(String ip, String name, String code, Map<String, String> idToAlarm) {
        String[] keys = { ip, name, code };
        for (String k : keys) {
            if (k == null || k.isEmpty()) continue;
            String normK = k.toUpperCase().trim();
            if (idToAlarm.containsKey(normK)) return idToAlarm.get(normK);
        }
        return "Network Issue";
    }

    private int findCol(Row hdr, String... targets) {
        if (hdr == null) return -1;
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = getCellValue(hdr.getCell(c)).toUpperCase().trim();
            for (String t : targets) if (v.equals(t.toUpperCase())) return c;
        }
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = getCellValue(hdr.getCell(c)).toUpperCase().trim();
            for (String t : targets) if (v.contains(t.toUpperCase())) return c;
        }
        return -1;
    }

    private String getCellValue(Row row, int col) {
        return col < 0 ? "" : getCellValue(row.getCell(col));
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
        }
        return cell.toString().trim();
    }
}
