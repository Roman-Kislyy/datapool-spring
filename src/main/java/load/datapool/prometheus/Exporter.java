package load.datapool.prometheus;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;
import load.datapool.db.H2DataSourse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Exporter {
    // Prometheus collectors
    static final CollectorRegistry c = new CollectorRegistry();
    static private String [] labelNames= new String [] {"host", "port", "environment","name"};
    static private String [] labelNamesExt= new String [] {"host", "port", "environment","name","method"};

    static final Gauge availableRows = Gauge.build()
            .name("datapool_available_rows")
            .labelNames(labelNames)
            .help("Available no locked rows count.").register(c);

    static final Gauge totalRows = Gauge.build()
            .name("datapool_total_rows")
            .labelNames(labelNames)
            .help("Total rows count.").register(c);

    static final Gauge currentOffset = Gauge.build()
            .name("datapool_current_offset")
            .labelNames(labelNames)
            .help("Current sequence position.").register(c);

    static final Counter latency = Counter.build()
            .name("datapool_sum_latency")
            .labelNames(labelNamesExt)
            .help("Self response time (nano sec). Sum for requests by method").register(c);

    static final Counter requestsCount = Counter.build()
            .name("datapool_requests_count")
            .labelNames(labelNamesExt)
            .help("Count read values requests").register(c);

    //Other attributes
    private String host = "undefined";

    @Value(value = "${server.port:1}")
    private int port;
    static private long nextCalulationTime = 0;
    static private long calculationDelay = 15000; //15 sec

    private final JdbcOperations jdbcOperations;
    public boolean isCalcAVRows = false; //getAvailableRows


    public Exporter(){
        jdbcOperations = new JdbcTemplate(new H2DataSourse().getDataSource());
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            System.err.println("Failed to get host name");
        }
    }

    public String getMetrics() throws IOException {
        if (nextCalulationTime < System.currentTimeMillis() ) {
            this.setMetrics();
        }
        Writer writer = new StringWriter();
        TextFormat.write004(writer, c.metricFamilySamples() );
        return writer.toString();
    }

    private void  saveMetrics (String env, String pool, int totalCnt, int availCnt, int seqOffset){

        if (totalCnt >= 0) {totalRows.labels(new String [] {host,String.valueOf(port),env,pool}).set(totalCnt);}
        if (seqOffset >= 0) {currentOffset.labels(new String [] {host,String.valueOf(port),env,pool}).set(seqOffset);}
        if (availCnt >= 0) {availableRows.labels(new String [] {host,String.valueOf(port),env,pool}).set(availCnt);}


    }

    public void setMetrics (){

        String sql = "select table_schema, table_name, row_count_estimate as row_count, current_value\n" +
                "        from INFORMATION_SCHEMA.TABLES t\n" +
                "        join  INFORMATION_SCHEMA.SEQUENCES s on t.table_schema = s.sequence_schema and sequence_name = substr('SEQ_'||t.table_name, 0, 25)||'_RID'\n" +
                "        where table_schema not in ('INFORMATION_SCHEMA', 'PUBLIC')";
        try {
            List<String> pools = new ArrayList<>();

            List<Map<String, Object>> rows = jdbcOperations.queryForList(sql);

            for (Map row : rows) {
                saveMetrics (
                        (String) row.get ("table_schema"),
                        (String) row.get ("table_name"),
                        ((Long) row.get ("row_count")).intValue(),getAvailableRows ((String) row.get ("table_schema"),  (String) row.get ("table_name")),
                        ((Long) row.get ("current_value")).intValue());
            }
            nextCalulationTime = System.currentTimeMillis() + calculationDelay;
        } catch (EmptyResultDataAccessException e){
        }
    }
    public void increaseRequests(String env, String pool,String method){  //+1 increment got get requests metric
        requestsCount.labels(new String [] {host,String.valueOf(port),env,pool,method }).inc();
    }

    public void increaseLatency(String env, String pool, String method, Instant start){  //Save response time
        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        latency.labels(new String [] {host,String.valueOf(port),env,pool,method }).inc(timeElapsed.toNanos()/1000 );
    }
    private int getAvailableRows (String schema, String pool){
        if (!isCalcAVRows) return -1;
        return ((Long)this.jdbcOperations.queryForObject("SELECT COUNT (locked) FROM " + schema + "." +pool + "  where locked != true", Long.class)).intValue();
    }
}
