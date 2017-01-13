package benchq
package git

import benchq.queue.TaskQueue

class GitRepo(db: DB, queue: TaskQueue) {
  def checkNewCommits(): Unit = ()
}
