import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info) with IdeaProject {
  val mavenProject = "org.apache.maven" % "maven-project" % "2.2.1" withSources()
  val plexusUtils = "org.codehaus.plexus" % "plexus-utils" % "1.5.15" withSources()
}
