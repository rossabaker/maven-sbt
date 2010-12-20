package maven

import sbt._
import sbt.Process._
import org.apache.maven.project.MavenProject
import org.apache.maven.model.{Repository, Model, Dependency}
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.codehaus.plexus.util.WriterFactory
import xml.Elem

// TODO: 12/19/10 <coda> -- fix logging

trait MavenDependencies extends DefaultProject {
  override def classpathFilter = super.classpathFilter -- "*-sources.jar" -- "*-javadoc.jar"

  private lazy val mavenProject = {
    new MavenProject(mavenModel)
  }

  private lazy val mavenModel = {
    val m = new Model
    m.setModelVersion("4.0.0")
    m.setName(name)
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

  private def runAll(commands: Elem*) = {
    commands.foreach { c =>
      if (c ! log != 0) {
        error("Error running " + c)
      }
    }
  }

  override protected def publishAction = {
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

    def deployFile(artifact: Artifact) = {
      val jarName = artifact.classifier match {
        case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
        case None => "%s-%s.jar".format(artifact.name, version.toString)
      }
      val code = <x>
          mvn deploy:deploy-file
          -Durl={repoUrl}
          -DrepositoryId={repoId}
          -Dfile={(outputPath / jarName).absolutePath}
          -DpomFile={pomPath}
          -DcreateChecksum=true
          -Dclassifier={artifact.classifier.getOrElse("")}
      </x> ! log
      if (code == 0) {
        None
      } else {
        Some("Unable to publish " + jarName)
      }
    }

    task {
      log.info("Publishing...")
      artifacts.projection.map(deployFile).find { _.isDefined }.getOrElse(None)
    } dependsOn (makePom)
  }


  override protected def publishLocalAction = {
    def installFile(artifact: Artifact) = {
      val jarName = artifact.classifier match {
        case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
        case None => "%s-%s.jar".format(artifact.name, version.toString)
      }
      val code = <x>
          mvn install:install-file
          -Dfile={(outputPath / jarName).absolutePath}
          -DpomFile={pomPath}
          -DcreateChecksum=true
          -Dclassifier={artifact.classifier.getOrElse("")}
      </x> ! log
      if (code == 0) {
        None
      } else {
        Some("Unable to publish " + jarName)
      }
    }

    task {
      log.info("Publishing locally...")
      artifacts.projection.map(installFile).find { _.isDefined }.getOrElse(None)
    } dependsOn (makePom)
  }

  override protected def updateAction = {
    task {
      log.info("Updating dependencies...")
      FileUtilities.clean(managedDependencyPath / "compile", log)
      FileUtilities.clean(managedDependencyPath / "test", log)

      val compileDepPath = (managedDependencyPath / "compile").absolutePath
      val testDepPath = (managedDependencyPath / "test").absolutePath

      execTask {
       <x>
         mvn -f {pomPath} dependency:copy-dependencies
         -DoutputDirectory={testDepPath}
         -Dmdep.failOnMissingClassifierArtifact=true -DexcludeScope=compile
       </x> #&& <x>
         mvn -f {pomPath} dependency:copy-dependencies
         -DoutputDirectory={testDepPath}
         -Dmdep.failOnMissingClassifierArtifact=false -DexcludeScope=compile
         -Dclassifier=sources
       </x> #&& <x>
         mvn -f {pomPath} dependency:copy-dependencies
        -DoutputDirectory={compileDepPath}
        -Dmdep.failOnMissingClassifierArtifact=true
        -DincludeScope=compile -DexcludeScope=test
       </x> #&& <x>
         mvn -f {pomPath} dependency:copy-dependencies
        -DoutputDirectory={compileDepPath}
        -Dmdep.failOnMissingClassifierArtifact=false
        -DincludeScope=compile -DexcludeScope=test
        -Dclassifier=sources
       </x>
      }.run
    } dependsOn(makePom)
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
}
