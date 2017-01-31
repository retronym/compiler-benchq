# --- !Ups

drop table defaultBenchmark;

create table defaultBenchmark(
    id identity primary key,
    branch varchar(128),
    benchmarkId bigint,
    foreign key (benchmarkId) references benchmark (id) on delete cascade
);

# --- !Downs
