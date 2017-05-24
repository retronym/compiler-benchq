# --- !Ups

alter table benchmark alter column name varchar(1024);
alter table benchmark alter column name rename to command;
drop table benchmarkArgument;

# --- !Downs

create table benchmarkArgument(
    benchmarkId bigint,
    arg varchar(128),
    idx int,
    foreign key (benchmarkId) references benchmark (id) on delete cascade,
    primary key (benchmarkId, arg)
);
alter table benchmark alter column command rename to name;
alter table benchmark alter column name varchar(256);

