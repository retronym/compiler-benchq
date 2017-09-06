# --- !Ups

create table lastExecutedBenchmark(
    benchmarkId bigint,
    branch varchar(128),
    sha varchar(40),
    commitTime bigint,
    foreign key (benchmarkId) references benchmark (id) on delete cascade,
    primary key (benchmarkId, branch)
);

# --- !Downs

drop table lastExecutedBenchmark;
