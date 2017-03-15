# --- !Ups

alter table scalaVersion add column repo varchar(128) after id;
update scalaVersion set repo='scala/scala' where repo is null;

# --- !Downs

alter table scalaVersion drop column repo;
