# --- !Ups

alter table benchmark add column daily int after command;
update benchmark set daily=0 where daily is null;

# --- !Downs

alter table benchmark drop column daily;
