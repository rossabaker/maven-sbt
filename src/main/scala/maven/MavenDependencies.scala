package maven

import scala.collection.mutable
import sbt._
import sbt.FileUtilities.copyFile
import org.apache.tools.ant.{Project => AntProject}
import org.apache.tools.ant.types.FileSet
import org.apache.tools.ant.types.resources.FileResource
import org.apache.maven.project.MavenProject
import org.apache.maven.model.{Repository, Model, Exclusion, Dependency}
import org.apache.maven.artifact.ant.{InstallTask, Pom, WritePomTask, DependenciesTask}
import org.codehaus.plexus.embed.Embedder
import org.codehaus.plexus.{DefaultPlexusContainer, PlexusContainer}
import java.io.{StringReader, InputStreamReader, File}
import java.net.{URL, URLClassLoader}
import org.codehaus.classworlds.{DefaultClassRealm, ClassRealm, ClassWorld}

// TODO: 12/17/10 <coda> -- fix logging

trait MavenDependencies extends DefaultProject {
  sealed trait ArtifactType
  object ArtifactType {
    case object Jar extends ArtifactType
    case object Source extends ArtifactType
    case object Doc extends ArtifactType
  }

  override def classpathFilter = super.classpathFilter -- "*-sources.jar" -- "*-javadoc.jar"

  private lazy val mavenContainer = {
    val container = new DefaultPlexusContainer()
    val loader = ClasspathUtilities.toLoader("project" / "plugins" / "lib_managed" ** "*.jar")
    val world = new ClassWorld()
    val realm = world.newRealm("maven", loader)
    container.setCoreRealm(realm)
    container.setClassWorld(world)
    // why the fuck don't you work?
    container.initialize()
    container.start()
    container
  }

  private lazy val mavenPom = {
    val p = new Pom
    p.setMavenProject(mavenProject)
    p
  }

  private lazy val mavenProject = {
    new MavenProject(mavenModel)
  }

  private lazy val mavenModel = {
    val m = new Model
    m.setVersion("4.0.0")
    m.setGroupId(organization)
    m.setArtifactId(name)
    m.setVersion(version.toString)

    mavenRepositories.foreach(m.addRepository)
    mavenDependencies.foreach(m.addDependency)

    m
  }

  private lazy val scalaLibraryExclusion = {
    val e = new Exclusion
    e.setGroupId("org.scala-lang")
    e.setArtifactId("scala-library")
    e
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
    dependency.addExclusion(scalaLibraryExclusion)
    d.configurations.foreach(dependency.setScope)
    dependency
  }
  }.toSeq

  private lazy val antProject = {
    val p = new AntProject
    p.addReference(classOf[PlexusContainer].getName, mavenContainer)
    p
  }

  private def selectDependencies(scope: String, artifactTypes: Set[ArtifactType]) = {
    def appendFiles(filesetId: String, deps: mutable.ArrayBuffer[File]) {
      val ref = antProject.getReference(filesetId)
      if (ref != null) {
        val iter = ref.asInstanceOf[FileSet].iterator
        while (iter.hasNext) {
          val resource = iter.next.asInstanceOf[FileResource]
          deps += resource.getFile
        }
      }
    }

    val jarFilesetId = "sbt-deps-jars"
    val sourceFilesetId = "sbt-deps-source"
    val docFilesetId= "sbt-docs-source"

    val task = new DependenciesTask
    task.addPom(mavenPom)
    task.setProject(antProject)

    for (t <- artifactTypes) {
      t match {
        case ArtifactType.Jar => task.setFilesetId(jarFilesetId)
        case ArtifactType.Source => task.setSourcesFilesetId(sourceFilesetId)
        case ArtifactType.Doc => task.setJavadocFilesetId(docFilesetId)
      }
    }

    task.setScopes(scope)

    task.execute()
    val deps = new mutable.ArrayBuffer[File]
    appendFiles(jarFilesetId, deps)
    appendFiles(sourceFilesetId, deps)
    appendFiles(docFilesetId, deps)
    deps.toList
  }

  private def syncDeps(files: List[File], dir: File) {
    dir.mkdirs()

    val existingFiles = Map() ++ dir.listFiles.toList.map { f => f.getName -> f }
    val newFiles = Map() ++ files.map { f => f.getName -> f }
    val filesToCopy = files.filter { f => !existingFiles.contains(f.getName) }
    val filesToDelete = dir.listFiles.toList.filter { f => !newFiles.contains(f.getName) }

    for (file <- filesToDelete) {
      log.debug("Deleting %s".format(file))
      file.delete()
    }

    for (file <- filesToCopy) {
      copyFile(file, new File(dir.getAbsolutePath + File.separator + file.getName), log)
    }

    println()
  }


  def updateWithMaven(artifactTypes: ArtifactType*): Option[String] = {
    try {
      log.info("Checking dependencies...")

      val compileDeps = selectDependencies("compile", Set() ++ artifactTypes)
      syncDeps(compileDeps, (managedDependencyPath / "compile").asFile)

      val testDeps = selectDependencies("test", Set() ++ artifactTypes)
      syncDeps(testDeps, (managedDependencyPath / "test").asFile)

      None
    } catch {
      case e: Exception => {
        log.error(e.getMessage)
        Some("update failed")
      }
    }
  }

  // TODO: 12/17/10 <coda> -- publish
  // TODO: 12/17/10 <coda> -- publish-local

  override protected def publishLocalAction = task {
    val task = new InstallTask
    task.addPom(mavenPom)
    task.setProject(antProject)

    for (artifact <- artifacts) {
      val attachment = task.createAttach()
      artifact.classifier.foreach(attachment.setClassifier)
      attachment.setType(artifact.`type`)

      val jarName = artifact.classifier match {
        case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
        case None => "%s-%s.jar".format(artifact.name, version.toString)
      }
      attachment.setFile((outputPath / jarName).asFile)
    }

    task.execute()
    None
  } dependsOn(packageToPublishActions: _*)

  override protected def updateAction = task {
    updateWithMaven(ArtifactType.Jar)
  }

  override def makePomAction = task {
    val t = new WritePomTask
    outputPath.asFile.mkdirs()
    val pomFile = pomPath.asFile
    pomFile.createNewFile()
    t.writeModel(mavenModel, pomFile)
    None
  } dependsOn(packageToPublishActions:_*)

  def updateSourcesAction = task {
    updateWithMaven(ArtifactType.Jar, ArtifactType.Source)
  }
  lazy val updateSources = updateSourcesAction
}