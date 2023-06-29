# datapool-spring
Service for creating and utilizing test data pools via REST API.

# View. How it's work

![](src/main/resources/static/schema.jpg)


# Start service

cd ./bin/

java -jar datapool-service-<version>.jar

# Configure

./bin/application.properties

# Documentation

- [Docs htmlpreview](https://htmlpreview.github.io/?https://github.com/Roman-Kislyy/datapool-spring/blob/master/src/main/resources/static/index.html)
- [Local](http://localhost:8080/) You can open web documentaion everytime. Simple open http://localhost:8080/ where datapool has been started.
- [Or open resourse](src/main/resources/static/index.html)

# Build with maven

mvn clean verify

# Useful links H2 DB

http://www.h2database.com/html/tutorial.html#using_server

https://www.tutorialspoint.com/h2_database/h2_database_create.htm

# Useful links Spring REST

https://spring.io/guides/gs/rest-service/

https://alexkosarev.name/2019/03/08/rest-api-with-spring/

https://www.baeldung.com/spring-boot-h2-database