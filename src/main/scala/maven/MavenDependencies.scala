package maven

import scala.collection.mutable
import java.io.File
import sbt._
import sbt.Process._
import sbt.FileUtilities.copyFile
import org.apache.tools.ant.{Project => AntProject}
import org.apache.tools.ant.types.FileSet
import org.apache.tools.ant.types.resources.FileResource
import org.apache.maven.project.MavenProject
import org.apache.maven.model.{Repository, Model, Dependency}
import org.apache.maven.artifact.ant.{Pom, DependenciesTask}
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.codehaus.plexus.util.WriterFactory

// TODO: 12/17/10 <coda> -- fix logging

trait MavenDependencies extends DefaultProject {
  sealed trait ArtifactType
  object ArtifactType {
    case object Jar extends ArtifactType
    case object Source extends ArtifactType
    case object Doc extends ArtifactType
  }

  override def classpathFilter = super.classpathFilter -- "*-sources.jar" -- "*-javadoc.jar"

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

  private lazy val mavenDependencies: Seq[Dependency] = libraryDependencies.map { d =>
    val dependency = new Dependency()
    dependency.setGroupId(d.organization)
    dependency.setArtifactId(d.name)
    dependency.setVersion(d.revision)
    d.configurations.foreach(dependency.setScope)
    dependency
  }.filter { d => d.getGroupId != "org.scala-lang" && d.getArtifactId != "scala-library" }.toSeq

  private lazy val antProject = new AntProject

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
      val dest = new File(dir.getAbsolutePath + File.separator + file.getName)
      log.debug("Copying %s to %s".format(dest))
      copyFile(file, dest, log)
    }

    println()
  }


  def updateWithMaven(artifactTypes: ArtifactType*): Option[String] = {
    try {
      log.info("Checking dependencies...")

      log.debug("Detected dependencies: " + mavenDependencies)

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

  override protected def publishAction = task {
    val repo = reflectiveRepositories.get(BasicManagedProject.PublishToName).getOrElse(error("No repository to publish to was specified"))
    val repoId = repo.name
    val repoUrl = repo match {
        // TODO: 12/19/10 <coda> -- add support for URLRepository
        // TODO: 12/19/10 <coda> -- add support for FileRepository
      case r: MavenRepository => r.root
      case r: SshRepository => {
        val path = r.patterns.artifactPatterns.first
        """ssh://%s%s""".format(r.connection.hostname.get, path.substring(0, path.indexOf('[')))
      }
      case r: SftpRepository => {
        val path = r.patterns.artifactPatterns.first
        """sftp://%s%s""".format(r.connection.hostname.get, path.substring(0, path.indexOf('[')))
      }
      case _ => error("Unknown repository type specified for publishing.")
    }

    artifacts.map { artifact =>
      val jarName = artifact.classifier match {
        case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
        case None => "%s-%s.jar".format(artifact.name, version.toString)
      }
      execTask(<x>
        mvn deploy:deploy-file
        -Durl={repoUrl}
        -DrepositoryId={repoId}
        -Dfile={(outputPath / jarName).absolutePath}
        -DpomFile={pomPath}
        -DcreateChecksum=true
        -Dclassifier={artifact.classifier.getOrElse("")}
      </x>)
    }.projection.map { _.run }.find { _.isDefined }.getOrElse(None)
  } dependsOn((packageToPublishActions ++ (makePom :: Nil)): _*)

  override protected def publishLocalAction = task {
    artifacts.map { artifact =>
      val jarName = artifact.classifier match {
        case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
        case None => "%s-%s.jar".format(artifact.name, version.toString)
      }
      execTask(<x>
        mvn install:install-file
        -Dfile={(outputPath / jarName).absolutePath}
        -DpomFile={pomPath}
        -DcreateChecksum=true
        -Dclassifier={artifact.classifier.getOrElse("")}
      </x>)
    }.projection.map { _.run }.find { _.isDefined }.getOrElse(None)
  } dependsOn((packageToPublishActions ++ (makePom :: Nil)): _*)

  override protected def updateAction = task {
    updateWithMaven(ArtifactType.Jar)
  }

  override def makePomAction = task {
    outputPath.asFile.mkdirs()
    val pomFile = pomPath.asFile
    pomFile.createNewFile()
    val fw = WriterFactory.newXmlWriter(pomFile)
    val writer = new MavenXpp3Writer
    writer.write(fw, mavenModel)
    None
  } dependsOn(packageToPublishActions:_*)

  protected def updateSourcesAction = task {
    updateWithMaven(ArtifactType.Jar, ArtifactType.Source)
  }
  lazy val updateSources = updateSourcesAction
}
