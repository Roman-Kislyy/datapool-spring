--https://www.tutorialspoint.com/h2_database/h2_database_create.htm
create table if not exists todo
(
  id           varchar(36) primary key,
  date_created timestamp not null default now(),
  done         boolean   not null default false,
  task         varchar(255)
);

create schema if not exists load;
/*drop table if exists load.testpool;
create table if not exists load.testpool
(
  rid           bigint primary key,
  text        varchar(32000),
  locked         boolean   not null default false
);
CREATE INDEX load.testpool_rid ON  load.testpool (rid);
insert into load.testpool (rid,text,locked) values (1,'fio,passport', false);

 */