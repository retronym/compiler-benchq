This tool watches scala/scala for new commits and starts a run of our benchmark suite
for every merge commit. The UI is at [scala-ci.typesafe.com/benchq](https://scala-ci.typesafe.com/benchq).

## Admin

### CLI

Stop the service, log in as `ubuntu`:
```
ssh ubuntu@ec2-52-53-208-5.us-west-1.compute.amazonaws.com
sudo sv down benchq
```

Use the sbt console (todo: for some reason, I saw all debug-level logs in the console):
```
ssh benchq@ec2-52-53-208-5.us-west-1.compute.amazonaws.com
cd compiler-benchq
sbt -Dconfig.file=/home/benchq/benchq-data/conf/application.conf -Dlogger.file=/home/benchq/benchq-data/conf/logback.xml console

scala> knownRevisionService.lastKnownRevision(Branch.v2_12_x)
res0: Option[benchq.model.KnownRevision] = Some(KnownRevision(2.12.x,cbf7daa57d70ccacf8cfc7c2f4a7c0e81e1c773a))

scala> q // to shutdown the application
scala> :q

./scripts/dba /home/benchq/benchq-data/db
sql> select * from knownRevision;
BRANCH | REVISION
2.11.x | 011cc7ec86105640a6d606998f769986630fb62a
2.13.x | 6ebf4140561e2564ec7ed30c3f6d9a2dea92be01
2.12.x | cbf7daa57d70ccacf8cfc7c2f4a7c0e81e1c773a
(3 rows, 1 ms)

sql> quit
```

Restart the service as `ubuntu`:
```
ssh ubuntu@ec2-52-53-208-5.us-west-1.compute.amazonaws.com
sudo sv up benchq
```

### Deployment

  * Running on `ssh benchq@ec2-52-53-208-5.us-west-1.compute.amazonaws.com`, port `8084`.
  * Exposed via the [scala-ci reverse proxy](https://github.com/scala/scala-jenkins-infra/commit/0bd0525379ebb024cf34e13e1a5b9da59209e3f1)

#### Sever admin

`ssh ubuntu@ec2-52-53-208-5.us-west-1.compute.amazonaws.com`

Service by [runit](http://smarden.org/runit/), here's a [tutorial](http://kchard.github.io/runit-quickstart/).
Script at `/etc/service/benchq/run` (uses a fat jar by [sbt-assembly](https://github.com/sbt/sbt-assembly)):

```
#!/bin/sh -e
exec 2>&1
exec chpst -u benchq -U benchq \
  env HOME=/home/benchq \
  java \
    -Dhttp.port=8084 \
    -Dplay.evolutions.db.default.autoApply=true \
    -Dpidfile.path=/dev/null \
    -Dconfig.file=/home/benchq/benchq-data/conf/application.conf \
    -Dlogger.file=/home/benchq/benchq-data/conf/logback.xml \
    -jar /home/benchq/compiler-benchq/target/scala-2.11/benchq.jar
```

#### Push to deploy

  * Ordinary (not "bare") clone at `/home/benchq/compiler-benchq`
  * Allow [pushes to update the index](http://stackoverflow.com/questions/3221859/cannot-push-into-git-repository/28257982#28257982):
    `git config receive.denyCurrentBranch updateInstead`
  * [`post-receive` script](https://github.com/scala/compiler-benchq/blob/master/scripts/post-receive)
    installed with `ln -s ../../scripts/post-receive .git/hooks/post-receive`
  * Add `prod` remote to local checkout: `git remote add prod ssh://benchq@ec2-52-53-208-5.us-west-1.compute.amazonaws.com/home/benchq/compiler-benchq`
  * `git push prod master`

#### Logs

```
ssh benchq@ec2-52-53-208-5.us-west-1.compute.amazonaws.com
tail -n 100 -f /home/benchq/benchq-data/logs/application.log
```
