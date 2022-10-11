package load.datapool.service;

import org.springframework.stereotype.Service;

@Service
public class TableService {

    public final String delimiter = ".";

    public String fullName(String schema, String table) {
        return (schema + delimiter + table).toUpperCase();
    }

}
