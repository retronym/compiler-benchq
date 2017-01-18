# --- `identity` is a `bigint auto_increment`, a 64-bit integer

# --- !Ups

create table scalaVersion(
    id identity primary key,
    sha varchar(40)
);

create table compilerOption(
    id identity primary key,
    opt varchar(128)
);

create table scalaVersionCompilerOptions(
    scalaVersionId bigint,
    compilerOptionId bigint,
    foreign key (scalaVersionId) references scalaVersion (id) on delete cascade,
    foreign key (compilerOptionId) references compilerOption (id),
    primary key (scalaVersionId, compilerOptionId)
);

# --- !Downs

drop table ScalaVersion;
drop table compilerArg;
drop table scalaVersionCompilerOptions;
