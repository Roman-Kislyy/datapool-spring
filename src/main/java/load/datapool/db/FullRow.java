package load.datapool.db;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FullRow {

    int rid;
    String text;
    String searchkey;
    boolean locked;
}
