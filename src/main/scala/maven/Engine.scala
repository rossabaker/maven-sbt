package maven

import java.io.File
import collection.mutable.HashMap
import sbt._
import org.sonatype.aether.connector.wagon.{WagonRepositoryConnectorFactory, WagonProvider}
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory
import org.apache.maven.repository.internal.{MavenRepositorySystemSession, DefaultServiceLocator}
import org.sonatype.aether.collection.CollectRequest
import org.sonatype.aether.util.graph.PreorderNodeListGenerator
import org.sonatype.aether.repository.{RepositoryPolicy, RemoteRepository, LocalRepository}
import org.sonatype.aether.{RepositoryEvent, AbstractRepositoryListener, RepositorySystem}
import org.apache.maven.wagon.providers.http.LightweightHttpWagon
import org.apache.maven.wagon.providers.file.FileWagon
import org.apache.maven.wagon.Wagon
import org.sonatype.aether.transfer.{TransferEvent, AbstractTransferListener}
import org.sonatype.aether.installation.InstallRequest
import org.sonatype.aether.util.artifact.{SubArtifact, DefaultArtifact}
import org.sonatype.aether.deployment.DeployRequest
import org.sonatype.aether.graph.{DependencyNode, Dependency}
import org.sonatype.aether.resolution.{ArtifactRequest, ArtifactResolutionException}
import org.sonatype.aether.artifact.Artifact

class ManualWagonProvider extends WagonProvider {
  def release(wagon: Wagon) = {}

  def lookup(roleHint: String) = {
    roleHint match {
      case "file" => new FileWagon
      case "http" => new LightweightHttpWagon
      case _ => null
    }
  }
}

sealed trait ChecksumPolicy {
  def value: String
}

object ChecksumPolicy {
  case object Fail extends ChecksumPolicy {
    def value = RepositoryPolicy.CHECKSUM_POLICY_FAIL
  }

  case object Warn extends ChecksumPolicy {
    def value = RepositoryPolicy.CHECKSUM_POLICY_WARN
  }

  case object Ignore extends ChecksumPolicy {
    def value = RepositoryPolicy.CHECKSUM_POLICY_IGNORE
  }
}

sealed trait SnapshotUpdatePolicy {
  def value: String
}

object SnapshotUpdatePolicy {
  case object Always extends SnapshotUpdatePolicy {
    def value = RepositoryPolicy.UPDATE_POLICY_ALWAYS
  }

  case object Daily extends SnapshotUpdatePolicy {
    def value = RepositoryPolicy.UPDATE_POLICY_DAILY
  }

  case object Never extends SnapshotUpdatePolicy {
    def value = RepositoryPolicy.UPDATE_POLICY_NEVER
  }
}

class Engine(localRepo: String,
             resolvers: Set[Resolver],
             log: Logger,
             offline: Boolean,
             checksumPolicy: ChecksumPolicy,
             snapshotUpdatePolicy: SnapshotUpdatePolicy) {
  private val spiServiceLocator = {
    val l = new DefaultServiceLocator

    l.setServices(classOf[WagonProvider], new ManualWagonProvider)

    // only doing this because Scala 2.7's type inference is fucked
    val addService = l.getClass.getMethod("addService", classOf[Class[_]], classOf[Class[_]])
    addService.invoke(l, classOf[RepositoryConnectorFactory], classOf[WagonRepositoryConnectorFactory])

    l
  }

  private val system = spiServiceLocator.getService(classOf[RepositorySystem])

  private val session = {
    val session = new MavenRepositorySystemSession
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(new LocalRepository(localRepo)))
    session.setOffline(offline)
    session.setChecksumPolicy(checksumPolicy.value)
    session.setUpdatePolicy(snapshotUpdatePolicy.value)
    // TODO: 12/29/10 <coda> -- add transfer progress logging
    session.setTransferListener(new AbstractTransferListener {
//      override def transferProgressed(event: TransferEvent) {
//        if (event.getDataLength > 10 * 1024) {
//          val downloaded = event.getTransferredBytes / 1024
//          val total = event.getResource.getContentLength / 1024
//
//          log.info("Downloaded %dK/%dK".format(downloaded, total))
//        }
//      }
      override def transferStarted(event: TransferEvent) = {
        val label = event.getRequestType match {
          case TransferEvent.RequestType.GET => "Downloading "
          case TransferEvent.RequestType.PUT => "Uploading "
        }
        log.info(label + event.getResource.getRepositoryUrl + event.getResource.getResourceName)
      }
    });
    session.setRepositoryListener(new AbstractRepositoryListener {
      override def artifactDownloading(event: RepositoryEvent) {
        log.debug("Downloading " + event.getArtifact + " from " + event.getRepository.getId)
      }

      override def artifactResolving(event: RepositoryEvent) {
        log.debug("Resolving " + event.getArtifact)
      }

      override def artifactDeploying(event: RepositoryEvent) {
        log.debug("Deploying " + event.getArtifact + " to " + event.getRepository.getId)
      }

      override def metadataDeploying(event: RepositoryEvent) {
        log.debug("Deploying metadata for " + event.getMetadata + " to " + event.getRepository.getId)
      }

      override def metadataInstalling(event: RepositoryEvent) {
        log.debug("Installing metadata for " + event.getMetadata + " in " + event.getRepository.getId)
      }

      override def artifactInstalling(event: RepositoryEvent) {
        log.debug("Installing " + event.getArtifact + " in " + event.getRepository.getId)
      }
    });
    session
  }

  private val repositories = Resolver.withDefaultResolvers(resolvers.toSeq).flatMap {
    case r: MavenRepository => {
      val repo = new RemoteRepository(r.name, "default", r.root)
      Some(repo)
    }
    // TODO: 12/29/10 <coda> -- support non-HTTP Maven repos
    case r => None
  }.toSeq

  private def debugDependencies(node: DependencyNode, indent: Int) {
    log.debug((" " * indent) + "-> " + (if (node.getDependency == null) {
      "(root)"
    } else {
      node.getDependency
    }))
    val iterator = node.getChildren.iterator
    while (iterator.hasNext) {
      debugDependencies(iterator.next(), indent + 2)
    }
  }

  private def resolveSubArtifact(dependency: Dependency, classifier: String): Option[Artifact] = {
    val request = new ArtifactRequest
    repositories.reverse.foreach(request.addRepository)
    request.setArtifact(new SubArtifact(dependency.getArtifact, classifier, "jar"))
    try {
      val response = system.resolveArtifact(session, request)
      if (response.isMissing) {
        log.warn("Unable to download " + response.getArtifact)
        None
      } else {
        Some(response.getArtifact)
      }
    } catch {
      case e: ArtifactResolutionException => {
        log.warn("Unable to download " + request.getArtifact)
        None
      }
    }
  }

  def update(dependencies: Set[ModuleID], managedDependenciesPath: Path) {
    try { {
      val generator = new PreorderNodeListGenerator
      val request = new CollectRequest()

      repositories.reverse.foreach(request.addRepository)

      for (dependency <- dependencies) {
        val artifact = new DefaultArtifact(dependency.organization, dependency.name, "jar", dependency.revision)
        request.addDependency(new Dependency(artifact, dependency.configurations.getOrElse("compile")))
      }

      val response = system.collectDependencies(session, request)
      debugDependencies(response.getRoot, 0)

      val root = response.getRoot
      system.resolveDependencies(session, root, null)
      root.accept(generator)

      // oh my god fuck Scala 2.7
      val artifacts = new HashMap[String, List[Artifact]]
      val iterator = generator.getDependencies(false).iterator
      while (iterator.hasNext) {
        val dependency = iterator.next()
        if (!(dependency.getArtifact.getGroupId == "org.scala-lang" &&
                dependency.getArtifact.getArtifactId == "scala-library")) {
          val deps = artifacts.getOrElse(dependency.getScope, Nil)
          artifacts += (dependency.getScope -> (
            dependency.getArtifact ::
                    resolveSubArtifact(dependency, "sources").orElse(
                      resolveSubArtifact(dependency, "javadoc")
                    ).toList ::: deps
          ))
        }
      }

      for ((scope, artifacts) <- artifacts) {
        val files = artifacts.map { _.getFile }
        val filenames = Set() ++ files.map { _.getName }

        val dir = managedDependenciesPath / scope
        dir.asFile.mkdirs()

        for (file <- dir.asFile.listFiles()) {
          if (!filenames.contains(file.getName)) {
            log.debug("Deleting " + file)
            file.delete()
          }
        }

        FileUtilities.copyFilesFlat(files, dir, log)
      }
    }
    } catch {
      case e: ArtifactResolutionException => {
        log.error(e.getMessage)
      }
    }
  }

  private def jarName(artifact: sbt.Artifact, version: Version) = artifact.classifier match {
    case Some(classifier) => "%s-%s-%s.jar".format(artifact.name, version.toString, classifier)
    case None => "%s-%s.jar".format(artifact.name, version.toString)
  }

  private def buildArtifacts(project: Project, moduleID: String, artifacts: Set[sbt.Artifact], pom: Path, outputPath: Path) = {
    val (Seq(main), others) = artifacts.partition { _.classifier.isEmpty }

    val mainArtifact = new DefaultArtifact(project.organization, moduleID,
                                           "", "jar", project.version.toString)
                            .setFile((outputPath / jarName(main, project.version)).asFile)

    val otherArtifacts = others.map {other => {
      new SubArtifact(mainArtifact, other.classifier.get, "jar")
      .setFile((outputPath / jarName(other, project.version)).asFile)
    }
    }

    val pomArtifact = new SubArtifact(mainArtifact, "", "pom").setFile(pom.asFile)

    mainArtifact :: pomArtifact :: otherArtifacts.toList
  }

  def install(project: Project, moduleID: String, artifacts: Set[sbt.Artifact], pom: Path, outputPath: Path) {
    val request = new InstallRequest()
    buildArtifacts(project, moduleID, artifacts, pom, outputPath).foreach(request.addArtifact)
    system.install(session, request)
  }

  def deploy(project: Project, moduleID: String, artifacts: Set[sbt.Artifact], pom: Path, outputPath: Path) {
    val request = new DeployRequest
    buildArtifacts(project, moduleID, artifacts, pom, outputPath).foreach(request.addArtifact)
    // FIXME: 12/29/10 <coda> -- actually detect which repo to publish to!
    request.setRepository(new RemoteRepository("nexus", "default", new File("target/dist-repo").toURI().toString()))
    system.deploy(session, request)
  }
}
