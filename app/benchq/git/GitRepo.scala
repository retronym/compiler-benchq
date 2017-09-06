package benchq
package git

import benchq.model.{Branch, KnownRevision}
import better.files._

import scala.sys.process._
import scala.util.Try

class GitRepo(config: Config) {
  import config.gitRepo._

  val repoUrl = "https://github.com/scala/scala.git"
  def checkoutDirectory = File(checkoutLocation)
  def checkoutDirectoryJ = checkoutDirectory.toJava

  private def cloneIfNonExisting(): Unit = {
    if (!checkoutDirectory.exists)
      Process(s"git clone $repoUrl ${checkoutDirectory.name}", checkoutDirectory.parent.toJava).!
  }

  private def fetchOrigin(doIt: Boolean): Unit = if (doIt) {
    cloneIfNonExisting()
    Process("git fetch -f origin --tags", checkoutDirectoryJ).!
  }

  def newMergeCommitsSince(knownRevision: KnownRevision, fetch: Boolean = true): List[String] = {
    fetchOrigin(fetch)
    // --first-parent to pick only merge commits, and direct commits to the branch
    // http://stackoverflow.com/questions/10248137/git-how-to-list-commits-on-this-branch-but-not-from-merged-branches
    Process(
      s"git log --first-parent --pretty=format:%H ${knownRevision.revision}..origin/${knownRevision.branch.entryName}",
      checkoutDirectoryJ).lineStream.toList
  }

  def branchesContaining(sha: String, fetch: Boolean = true): Try[List[Branch]] = {
    fetchOrigin(fetch)
    val originPrefix = "origin/"
    Try {
      // Throws an exception if `sha` is not known
      val containingBranches =
        Process(s"git branch -r --contains $sha", checkoutDirectoryJ).lineStream
          .map(_.trim)
          .collect({
            case s if s.startsWith(originPrefix) => s.substring(originPrefix.length)
          })
          .toSet
      Branch.sortedValues.filter(b => containingBranches(b.entryName))
    }
  }

  def isMerged(sha: String, fetch: Boolean = true): Boolean =
    branchesContaining(sha, fetch).map(_.nonEmpty).getOrElse(false)

  def shaForTag(tag: String, fetch: Boolean = true): Try[String] = {
    fetchOrigin(fetch)
    Try(Process(s"git rev-list -n 1 $tag", checkoutDirectoryJ).lineStream.head)
  }

  def tagForSha(sha: String, fetch: Boolean = true): Option[String] = {
    fetchOrigin(fetch)
    Try(Process(s"git describe --tag --exact-match $sha", checkoutDirectoryJ).lineStream.head).toOption
  }

  def commitDateMillis(sha: String, fetch: Boolean = true): Option[Long] = {
    fetchOrigin(fetch)
    // %ct gives a unix timestamp (in seconds)
    Try(Process(s"git log -n 1 --pretty=format:%ct $sha", checkoutDirectoryJ).lineStream.head.toLong * 1000).toOption
  }
}
