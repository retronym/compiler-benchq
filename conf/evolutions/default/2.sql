# --- !Ups

create table benchmark(
    id identity primary key,
    name varchar(256)
);

create table benchmarkArgument(
    benchmarkId bigint,
    arg varchar(128),
    idx int,
    foreign key (benchmarkId) references benchmark (id) on delete cascade,
    primary key (benchmarkId, arg)
);

create table compilerBenchmarkTask(
    id identity primary key,
    priority int,
    nextAction varchar(128),
    scalaVersionId bigint,
    foreign key (scalaVersionId) references scalaVersion (id)
);

create table compilerBenchmarkTaskBenchmark(
    compilerBenchmarkTaskId bigint,
    benchmarkId bigint,
    idx int,
    foreign key (compilerBenchmarkTaskId) references compilerBenchmarkTask (id) on delete cascade,
    foreign key (benchmarkId) references benchmark (id),
    primary key (compilerBenchmarkTaskId, benchmarkId)
);

# --- !Downs

drop table benchmark;
drop table benchmarkArgument;
drop table compilerBenchmarkTask;
drop table compilerBenchmarkTaskBenchmark;
