--https://www.tutorialspoint.com/h2_database/h2_database_create.htm

create schema if not exists LOAD;
CREATE USER IF NOT EXISTS VIEWER PASSWORD 'viewer';
GRANT SELECT ON SCHEMA LOAD TO VIEWER;