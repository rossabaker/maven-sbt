import sbt._

class MavenSbt(info: ProjectInfo) extends PluginProject(info)
                                          with IdeaProject
                                          with maven.MavenDependencies {

  lazy val publishTo = Resolver.sftp("Personal Repo",
                                     "codahale.com",
                                     "/home/codahale/repo.codahale.com/")

  val mavenAetherProvider = "org.apache.maven" % "maven-aether-provider" % "3.0.1"
  val aetherConnectorWagon = "org.sonatype.aether" % "aether-connector-wagon" % "1.8"
  
  val wagonVersion = "1.0-beta-7"
  val wagonFile = "org.apache.maven.wagon" % "wagon-file" % wagonVersion
  val wagonHttpShared = "org.apache.maven.wagon" % "wagon-http-shared" % wagonVersion
  val wagonHttp = "org.apache.maven.wagon" % "wagon-http-lightweight" % wagonVersion
  val wagonSshCommon = "org.apache.maven.wagon" % "wagon-ssh-common" % wagonVersion
  val wagonSsh = "org.apache.maven.wagon" % "wagon-ssh-external" % wagonVersion
}
