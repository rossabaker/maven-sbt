import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info) with IdeaProject {
  /**
   * Publish via Ivy. (womp womp)
   */
  lazy val publishTo = Resolver.sftp("Personal Repo",
                                     "codahale.com",
                                     "/home/codahale/repo.codahale.com/") as ("codahale")
  override def managedStyle = ManagedStyle.Maven

  val mavenProject = "org.apache.maven" % "maven-project" % "2.2.1" withSources()
  val plexusUtils = "org.codehaus.plexus" % "plexus-utils" % "1.5.15" withSources()
}
