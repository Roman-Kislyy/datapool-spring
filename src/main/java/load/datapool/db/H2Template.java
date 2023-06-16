package load.datapool.db;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class H2Template extends JdbcTemplate {
    @Value("${spring.datasource.url}")
    private String url;

    @Value(value = "${spring.datasource.driverClassName}")
    private String driver = "org.h2.Driver";

    @Value(value = "${spring.datasource.username:sa}")
    private String user ="sa";

    @Value(value = "${spring.datasource.password}")
    private String pwd ="password";

    @Value("${h2.maxIdle}")
    private int maxIdle;

    @Autowired
    public void init(){
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName(driver);
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(pwd);
        dataSource.setMaxIdle(maxIdle);
        setDataSource(dataSource);
    }

}
