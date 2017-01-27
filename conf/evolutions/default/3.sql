# --- !Ups

create table knownRevision(
    branch varchar(128) primary key,
    revision varchar(40)
);

create table defaultBenchmark(
    id identity primary key,
    branch varchar(128),
    benchmarkId bigint,
    foreign key (branch) references knownRevision (branch) on delete cascade
);

# --- !Downs

drop table knownRevision;
drop table defaultBenchmark;
