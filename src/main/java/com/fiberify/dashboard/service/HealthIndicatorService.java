package com.fiberify.dashboard.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import com.fiberify.dashboard.model.BlockNode;

import java.io.File;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated service for fetching OLT health indicator data.
 * Completely independent of ExcelProcessorService -- no shared state, no interference.
 * Runs its own scheduled background refresh every 10 minutes.
 */
@Service
public class HealthIndicatorService {

    private static final Logger log = LoggerFactory.getLogger(HealthIndicatorService.class);

    private static final String LIVE_TOKEN =
        "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJmaWJlcmlmeWluYyIsImF1dGgiOiJST0xFX0JBLFJPTEVfT0EsUk9MRV9QTEFOX0FETUlOLFJPTEVfUk9MTE9VVF9BRE1JTixST0xFX1JPTExPVVRfTUFOQUdFUixST0xFX1VTRVJfQURNSU4iLCJleHAiOjE3ODA2NDE0ODl9.t4T3tDkoJrUZmSy3Tg7n0zynibuko_TXz1WsLsCkoAIfEZej0S-_Pyhu8jI2FZ6_vE0BP9fm-0d2xZFCHUQ8ng";

    private static final String ASSET_API =
        "https://sitpolycab.fiberify.com/api/assets-group-db/searchvalue/olt";

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private volatile List<Map<String, Object>> cachedData   = new ArrayList<>();
    private volatile List<BlockNode>           masterRefData = new ArrayList<>();
    private volatile String                    lastFetchedAt = "Not yet fetched";
    private volatile boolean                   fetchRunning  = false;
    private volatile String                    syncProgress  = "";

    @Autowired
    private ExcelProcessorService excelService;

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-indicator-scheduler");
            t.setDaemon(true);
            return t;
        });

    @PostConstruct
    public void init() {
        loadFromDisk();
        loadMasterFile(); 
        new Thread(this::fetchAndCache).start();
    }

    private void loadMasterFile() {
        File f = new File("OLTFULLDATA.xlsx");
        if (!f.exists()) f = new File("dashboard_files", "OLTFULLDATA.xlsx");
        if (!f.exists()) return;

        log.info("HealthIndicatorService: Loading private master reference from {}", f.getAbsolutePath());
        List<BlockNode> results = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(f); Workbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheetAt(0);
            Row hdr = sheet.getRow(0);
            int distCol = findCol(hdr, "DISTRICT", "DIST", "REGION");
            int nameCol = findCol(hdr, "OLT NAME", "NODE NAME", "NAME", "OLT_NAME", "BLOCK NODE NAME");
            int codeCol = findCol(hdr, "OLT CODE", "OLT_CODE", "CODE", "LOCATION CODE");
            int blkCol  = findCol(hdr, "GP BLOCK", "BLOCK", "BLOCK_NAME");

            log.info("HealthIndicatorService Mapping: dist={}, name={}, code={}, block={}", 
                distCol, nameCol, codeCol, blkCol);
            
            if (nameCol == -1) {
                log.warn("HealthIndicatorService: Could not find 'OLT Name' column. Headers: {}", getHeaders(hdr));
                return;
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String name = getCellValue(row.getCell(nameCol));
                if (name.isEmpty()) continue;

                BlockNode bn = new BlockNode();
                bn.setName(name);
                bn.setDistrict(getCellValue(row.getCell(distCol)));
                bn.setBlockCode(getCellValue(row.getCell(codeCol)));
                bn.setGpBlock(getCellValue(row.getCell(blkCol)));
                results.add(bn);
            }
            this.masterRefData = results;
            log.info("HealthIndicatorService: Successfully loaded {} master references from {}.", results.size(), f.getName());
            
            this.cachedData = new ArrayList<>();
            new File("dashboard_files", "health_cache.json").delete();
            log.info("HealthIndicatorService: Cache cleared due to master file update.");
            
        } catch (Exception e) {
            log.warn("HealthIndicatorService: Failed to load master file: {}", e.getMessage());
        }
    }

    private String getHeaders(Row hdr) {
        if (hdr == null) return "NULL";
        List<String> list = new ArrayList<>();
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            list.add(getCellValue(hdr.getCell(c)));
        }
        return list.toString();
    }

    private int findCol(Row hdr, String... targets) {
        if (hdr == null) return -1;
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = getCellValue(hdr.getCell(c)).toUpperCase().trim();
            for (String t : targets) if (v.contains(t.toUpperCase())) return c;
        }
        return -1;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
        }
        return cell.toString().trim();
    }

    private void saveToDisk() {
        try {
            File dir = new File("dashboard_files");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "health_cache.json");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writeValue(file, cachedData);
            log.info("HealthIndicatorService: Saved {} records to disk.", cachedData.size());
        } catch (Exception e) {
            log.warn("HealthIndicatorService: Failed to save cache to disk: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        try {
            File file = new File("dashboard_files", "health_cache.json");
            if (file.exists()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                cachedData = mapper.readValue(file, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>(){});
                lastFetchedAt = "Loaded from disk (Restarted)";
                log.info("HealthIndicatorService: Loaded {} records from disk.", cachedData.size());
            }
        } catch (Exception e) {
            log.warn("HealthIndicatorService: Failed to load cache from disk: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getCachedData() {
        return cachedData;
    }

    public String getLastFetchedAt() {
        return lastFetchedAt;
    }

    public boolean isFetchRunning() {
        return fetchRunning;
    }

    public String getSyncProgress() {
        return syncProgress;
    }

    public void fetchAndCache() {
        if (fetchRunning) return;
        fetchRunning = true;
        syncProgress = "Starting OLT Health Sync...";
        
        loadMasterFile();
        
        log.info("HealthIndicatorService: Starting OLT health fetch...");

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30_000);
            factory.setReadTimeout(60_000);
            RestTemplate restTemplate = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", LIVE_TOKEN);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            int page = 0, size = 100, maxPages = 50;

            while (page < maxPages) {
                String url = String.format(
                    "%s?page=%d&size=%d&includeAttributes=true&cacheBuster=%d",
                    ASSET_API, page, size, System.currentTimeMillis());

                ResponseEntity<Object> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, Object.class);

                log.info("HealthIndicatorService: fetching page {}, status: {}", page, response.getStatusCode());
                
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    log.warn("HealthIndicatorService: fetch failed or empty body on page {}", page);
                    break;
                }

                List<Map<String, Object>> items = extractContent(response.getBody());
                if (items == null || items.isEmpty()) {
                    log.info("HealthIndicatorService: No items found on page {}", page);
                    break;
                }

                for (Map<String, Object> asset : items) {
                    result.add(buildRow(asset));
                }

                if (items.size() < size) break;
                page++;
                syncProgress = "Syncing OLT Data... (Page " + page + ")";
                log.info("HealthIndicatorService: " + syncProgress);
            }

            syncProgress = "";

            if (result.isEmpty()) {
                log.warn("HealthIndicatorService: No data fetched from API. Keeping existing cache.");
            } else if (hasChanged(result)) {
                cachedData = result;
                saveToDisk();
                log.info("HealthIndicatorService: cache updated with {} records and saved to disk.", result.size());
            } else {
                log.info("HealthIndicatorService: fetched {} records, no change detected.", result.size());
            }

            lastFetchedAt = LocalDate.now().format(DATE_FMT) + " "
                + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

        } catch (Exception e) {
            log.error("HealthIndicatorService: fetch failed", e);
        } finally {
            fetchRunning = false;
        }
    }

    private Map<String, Object> buildRow(Map<String, Object> asset) {
        Object healthIndObj = asset.get("healthIndicator");
        Object remarksObj   = asset.get("healthRemarks");

        String healthStatus;
        String alarmCause;

        if (healthIndObj == null) {
            healthStatus = "DOWN";
            alarmCause   = "No health data from API (Marked DOWN)";
        } else {
            boolean isHealthy;
            if (healthIndObj instanceof Boolean) {
                isHealthy = (Boolean) healthIndObj;
            } else {
                String hVal = healthIndObj.toString().trim().toLowerCase();
                isHealthy = !hVal.equals("false") && !hVal.equals("0")
                         && !hVal.equals("down")  && !hVal.equals("fail") && !hVal.isEmpty();
            }
            healthStatus = isHealthy ? "UP" : "DOWN";
            alarmCause   = isHealthy ? "Health Check Passed" : "Health Check Failed";
        }

        if (remarksObj != null && !remarksObj.toString().trim().isEmpty()
                && !remarksObj.toString().equalsIgnoreCase("null")) {
            alarmCause = remarksObj.toString().trim();
        }

        String nmsOltCd = "";
        String blkName  = "";
        
        for (Map.Entry<String, Object> entry : asset.entrySet()) {
            String key = entry.getKey().toLowerCase();
            Object val = entry.getValue();
            if (val == null || val.toString().equalsIgnoreCase("null") || val.toString().trim().isEmpty()) continue;
            
            String sVal = val.toString().trim();
            if (nmsOltCd.isEmpty() && (key.contains("nmsolt") || key.equals("code") || key.equals("externalid"))) {
                nmsOltCd = sVal;
            }
            if (blkName.isEmpty() && (key.contains("block") || key.contains("blk"))) {
                blkName = sVal;
            }
        }
        
        Object attrListObj = asset.get("assetAttributeList");
        if (attrListObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attrList = (List<Map<String, Object>>) attrListObj;
            for (Map<String, Object> attr : attrList) {
                String aName = "";
                String aVal = "";
                for (Map.Entry<String, Object> entry : attr.entrySet()) {
                    String k = entry.getKey().toLowerCase();
                    Object v = entry.getValue();
                    if (v == null || v.toString().equalsIgnoreCase("null")) continue;
                    
                    if (k.contains("name") || k.contains("label") || k.contains("key")) aName = v.toString().toLowerCase();
                    if (k.contains("value") || k.contains("val") || k.contains("data")) aVal = v.toString().trim();
                }
                
                if (!aName.isEmpty() && !aVal.isEmpty() && !aVal.equalsIgnoreCase("null")) {
                    String normName = aName.replace("_", "").replace(" ", "");
                    if (normName.contains("nms") && (normName.contains("cd") || normName.contains("code"))) {
                        nmsOltCd = aVal;
                    }
                    if (normName.contains("blkname") || normName.contains("blockname")) {
                        blkName = aVal;
                    }
                }
            }
        }

        String blockName = "";
        String excelCode = "";
        String district  = "";
        String assetNameRaw = String.valueOf(asset.getOrDefault("name", ""));
        String assetCodeRaw = String.valueOf(asset.getOrDefault("code", ""));
        
        String assetNameNorm = assetNameRaw.toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "");
        String assetCodeNorm = assetCodeRaw.toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "");
        String blkNameNorm   = blkName.toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "");
        
        List<BlockNode> combinedNodes = new ArrayList<>(masterRefData);
        combinedNodes.addAll(excelService.getCurrentData());

        String matchedAlarm = "";
        for (BlockNode node : combinedNodes) {
            String nodeName = (node.getName() != null) ? node.getName().toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "") : "";
            String nodeCode = (node.getBlockCode() != null) ? node.getBlockCode().toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "") : "";
            String nodeIp   = (node.getIp() != null) ? node.getIp().toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "") : "";
            String nodeBlk  = (node.getGpBlock() != null) ? node.getGpBlock().toUpperCase().replaceAll("[^A-Z0-9]", "").replaceAll("(.)(?=\\1)", "") : "";
            
            boolean match = (!nodeName.isEmpty() && nodeName.equals(assetNameNorm)) || 
                           (!nodeCode.isEmpty() && nodeCode.equals(assetCodeNorm)) ||
                           (!nodeIp.isEmpty()   && nodeIp.equals(assetNameNorm));
            
            if (!match && !nodeName.isEmpty() && !assetNameNorm.isEmpty()) {
                if (assetNameNorm.contains(nodeName) || nodeName.contains(assetNameNorm)) match = true;
            }
            
            if (!match && !blkNameNorm.isEmpty() && !nodeBlk.isEmpty()) {
                if (blkNameNorm.equals(nodeBlk)) match = true;
            }
            
            if (match) {
                blockName = node.getGpBlock();
                excelCode = node.getBlockCode();
                district  = node.getDistrict();
                matchedAlarm = node.getAlarm();
                break;
            }
        }
        
        Map<String, Object> row = new LinkedHashMap<>();
        
        String finalBlock = blockName; 
        if (finalBlock == null || finalBlock.isEmpty()) finalBlock = blkName; 
        if (finalBlock == null || finalBlock.isEmpty()) {
            Object geoNameObj = asset.get("geofenceName");
            if (geoNameObj != null && !geoNameObj.toString().equalsIgnoreCase("null")) {
                finalBlock = geoNameObj.toString().trim();
            }
        }
        
        String finalCode = excelCode;
        if (finalCode == null || finalCode.isEmpty()) finalCode = nmsOltCd;
        if (finalCode == null || finalCode.isEmpty()) finalCode = String.valueOf(asset.getOrDefault("code", ""));
        
        row.put("block",        (finalBlock == null || finalBlock.isEmpty()) ? "N/A" : finalBlock);
        row.put("district",     (district  == null || district.isEmpty())  ? "N/A" : district);
        row.put("assetName",    asset.get("name"));
        row.put("code",         (finalCode == null || finalCode.isEmpty() || finalCode.equalsIgnoreCase("null")) ? "N/A" : finalCode);
        row.put("healthStatus", (healthStatus == null || healthStatus.isEmpty()) ? "DOWN" : healthStatus);
        
        String finalAlarm = "";
        if ("ticket is there".equalsIgnoreCase(matchedAlarm) || "ticket is closed".equalsIgnoreCase(matchedAlarm)) {
            finalAlarm = matchedAlarm;
        }
        row.put("alarmCause", finalAlarm);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractContent(Object body) {
        if (body == null) return new ArrayList<>();
        if (body instanceof List)  return (List<Map<String, Object>>) body;
        if (body instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) body;
            if (map.containsKey("content")) return (List<Map<String, Object>>) map.get("content");
            if (map.containsKey("data")) return (List<Map<String, Object>>) map.get("data");
        }
        log.warn("HealthIndicatorService: Unexpected response body type: {}", body.getClass().getName());
        return new ArrayList<>();
    }

    private boolean hasChanged(List<Map<String, Object>> newData) {
        if (cachedData.size() != newData.size()) return true;
        for (int i = 0; i < newData.size(); i++) {
            String oldStatus = String.valueOf(cachedData.get(i).getOrDefault("healthStatus", ""));
            String newStatus = String.valueOf(newData.get(i).getOrDefault("healthStatus", ""));
            String oldName   = String.valueOf(cachedData.get(i).getOrDefault("assetName",   ""));
            String newName   = String.valueOf(newData.get(i).getOrDefault("assetName",   ""));
            if (!oldStatus.equals(newStatus) || !oldName.equals(newName)) return true;
        }
        return false;
    }
}