package load.datapool.prometheus;


import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;


//import io.prometheus.client.exporter.MetricsServlet;

/*
datapool_available_rows{serviceNode =, servicePort=, env=, name, availableRows=}
datapool_total_rows {serviceNode =, servicePort=, env=, name, totalRows=}
datapool_current_offset {serviceNode =, servicePort=, env=, name, currentOffset}
*/

public class Exporter {
    // Prometheus collectors
    static final CollectorRegistry c = new CollectorRegistry();
    static private String [] labelNames= new String [] {"environment","name"};
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

    public String getMetrics() throws IOException {
        availableRows.labels(new String [] {"env","demo_pool"}).inc();
        totalRows.labels(new String [] {"env","demo_pool"}).inc();
        currentOffset.labels(new String [] {"env","demo_pool"}).inc();

        Writer writer = new StringWriter();
        TextFormat.write004(writer, c.metricFamilySamples() );
        return writer.toString();

    }
}
