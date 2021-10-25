package load.datapool.db;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
//@PropertySource("classpath:application.properties") //Dont work
public class H2Template extends JdbcTemplate {

    //@Value("jdbc:h2:file:./testdb")//:jdbc:h2:file:./testdb
    private String url ="jdbc:h2:file:./testdb;LOG=0;CACHE_SIZE=1048576;LOCK_MODE=0;UNDO_LOG=0;PAGE_SIZE=512";

    //@Value(value = "${spring.datasource.driverClassName}")
    private String driver = "org.h2.Driver";

    //@Value(value = "${spring.datasource.username:sa}")
    private String user ="sa";

//    @Value(value = "${spring.datasource.password}")
    private String pwd ="password";

    @Value("${h2.maxIdle}")
    private int maxIdle;

    public H2Template(){
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName(driver);
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(pwd);
        dataSource.setMaxIdle(maxIdle);
        setDataSource(dataSource);
    }

    public void init() {
        System.out.println(maxIdle);
    }
}
