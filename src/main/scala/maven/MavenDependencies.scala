package maven

import sbt._
import org.apache.maven.model.{Model, Dependency, Repository}
import org.codehaus.plexus.util.WriterFactory
import org.apache.maven.model.io.xpp3.MavenXpp3Writer

trait MavenDependencies extends DefaultProject {
  override def classpathFilter = super.classpathFilter -- "*-sources.jar" -- "*-javadoc.jar"

  def localMavenRepo = Path.userHome / ".m2" / "repository"

  def checksumPolicy = ChecksumPolicy.Warn

  def snapshotUpdatePolicy = SnapshotUpdatePolicy.Daily

  private lazy val engine = new Engine(localMavenRepo.absolutePath,
                                       repositories,
                                       log,
                                       offline.value,
                                       checksumPolicy,
                                       snapshotUpdatePolicy)

  private lazy val mavenModel = {
    val m = new Model
    m.setModelVersion("4.0.0")
    m.setName(name)
    m.setGroupId(organization)
    m.setArtifactId(moduleID)
    m.setVersion(version.toString)

    mavenRepositories.foreach(m.addRepository)
    mavenDependencies.foreach(m.addDependency)

    m
  }

  private lazy val mavenRepositories: Seq[Repository] = repositories.flatMap {
    case r: MavenRepository if r.root != "http://repo1.maven.org/maven2" => {
      val repo = new Repository
      repo.setId(r.name)
      repo.setUrl(r.root)
      Some(repo)
    }
    case r => {
      println(r)
      None
    }
  }.toSeq

  private lazy val mavenDependencies: Seq[Dependency] = libraryDependencies.map {d => {
    val dependency = new Dependency()
    dependency.setGroupId(d.organization)
    dependency.setArtifactId(d.name)
    dependency.setVersion(d.revision)
    d.configurations.foreach(dependency.setScope)
    dependency
  }
  }.toSeq

  override lazy val makePom = task {
    outputPath.asFile.mkdirs()
    val pomFile = pomPath.asFile
    pomFile.createNewFile()
    val fw = WriterFactory.newXmlWriter(pomFile)
    val writer = new MavenXpp3Writer
    writer.write(fw, mavenModel)
    None
  } describedAs("Generates a POM file.")

  override lazy val update = task {
    // clean libs
    // copy stuff to whurr it should be
    engine.update(libraryDependencies)

    None
  } describedAs(BasicManagedProject.UpdateDescription)

  override lazy val cleanCache = task {
    log.info("You don't need to nuke your cache; you're not using Ivy.")
    None
  } describedAs(BasicManagedProject.CleanCacheDescription)

  override lazy val publish = task {
    log.error("Not implemented yet")
    None
  } describedAs("Deploys your artifacts to the specified repository.")

  override lazy val publishLocal = task {
    log.error("Not implemented yet")
    None
  } describedAs("Deploys your artifacts to your local repository.")
}
