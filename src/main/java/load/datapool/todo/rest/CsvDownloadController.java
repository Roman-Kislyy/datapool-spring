package load.datapool.todo.rest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import load.datapool.db.H2Template;
import load.datapool.prometheus.Exporter;
import load.datapool.service.LockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("api/v1")
public class CsvDownloadController {
    private final Logger logger = LoggerFactory.getLogger(CsvDownloadController.class);
    private final H2Template jdbcOperations;
    private final Exporter exp;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    public CsvDownloadController(H2Template jdbcOperations, Exporter exp) {
        this.jdbcOperations = jdbcOperations;
        this.exp = exp;
    }

    @GetMapping("/download/csv")
    public ResponseEntity<byte[]> downloadCsv(@RequestParam(value = "env", defaultValue = "load") String env,
                                              @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                              @RequestParam(value = "delimiter", defaultValue = ",") String delim) {
        Instant start = Instant.now();
        String sql = "SELECT text FROM " + env + "." + pool;

        List<String> jsonDataList = jdbcOperations.queryForList(sql, String.class);

        Set<String> headersSet = new LinkedHashSet<>();
        List<List<String>> rows = new ArrayList<>();

        for (String jsonData : jsonDataList) {
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonData);
                List<String> row = new ArrayList<>();

                jsonNode.fieldNames().forEachRemaining(fieldName -> {
                    headersSet.add(fieldName);
                    row.add(jsonNode.get(fieldName).asText());
                });

                rows.add(row);
            } catch (IOException e) {
                // Обработка ошибок парсинга JSON
                e.printStackTrace();
                exp.incRequestsAndLatency(env, pool, "download-csv", "json parsing failed", start);
            }
        }

        List<String> headersList = new ArrayList<>(headersSet);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8))) {

            // Запись заголовка CSV
            writer.println(String.join(delim, headersList));

            // Запись данных
            for (List<String> row : rows) {
                // Дополнение строки пустыми значениями, если нужно
                while (row.size() < headersList.size()) {
                    row.add("");
                }
                writer.println(String.join(delim, row));
            }
            writer.flush();
            byte[] csvBytes = byteArrayOutputStream.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + env + "." + pool + ".csv");
            headers.add(HttpHeaders.CONTENT_TYPE, "text/csv");
            headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(csvBytes.length));
            exp.incRequestsAndLatency(env, pool, "download-csv", "File prepared", start);
            return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            exp.incRequestsAndLatency(env, pool, "download-csv", "Some server error", start);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}
