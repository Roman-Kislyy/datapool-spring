package load.datapool.db;

import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import org.springframework.jdbc.datasource.embedded.ConnectionProperties;
import org.springframework.stereotype.Component;

//@Configuration
//@PropertySource("classpath:application.properties") //Dont work
public class H2DataSourse extends BasicDataSource {
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyConfigInDev() {
        return new PropertySourcesPlaceholderConfigurer();
    }
    //@Value("jdbc:h2:file:./testdb")//:jdbc:h2:file:./testdb
    private String url ="jdbc:h2:file:./testdb;LOG=0;CACHE_SIZE=1048576;LOCK_MODE=0;UNDO_LOG=0;PAGE_SIZE=512";

    //@Value(value = "${spring.datasource.driverClassName}")
    private String driver = "org.h2.Driver";

    //@Value(value = "${spring.datasource.username:sa}")
    private String user ="sa";

    //@Value(value = "${spring.datasource.password}")
    private String pwd ="password";

    private BasicDataSource dataSource;

    public H2DataSourse (){
        dataSource = new BasicDataSource();
        dataSource.setDriverClassName(driver);
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(pwd);
        dataSource.setMaxIdle(100);

        
    }
    public BasicDataSource getDataSource (){
        return this.dataSource;
    }
}
