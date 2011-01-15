package maven

import sbt._
import org.apache.maven.model.{Model, Dependency, Repository}
import org.codehaus.plexus.util.WriterFactory
import org.apache.maven.model.io.xpp3.MavenXpp3Writer

trait MavenDependencies extends BasicManagedProject {
  override def classpathFilter = super.classpathFilter -- "*-sources.jar" -- "*-javadoc.jar"

  override def managedStyle = ManagedStyle.Ivy // just enforce this to make things easier

  // We lose the POM as an artifact when managedStyle != Maven, but we still want it.
  override def artifacts = super.artifacts ++ List(Artifact(artifactID, "pom", "pom"))

  def localMavenRepo = Path.userHome / ".m2" / "repository"

  def checksumPolicy: ChecksumPolicy = ChecksumPolicy.Warn

  def snapshotUpdatePolicy: SnapshotUpdatePolicy = SnapshotUpdatePolicy.Daily

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

  override def makePomAction = task {
    outputPath.asFile.mkdirs()
    val pomFile = pomPath.asFile
    pomFile.createNewFile()
    val fw = WriterFactory.newXmlWriter(pomFile)
    val writer = new MavenXpp3Writer
    writer.write(fw, mavenModel)
    None
  } describedAs("Generates a POM file.")

  override def updateAction = task {
    log.info("Updating dependencies...")
    engine.update(this)
    None
  } describedAs(BasicManagedProject.UpdateDescription)

  override def cleanCacheAction = task {
    log.info("You don't need to nuke your cache; you're not using Ivy.")
    None
  } describedAs(BasicManagedProject.CleanCacheDescription)

  override def publishAction = task {
    log.info("Publishing...")
    engine.deploy(this)
    None
  } describedAs("Deploys your artifacts to your local repository.") dependsOn((List(makePom) ++ packageToPublishActions): _*)

  override def publishLocalAction = task {
    log.info("Installing locally...")
    engine.install(this)
    None
  } describedAs("Deploys your artifacts to your local repository.") dependsOn((List(makePom) ++ packageToPublishActions): _*)

  lazy val dependencyTree = dependencyTreeAction
  def dependencyTreeAction = task {
    engine.printDependencies(this)
    None
  } describedAs ("Prints a tree of your project's dependencies.")

  // TODO: 1/9/11 <coda> -- override deliver
  // TODO: 1/9/11 <coda> -- override deliverLocal
}
