import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info) with IdeaProject {
//  val maven = "org.apache.maven" % "apache-maven" % "3.0.1"
//  val mavenCore = "org.apache.maven" % "maven-core" % "3.0.1" withSources()
//  val mavenModel = "org.apache.maven" % "maven-model" % "3.0.1" withSources()
//  val mavenModelBuilder = "org.apache.maven" % "maven-model-builder" % "3.0.1" withSources()
//  val mavenEmbedder = "org.apache.maven" % "maven-embedder" % "3.0.1" withSources ()

  val mavenAntTasks = "org.apache.maven" % "maven-ant-tasks" % "2.1.1" withSources()
}
