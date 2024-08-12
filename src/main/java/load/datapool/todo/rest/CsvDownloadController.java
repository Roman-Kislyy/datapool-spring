package load.datapool.todo.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import load.datapool.db.FullRow;
import load.datapool.db.H2Template;
import load.datapool.prometheus.Exporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Value(value = "${db.download.batch-rows:10000}")
    private int MAX_SELECT_BATCH;

    @Value(value = "${db.download.max-rows:500000}")
    private int MAX_SELECT_ROWS;
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
                                              @RequestParam(value = "delimiter", defaultValue = ",") String delim,
                                              @RequestParam(value = "all_columns", defaultValue = "false") boolean allColumns,
                                              @RequestParam(value = "batch_size", defaultValue = "0") int batchSize,
                                              @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Instant start = Instant.now();
        String sql = "";
        // Метод сильно просаживает производительность поэтому выставляем лимиты
        int localBatchSize = MAX_SELECT_BATCH;
        int localOffset = 0;
        int exportedRowsCount = 0;

        if (batchSize > 0 && batchSize <MAX_SELECT_BATCH){
            localBatchSize = batchSize;
        }
        if (offset > 0){
            localOffset = offset;
        }
        if (allColumns){
            sql = "SELECT RID as rid, TEXT as text, SEARCHKEY as searchkey, LOCKED as locked FROM " + env + "." + pool;
        }else {
            sql = "SELECT TEXT FROM " + env + "." + pool;
        }

        Set<String> headersSet = new LinkedHashSet<>();
        List<List<String>> rows = new ArrayList<>();

        while (true) {
            // Не делайте здесь LIMIT и OFFSET от h2db. Он работает плохо, замедляется к концу таблицы очень сильно
            String offset_sql = sql + " WHERE RID >= " + localOffset + " LIMIT " + localBatchSize ;
            List<FullRow> dataList = jdbcOperations.query(offset_sql, (rs, rowNum) -> {
                FullRow fullRow = new FullRow();
                fullRow.setText(rs.getString("text"));
                if (allColumns){
                    fullRow.setRid(rs.getInt("rid"));
                    fullRow.setSearchkey(rs.getString("searchkey"));
                    fullRow.setLocked(rs.getBoolean("locked"));
                }
                return fullRow;
            });

            if (dataList.isEmpty()) {
                break; // Выход из цикла, если нет больше данных
            }

            for (FullRow data : dataList) {
                try {
                    List<String> row = new ArrayList<>();
                    // Сначала технические поля, если запрашивались
                    if (allColumns){
                        headersSet.add("rid");
                        headersSet.add("searchkey");
                        headersSet.add("locked");
                        row.add(String.valueOf(data.getRid()));
                        row.add(data.getSearchkey());
                        row.add(String.valueOf(data.isLocked()));
                    }
                    // Преобразуем text в json
                    try {
                        JsonNode jsonNode = objectMapper.readTree(data.getText());
                        if (!jsonNode.isObject()){
                            // Проверка типа корневого узла, чтобы дальше не было ошибок
                            throw new JsonProcessingException("JSON должен быть объектом или массивом"){};
                        }
                        // Обработка успешного парсинга
                        jsonNode.fieldNames().forEachRemaining(fieldName -> {
                            headersSet.add(fieldName);
                            row.add(jsonNode.get(fieldName).asText());
                        });
                    } catch (JsonProcessingException e) {
                        // Обработка исключения. То есть, если в поле text простой текст
                        headersSet.add("raw_text");
                        row.add(data.getText());
                        exp.incRequestsAndLatency(env, pool, "download-csv", "json parsing failed", start);
                    }

                    // Можно было здесь сделать сохранение во временный файл, вместо массива. На подумать
                    rows.add(row);
                } catch (IOException e) {
                    // Обработка ошибок парсинга JSON
                    e.printStackTrace();
                    exp.incRequestsAndLatency(env, pool, "download-csv", "IO parsing failed", start);
                }
            }
            exportedRowsCount += dataList.size();
            logger.info("Total exported rows: {}. Offset {}, batch {}.", exportedRowsCount, localOffset, localBatchSize);
            localOffset += localBatchSize;
            if (exportedRowsCount >= MAX_SELECT_ROWS) {
                break; // Выход из цикла, достигли лимита выгрузки
            }
        }

        List<String> headersList = new ArrayList<>(headersSet);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8))) {
            // Если по какой-то причине ничего не нашлось, надо сообщить пользователю
            if (exportedRowsCount <= 0){
                writer.println("No data found!");
                //defaultResponseCode = HttpStatus.NOT_FOUND;
            }else{
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
