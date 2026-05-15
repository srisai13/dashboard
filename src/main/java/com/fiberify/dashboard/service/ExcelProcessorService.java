package com.fiberify.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service responsible for processing Excel data and synchronizing with live
 * APIs.
 * Uses in-memory caching for dashboard data.
 */
@Service
public class ExcelProcessorService {

    private static final Logger log = LoggerFactory.getLogger(ExcelProcessorService.class);
    private static final String DASHBOARD_FILES_DIR = "dashboard_files";
    private static final String LIVE_TOKEN = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJmaWJlcmlmeWluYyIsImF1dGgiOiJST0xFX0JBLFJPTEVfT0EsUk9MRV9QTEFOX0FETUlOLFJPTEVfUk9MTE9VVF9BRE1JTixST0xFX1JPTExPVVRfTUFOQUdFUixST0xFX1VTRVJfQURNSU4iLCJleHAiOjE3ODA2NDE0ODl9.t4T3tDkoJrUZmSy3Tg7n0zynibuko_TXz1WsLsCkoAIfEZej0S-_Pyhu8jI2FZ6_vE0BP9fm-0d2xZFCHUQ8ng";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private static final Set<String> FAILURE_KEYWORDS = new HashSet<>(Arrays.asList(
            "DOWN", "UNKNOWN", "UNREACHABLE", "OFFLINE", "CRITICAL", "OUTAGE", "LOS", "FAIL", "POWER"));

    private static final Set<String> SYSTEM_KEYWORDS = new HashSet<>(Arrays.asList(
            "DOWN", "UNKNOWN", "UNREACHABLE", "OFFLINE", "CRITICAL", "OUTAGE", "POWER", "FAILURE",
            "LINK", "FAIL", "RESOLVED", "CLOSED", "BLOCK", "NODE", "OLT", "GP"));

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Z0-9.-]{4,}");
    private List<BlockNode> cachedData = new ArrayList<>();

    private List<Map<String, Object>> latestIncidents = new CopyOnWriteArrayList<>();

    private volatile String incidentSyncProgress = "Waiting for sync...";

    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(10);
    private volatile boolean stopSync = false;
    private volatile boolean isIncidentSyncRunning = false;

    private volatile boolean initialLoadComplete = false;
    private boolean isFirstSyncOfSession = true;
    private volatile long dataVersion = System.currentTimeMillis();


    private Map<String, String> tempInitialStatus = new HashMap<>();
    private Map<String, String> tempInitialAlarm = new HashMap<>();
    private Map<String, String> tempInitialTime = new HashMap<>();
    private Map<String, String> tempInitialCreated = new HashMap<>();

    @PostConstruct
    public void init() {
        // Load status cache from previous sessions
        loadStatusCache();

        Thread loader = new Thread(() -> {
            log.info("Background init: loading base Excel data...");
            long start = System.currentTimeMillis();
            processLatestFiles();
            initialLoadComplete = true;
            log.info("Background init: Excel loaded in {}ms. Starting live API sync...",
                    System.currentTimeMillis() - start);



            // 1. Priority: Incident Status (I-status)
            // processLiveApi(); // Removed automatic startup sync

            log.info("Background init: Excel loaded.");
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

    public String getSyncProgress() {
        return incidentSyncProgress;
    }



    public boolean isSyncRunning() {
        return isIncidentSyncRunning;
    }



    public boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }





    public long getDataVersion() {
        return dataVersion;
    }

    public void stopSync() {
        this.stopSync = true;
    }

    // --- Core Processing Logic ---

    public void processLatestFiles() {
        File latestOlt = getLatestOltFile();
        if (latestOlt == null) {

            return;
        }

        try {
            // Apply initial status cache if present and no incidents yet
            if (latestIncidents != null && !latestIncidents.isEmpty()) {
                processIncidentsAndRefresh(latestIncidents);
            } else {
                processStatusMapsAndRefresh(tempInitialStatus, tempInitialAlarm, tempInitialTime, tempInitialCreated);
            }

        } catch (Exception e) {

            log.error("Failed to process latest files", e);
        }
    }

    public void restartSync() {
        if (isIncidentSyncRunning) {
            stopSync = true;
            int wait = 0;
            while (isIncidentSyncRunning && wait < 30) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                wait++;
            }
        }
        new Thread(this::processLiveApi).start();
    }

    public void processLiveApi() {
        if (isIncidentSyncRunning)
            return;

        isIncidentSyncRunning = true;
        stopSync = false;
        incidentSyncProgress = "Fetching Live Data...";

        Map<String, String> idToStatus = new HashMap<>();
        Map<String, String> idToAlarm = new HashMap<>();
        Map<String, String> idToTime = new HashMap<>();
        Map<String, String> idToCreated = new HashMap<>();

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30000);
            factory.setReadTimeout(60000);
            RestTemplate restTemplate = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", LIVE_TOKEN);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            int totalFetched = 0;
            int page = 0, size = 100, maxPages = 1000;

            while (page < maxPages && !stopSync) {
                incidentSyncProgress = String.format("Syncing... (%d items processed)", totalFetched);
                String url = String.format(
                        "https://sitpolycab.fiberify.com/api/tr-cases/searchvalue/olt alerts?page=%d&size=%d&sort=update_date,desc&cacheBuster=%d",
                        page, size, System.currentTimeMillis());

                log.info("Sync: Calling API Page {}: {}", page, url);
                ResponseEntity<Object> response = null;
                int retries = 0;
                while (retries < 3 && !stopSync) {
                    try {
                        response = restTemplate.exchange(url, HttpMethod.GET, entity, Object.class);
                        break;
                    } catch (Exception ex) {
                        retries++;
                        if (retries < 3 && !stopSync) {
                            log.warn("API Call failed (try {}): {}. Retrying in 5s...", retries, ex.getMessage());
                            Thread.sleep(5000);
                        } else {
                            throw ex;
                        }
                    }
                }

                if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Object body = response.getBody();
                    if (body instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) body;
                        if (m.containsKey("totalPages")) {
                            maxPages = Math.min(1000, ((Number) m.get("totalPages")).intValue());
                        } else if (m.get("page") instanceof Map) {
                            Map<String, Object> p = (Map<String, Object>) m.get("page");
                            if (p.containsKey("totalPages"))
                                maxPages = ((Number) p.get("totalPages")).intValue();
                        }
                    }

                    List<Map<String, Object>> pageItems = extractContent(body);
                    if (pageItems == null || pageItems.isEmpty())
                        break;

                    totalFetched += pageItems.size();
                    updateStatusMapsFromIncidents(pageItems, idToStatus, idToAlarm, idToTime, idToCreated);

                    // Progressive update every 10 pages to show numbers updating
                    if (page % 10 == 0) {
                        try {
                            processStatusMapsAndRefresh(idToStatus, idToAlarm, idToTime, idToCreated);
                        } catch (Exception ex) {
                            log.warn("Progressive update failed at page {}: {}", page, ex.getMessage());
                        }
                    }

                    if (pageItems.size() < size)
                        break;
                    page++;
                } else {
                    break;
                }
            }

            // Final bulk update
            log.info("Sync: Processed {} incidents total. Finalizing...", totalFetched);
            processStatusMapsAndRefresh(idToStatus, idToAlarm, idToTime, idToCreated);
            saveStatusCache(idToStatus, idToAlarm, idToTime, idToCreated);
            isFirstSyncOfSession = false;

            incidentSyncProgress = stopSync ? "Stopped" : "Completed (" + totalFetched + " items)";



        } catch (Exception e) {
            incidentSyncProgress = "Error: " + e.getMessage();
            log.error("Live API sync failed", e);
        } finally {
            isIncidentSyncRunning = false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractContent(Object body) {
        if (body instanceof List)
            return (List<Map<String, Object>>) body;
        if (body instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) body;
            if (map.containsKey("content"))
                return (List<Map<String, Object>>) map.get("content");
            if (map.containsKey("data"))
                return (List<Map<String, Object>>) map.get("data");
        }
        return new ArrayList<>();
    }


    private void processIncidentsAndRefresh(List<Map<String, Object>> incidents) {
        try {
            updateStatusMapsFromIncidents(incidents, tempInitialStatus, tempInitialAlarm, tempInitialTime, tempInitialCreated);
            processStatusMapsAndRefresh(tempInitialStatus, tempInitialAlarm, tempInitialTime, tempInitialCreated);
        } catch (Exception e) {
            log.error("Failed to process incidents and refresh", e);
        }
    }


    private void processStatusMapsAndRefresh(Map<String, String> idToStatus, Map<String, String> idToAlarm,
            Map<String, String> idToTime, Map<String, String> idToCreated) throws Exception {
        // Merge with initial cache if this is the very first partial refresh
        if (idToStatus.isEmpty() && !tempInitialStatus.isEmpty()) {
            idToStatus.putAll(tempInitialStatus);
            idToAlarm.putAll(tempInitialAlarm);
            idToTime.putAll(tempInitialTime);
            if (tempInitialCreated != null) {
                idToCreated.putAll(tempInitialCreated);
            }
        }

        File latestOlt = getLatestOltFile();
        if (latestOlt != null) {
            parseAndPersistOltFile(latestOlt, idToStatus, idToAlarm, idToTime, idToCreated);
        } else {
            log.warn("No base OLT file found in dashboard_files/. Cannot populate dashboard grid.");
        }
    }

    private boolean isFailureKeywordPresent(String text) {
        if (text == null)
            return false;
        String t = text.toUpperCase();
        for (String kw : FAILURE_KEYWORDS) {
            if (t.contains(kw))
                return true;
        }
        return false;
    }

    private boolean checkAttributesForFailures(Map<String, Object> item, Map<String, String> idToStatus,
            Map<String, String> idToAlarm, Map<String, String> idToTime, Map<String, String> idToCreated,
            String alarm, String time, String created) {
        boolean detected = false;
        Object attrsObj = item.get("caseTypeAttributeValues");
        if (attrsObj instanceof List) {
            List<Map<String, Object>> attrs = (List<Map<String, Object>>) attrsObj;
            for (Map<String, Object> attr : attrs) {
                String val = String.valueOf(attr.get("attributeValue")).toUpperCase();
                if (val != null && !val.equals("NULL") && !val.trim().isEmpty()) {
                    if (isFailureKeywordPresent(val))
                        detected = true;
                    if (val.length() > 3 && !isKeyword(val)) {
                        recordStatus(idToStatus, idToAlarm, idToTime, idToCreated, "DOWN", val, alarm, time, created);
                    }
                }
            }
        }
        return detected;
    }

    private void extractAndRecordIds(String text, String status, Map<String, String> statusMap,
            Map<String, String> alarms, Map<String, String> times, Map<String, String> createdDates,
            String alarm, String time, String created) {
        if (text == null)
            return;
        Matcher m = ID_PATTERN.matcher(text);
        while (m.find()) {
            String match = m.group();
            if (!isKeyword(match)) {
                recordStatus(statusMap, alarms, times, createdDates, status, match, alarm, time, created);
            }
        }
    }

    private void recordStatus(Map<String, String> statusMap, Map<String, String> alarms, Map<String, String> times,
            Map<String, String> createdDates, String status, String id, String alarm, String time, String created) {
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("null")) {
            String key = id.trim().toUpperCase();
            if (!statusMap.containsKey(key)) {
                statusMap.put(key, status);
                alarms.put(key, alarm);
                times.put(key, time);
                createdDates.put(key, created);
            }
        }
    }

    private boolean isKeyword(String s) {
        if (s == null)
            return false;
        return SYSTEM_KEYWORDS.contains(s.toUpperCase());
    }

    private File getLatestOltFile() {
        File dir = new File(DASHBOARD_FILES_DIR);
        File[] files = dir.listFiles((d, name) -> name.startsWith("OLT_GP_") && name.endsWith(".xlsx"));
        if (files == null || files.length == 0)
            return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private void parseAndPersistOltFile(File file, Map<String, String> idToStatus,
            Map<String, String> idToAlarm, Map<String, String> idToTime, Map<String, String> idToCreated)
            throws Exception {

        // Map existing nodes by IP for lookup
        Map<String, BlockNode> existingNodes = new HashMap<>();
        for (BlockNode n : cachedData)
            existingNodes.put(n.getIp(), n);

        // Map to keep track of nodes in order
        Map<String, BlockNode> orderedNodeMap = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(file); Workbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheetAt(0);
            Row hdr = sheet.getRow(0);

            int distCol = findCol(hdr, "DISTRICT", "DIST");
            int nameCol = findCol(hdr, "BLOCK NODE NAME", "NODE NAME", "NAME");
            int ipCol = findCol(hdr, "BLOCK NODE IP", "NODE IP", "IP ADDRESS", "IP");
            int codeCol = findCol(hdr, "BLOCK CODE", "BLOCK NODE LOCATION CODE", "LOCATION CODE", "NODE CODE", "CODE");
            int gpBlockCol = findCol(hdr, "GP BLOCK", "GP B");
            int gpLocCol = findCol(hdr, "GP LOCATION", "GP L");
            int gpCodeCol = findCol(hdr, "GP LOCATION CODE", "GP C");

            String curIP = "", curName = "", curDist = "", curCode = "", curGpBlock = "";

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null)
                    continue;

                String ip = getCellValue(row, ipCol);
                String name = getCellValue(row, nameCol);
                String dist = getCellValue(row, distCol);
                String code = getCellValue(row, codeCol);
                String gpBlock = getCellValue(row, gpBlockCol);
                String gpLoc = getCellValue(row, gpLocCol);
                String gpCode = getCellValue(row, gpCodeCol);

                if (!ip.isEmpty()) {
                    curIP = ip;
                    curName = name;
                    curDist = dist;
                    curCode = code;
                    curGpBlock = gpBlock;
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

                    updateNodeFromExcel(node, curIP, curName, curDist, curCode, curGpBlock, idToStatus, idToAlarm,
                            idToTime, idToCreated);

                    if (!gpLoc.isEmpty()) {
                        addOrUpdateGp(node, gpLoc, gpCode, idToStatus);
                    }
                }
            }
            // Update cachedData with the ordered values
            this.cachedData = new ArrayList<>(orderedNodeMap.values());
            this.dataVersion = System.currentTimeMillis();
            log.info("Excel Processing: Processed {} block nodes (ordered). Version: {}", cachedData.size(), dataVersion);
            if (cachedData.isEmpty()) {
                log.warn(
                        "Excel Processing: No nodes were found. Check if headers match: DISTRICT, BLOCK NODE NAME, BLOCK NODE IP.");
            }
        }
    }

    private void updateNodeFromExcel(BlockNode node, String ip, String name, String dist, String code, String gpBlock,
            Map<String, String> idToStatus, Map<String, String> idToAlarm, Map<String, String> idToTime,
            Map<String, String> idToCreated) {
        node.setIp(ip);
        node.setName(name);
        node.setDistrict(dist);
        node.setBlockCode(code);
        node.setGpBlock(gpBlock);

        String status = getLatestStatus(ip, name, code, idToStatus);
        if ("DOWN".equals(status)) {
            node.setStatus("UNREACHABLE");
            node.setAlarm(findAlarm(ip, name, code, idToAlarm));
            node.setStateChange(findTime(ip, name, code, idToTime));
        } else if ("RESOLVED".equals(status)) {
            node.setStatus("RESOLVED");
            node.setStateChange(findTime(ip, name, code, idToTime));
        } else {
            node.setStatus("UP");
            node.setAlarm("--");
            node.setStateChange("--");
            node.setCreatedDate("--");
        }

        // Also set created date if any incident exists
        String createdDate = findCreated(ip, name, code, idToCreated);
        if (createdDate != null && !createdDate.equals("--")) {
            node.setCreatedDate(createdDate);
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
            if (k == null || k.isEmpty())
                continue;
            String normK = k.toUpperCase().trim();
            if (idToStatus.containsKey(normK))
                return idToStatus.get(normK);
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
        if (s1.contains(s2)) {
            full = s1;
            sub = s2;
        } else if (s2.contains(s1)) {
            full = s2;
            sub = s1;
        } else
            return false;

        int idx = full.indexOf(sub);
        while (idx != -1) {
            boolean startOk = (idx == 0 || !Character.isLetterOrDigit(full.charAt(idx - 1)));
            int endIdx = idx + sub.length();
            boolean endOk = (endIdx == full.length() || !Character.isLetterOrDigit(full.charAt(endIdx)));
            if (startOk && endOk)
                return true;
            idx = full.indexOf(sub, idx + 1);
        }
        return false;
    }

    private String findAlarm(String ip, String name, String code, Map<String, String> idToAlarm) {
        String[] keys = { ip, name, code };
        for (String k : keys) {
            if (k == null || k.isEmpty())
                continue;
            String normK = k.toUpperCase().trim();
            if (idToAlarm.containsKey(normK))
                return idToAlarm.get(normK);
        }
        return "Network Issue";
    }

    private String findTime(String ip, String name, String code, Map<String, String> idToTime) {
        String[] keys = { ip, name, code };
        for (String k : keys) {
            if (k == null || k.isEmpty())
                continue;
            String normK = k.toUpperCase().trim();
            if (idToTime.containsKey(normK))
                return idToTime.get(normK);
        }
        return "--";
    }

    private String findCreated(String ip, String name, String code, Map<String, String> idToCreated) {
        String[] keys = { ip, name, code };
        for (String k : keys) {
            if (k == null || k.isEmpty())
                continue;
            String normK = k.toUpperCase().trim();
            if (idToCreated.containsKey(normK))
                return idToCreated.get(normK);
        }
        return "--";
    }

    private int findCol(Row hdr, String... targets) {
        if (hdr == null)
            return -1;
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = getCellValue(hdr.getCell(c)).toUpperCase().trim();
            for (String t : targets)
                if (v.equals(t.toUpperCase()))
                    return c;
        }
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = getCellValue(hdr.getCell(c)).toUpperCase().trim();
            for (String t : targets)
                if (v.contains(t.toUpperCase()))
                    return c;
        }
        return -1;
    }

    private String getCellValue(Row row, int col) {
        return col < 0 ? "" : getCellValue(row.getCell(col));
    }

    private String getCellValue(Cell cell) {
        if (cell == null)
            return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
        }
        return cell.toString().trim();
    }

    private void saveIncidents() {
        try {
            File file = new File(DASHBOARD_FILES_DIR, "last_incidents.json");
            new ObjectMapper().writeValue(file, latestIncidents);
            log.info("Saved {} incidents to cache", latestIncidents.size());
        } catch (IOException e) {
            log.warn("Failed to save incidents cache: {}", e.getMessage());
        }
    }




    private void saveStatusCache(Map<String, String> status, Map<String, String> alarm, Map<String, String> time,
            Map<String, String> created) {
        try {
            Map<String, Object> cache = new HashMap<>();
            cache.put("status", status);
            cache.put("alarm", alarm);
            cache.put("time", time);
            cache.put("created", created);
            new ObjectMapper().writeValue(new File(DASHBOARD_FILES_DIR, "status_cache.json"), cache);
            log.info("Saved status cache ({} records)", status.size());
        } catch (IOException e) {
            log.warn("Failed to save status cache: {}", e.getMessage());
        }
    }

    private void loadStatusCache() {
        try {
            File f = new File(DASHBOARD_FILES_DIR, "status_cache.json");
            if (f.exists()) {
                Map<String, Map<String, String>> cache = new ObjectMapper().readValue(f,
                        new TypeReference<Map<String, Map<String, String>>>() {
                        });
                this.tempInitialStatus = cache.get("status");
                this.tempInitialAlarm = cache.get("alarm");
                this.tempInitialTime = cache.get("time");
                this.tempInitialCreated = cache.get("created");
                if (this.tempInitialCreated == null)
                    this.tempInitialCreated = new HashMap<>();
                log.info("Loaded status cache from disk");
            }
        } catch (Exception e) {
            log.warn("Failed to load status cache: {}", e.getMessage());
        }
    }

    private void updateStatusMapsFromIncidents(List<Map<String, Object>> incidents, Map<String, String> idToStatus,
            Map<String, String> idToAlarm, Map<String, String> idToTime, Map<String, String> idToCreated) {
        for (Map<String, Object> item : incidents) {
            String status = String.valueOf(item.getOrDefault("status", item.getOrDefault("ticketStatus", "")))
                    .toUpperCase();
            String title = String.valueOf(item.getOrDefault("title", "")).toUpperCase();
            String assetName = String.valueOf(item.getOrDefault("assetName", "")).toUpperCase();
            String alarm = String.valueOf(item.getOrDefault("alarmCause", "Network Issue"));
            String time = formatTimestamp(item.getOrDefault("updateDate", item.getOrDefault("createDate", "--")));
            String created = formatTimestamp(item.getOrDefault("createDate", "--"));

            boolean isActive = !status.contains("RESOLVE") && !status.contains("CLOSE");
            boolean isResolved = status.contains("RESOLVE") || status.contains("CLOSE");

            boolean downDetected = isActive
                    && (isFailureKeywordPresent(title) || status.contains("NEW") || status.contains("ASSIGN"));

            if (isActive) {
                downDetected |= checkAttributesForFailures(item, idToStatus, idToAlarm, idToTime, idToCreated, alarm,
                        time, created);
            }

            if (downDetected) {
                recordStatus(idToStatus, idToAlarm, idToTime, idToCreated, "DOWN", assetName, alarm, time, created);
                recordStatus(idToStatus, idToAlarm, idToTime, idToCreated, "DOWN", String.valueOf(item.get("code")),
                        alarm, time, created);
                extractAndRecordIds(title, "DOWN", idToStatus, idToAlarm, idToTime, idToCreated, alarm, time, created);
            } else if (isResolved) {
                recordStatus(idToStatus, idToAlarm, idToTime, idToCreated, "RESOLVED", assetName, alarm, time, created);
                recordStatus(idToStatus, idToAlarm, idToTime, idToCreated, "RESOLVED", String.valueOf(item.get("code")),
                        alarm,
                        time, created);
                extractAndRecordIds(title, "RESOLVED", idToStatus, idToAlarm, idToTime, idToCreated, alarm, time,
                        created);
            }
        }
    }

    private String formatTimestamp(Object timestamp) {
        if (timestamp == null || timestamp.toString().equals("null") || timestamp.toString().equals("--")) {
            return "--";
        }
        try {
            // Check if it's a number (timestamp)
            String tsStr = timestamp.toString();
            if (tsStr.matches("\\d+")) {
                long ms = Long.parseLong(tsStr);
                LocalDateTime ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms),
                        java.time.ZoneId.systemDefault());
                return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return tsStr;
        } catch (Exception e) {
            return timestamp.toString();
        }
    }
}
