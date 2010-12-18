import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info) with IdeaProject {
  val mavenAntTasks = "org.apache.maven" % "maven-ant-tasks" % "2.1.1" withSources()
}
