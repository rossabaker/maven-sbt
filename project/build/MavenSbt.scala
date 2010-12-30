import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info) with IdeaProject {
  /**
   * Publish via Ivy. (womp womp)
   */
  lazy val publishTo = Resolver.sftp("Personal Repo",
                                     "codahale.com",
                                     "/home/codahale/repo.codahale.com/") as ("codahale")
  override def managedStyle = ManagedStyle.Maven

  val mavenVersion = "3.0.1"
  val mavenAetherProvider = "org.apache.maven" % "maven-aether-provider" % mavenVersion withSources()
  val mavenModel = "org.apache.maven" % "maven-model" % mavenVersion withSources()
  
  val aetherVersion = "1.8"
  val aetherApi = "org.sonatype.aether" % "aether-api" % aetherVersion withSources()
  val aetherImpl = "org.sonatype.aether" % "aether-impl" % aetherVersion withSources()
  val aetherSpi = "org.sonatype.aether" % "aether-spi" % aetherVersion withSources()
  val aetherUtil = "org.sonatype.aether" % "aether-util" % aetherVersion withSources()
  val aetherConnectorWagon = "org.sonatype.aether" % "aether-connector-wagon" % aetherVersion withSources()

  val wagonVersion = "1.0-beta-7"
  val wagonFile = "org.apache.maven.wagon" % "wagon-file" % wagonVersion withSources()
  val wagonHttp = "org.apache.maven.wagon" % "wagon-http-lightweight" % wagonVersion withSources()
  // TODO: 12/29/10 <coda> -- add sftp
  // TODO: 12/29/10 <coda> -- add ssh
}
