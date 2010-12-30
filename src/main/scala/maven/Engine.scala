package maven

import java.io.File
import collection.mutable.HashMap
import sbt._
import org.sonatype.aether.connector.wagon.{WagonRepositoryConnectorFactory, WagonProvider}
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory
import org.apache.maven.repository.internal.{MavenRepositorySystemSession, DefaultServiceLocator}
import org.sonatype.aether.collection.CollectRequest
import org.sonatype.aether.util.artifact.DefaultArtifact
import org.sonatype.aether.util.graph.PreorderNodeListGenerator
import org.sonatype.aether.graph.{DependencyNode, Dependency}
import org.sonatype.aether.repository.{RepositoryPolicy, RemoteRepository, LocalRepository}
import org.sonatype.aether.{RepositoryEvent, AbstractRepositoryListener, RepositorySystem}
import org.apache.maven.wagon.providers.http.LightweightHttpWagon
import org.apache.maven.wagon.providers.file.FileWagon
import org.apache.maven.wagon.Wagon
import org.sonatype.aether.resolution.ArtifactResolutionException
import org.sonatype.aether.transfer.{TransferEvent, AbstractTransferListener}

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
        log.info("Downloading " + event.getResource.getRepositoryUrl + event.getResource.getResourceName)
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
        log.debug("Deploying metadata for " + event.getArtifact + " to " + event.getRepository.getId)
      }

      override def metadataInstalling(event: RepositoryEvent) {
        log.debug("Installing metadata for " + event.getArtifact + " in " + event.getRepository.getId)
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
    log.debug((" " * indent) + "-> " + (if (node.getDependency == null) "(root)" else node.getDependency))
    val iterator = node.getChildren.iterator
    while (iterator.hasNext) {
      debugDependencies(iterator.next(), indent + 2)
    }
  }

  def update(dependencies: Set[ModuleID], managedDependenciesPath: Path) {
    try {
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
      val artifacts = new HashMap[String, List[Dependency]]
      val iterator = generator.getDependencies(false).iterator
      while (iterator.hasNext) {
        val dependency = iterator.next()
        if (!(dependency.getArtifact.getGroupId == "org.scala-lang" &&
                dependency.getArtifact.getArtifactId == "scala-library")) {
          val deps = artifacts.getOrElse(dependency.getScope, Nil)
          artifacts += (dependency.getScope -> (dependency :: deps))
        }
      }

      for ((scope, deps) <- artifacts) {
        val files = deps.map { _.getArtifact.getFile }
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
    } catch {
      case e: ArtifactResolutionException => {
        log.error(e.getMessage)
      }
    }
  }

  def install(project: Project, artifacts: Map[String, File], pom: File) {
    // TODO: 12/29/10 <coda> -- implement installing
//    Artifact projectOutput = new DefaultArtifact("test", "demo", "", "jar", "0.1-SNAPSHOT");
//    projectOutput = projectOutput.setFile(new File("demo.jar"));
//    Artifact projectPom = new SubArtifact(projectOutput, "", "pom");
//    projectPom = projectPom.setFile(new File("pom.xml"));
//
//    InstallRequest installRequest = new InstallRequest();
//    installRequest.addArtifact(projectOutput).addArtifact(projectPom);
//    repoSystem.install(session, installRequest);
  }

  def deploy(project: Project, artifacts: Map[String, File], pom: File, repository: Resolver) {
    // TODO: 12/29/10 <coda> -- implement deploying
//    Artifact projectOutput = new DefaultArtifact("test", "demo", "", "jar", "0.1-SNAPSHOT");
//    projectOutput = projectOutput.setFile(new File("demo.jar"));
//    Artifact projectPom = new SubArtifact(projectOutput, "", "pom");
//    projectPom = projectPom.setFile(new File("pom.xml"));
//
//    DeployRequest deployRequest = new DeployRequest();
//    deployRequest.addArtifact(projectOutput).addArtifact(projectPom);
//    deployRequest.setRepository(new RemoteRepository("nexus", "default",
//                                                     new File("target/dist-repo").toURI().toString()));
//    repoSystem.deploy(session, deployRequest);
  }
}
