package load.datapool.todo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PoolRow {

    private long rid;
    private String text;
    private boolean locked = false;
    //private List<String> jsontext = new ArrayList<String>();
    public PoolRow(String text) {
        this.text = text;
    }


    public Long getRid() {
        return this.rid;
    }

    public void setRid(Long rid) {
        this.rid = rid;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text + text;
    }

    public boolean isLocked() {
        return this.locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }
}
