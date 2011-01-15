package maven

import java.io.File
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
import org.apache.maven.wagon.providers.ssh.external.ScpExternalWagon
import collection.mutable.{ArrayBuffer, HashMap}

class ManualWagonProvider extends WagonProvider {
  def release(wagon: Wagon) = {}

  def lookup(roleHint: String) = {
    roleHint match {
      case "file" => new FileWagon
      case "http" => new LightweightHttpWagon
      case "ssh" | "sftp" => new ScpExternalWagon
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

  private def collectDependencies(dependencies: Set[ModuleID]): DependencyNode = {
    val request = new CollectRequest()

    repositories.reverse.foreach(request.addRepository)

    for (dependency <- dependencies) {
      val artifact = new DefaultArtifact(dependency.organization, dependency.name, "jar", dependency.revision)
      request.addDependency(new Dependency(artifact, dependency.configurations.getOrElse("compile")))
    }

    val response = system.collectDependencies(session, request)
    response.getRoot
  }

  private def resolveDependencies(project: BasicManagedProject): List[Dependency] = {
    val generator = new PreorderNodeListGenerator
    val root = collectDependencies(project.libraryDependencies)
    printDependencyTree(project, root, log.debug(_))
    system.resolveDependencies(session, root, null)
    root.accept(generator)

    val deps = new ArrayBuffer[Dependency]
    val iterator = generator.getDependencies(false).iterator
    while (iterator.hasNext) {
      deps += iterator.next()
    }
    deps.toList
  }

  private def isScalaLib(artifact: Artifact) = artifact.getGroupId == "org.scala-lang" &&
          artifact.getArtifactId == "scala-library"

  def update(project: BasicManagedProject) {
    try {
      // oh my god fuck Scala 2.7
      val artifacts = new HashMap[String, List[Artifact]]
      for (dependency <- resolveDependencies(project)) {
        if (!isScalaLib(dependency.getArtifact)) {
          val scope = dependency.getScope match {
            case "optional" => "compile"
            case "runtime" => "compile"
            case "provided" => "compile"
            case s => s
          }

          val deps = artifacts.getOrElse(scope, Nil)
          artifacts += (scope -> (
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

        val dir = project.managedDependencyPath / scope
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

  private def jarName(artifact: sbt.Artifact, version: Version) = artifact.classifier match {
    case Some(classifier) => "%s-%s-%s.%s".format(artifact.name, version.toString, classifier, artifact.extension)
    case None => "%s-%s.%s".format(artifact.name, version.toString, artifact.extension)
  }

  private def buildArtifacts(project: BasicManagedProject) = {
    val mainArtifactPredicate = { artifact: sbt.Artifact => 
      project match {
        case project: BasicScalaProject => artifact == project.mainArtifact
        case _ => artifact.classifier.isEmpty && artifact.`type` != "asc"
      }
    }
    val (Seq(main), others) = project.artifacts.partition(mainArtifactPredicate)

    val mainArtifact = new DefaultArtifact(project.organization, project.moduleID,
                                           main.classifier.getOrElse(null), main.extension, 
                                           project.version.toString)
                            .setFile((project.outputPath / jarName(main, project.version)).asFile)

    val otherArtifacts = others.map {other => {
      new SubArtifact(mainArtifact, other.classifier.getOrElse(null), other.extension)
      .setFile((project.outputPath / jarName(other, project.version)).asFile)
    }
    }

    mainArtifact :: otherArtifacts.toList
  }

  def install(project: BasicManagedProject) {
    val request = new InstallRequest()
    buildArtifacts(project).foreach(request.addArtifact)
    system.install(session, request)
  }

  def deploy(project: BasicManagedProject) {
    val request = new DeployRequest
    buildArtifacts(project).foreach(request.addArtifact)

    val repo = project.reflectiveRepositories.get(BasicManagedProject.PublishToName).getOrElse(error("No repository to publish to was specified"))
    val repoId = repo.name
    val repoUrl = repo match {
      case r: MavenRepository => r.root
      case r: SshRepository => {
        val path = r.patterns.artifactPatterns.first
        """ssh://%s%s""".format(r.connection.hostname.get, path.substring(0, path.indexOf('[')))
      }
      case r: SftpRepository => {
        val path = r.patterns.artifactPatterns.first
        """sftp://%s%s""".format(r.connection.hostname.get, path.substring(0, path.indexOf('[')))
      }
      case r: FileRepository => {
        val path = r.patterns.artifactPatterns.first
        new File(path.substring(0, path.indexOf('['))).toURI.toString
      }
      case r: URLRepository => {
        val path = r.patterns.artifactPatterns.first
        path.substring(0, path.indexOf('['))
      }
      case _ => error("Unknown repository type specified for publishing.")
    }
    request.setRepository(new RemoteRepository(repoId, "default", repoUrl))
    system.deploy(session, request)
  }

  def printDependencies(project: BasicManagedProject) {
    printDependencyTree(project, collectDependencies(project.libraryDependencies), log.info(_))
  }

  private def printDependencyTree(project: BasicManagedProject, root: DependencyNode, print: String => Unit) {
    val nontransitiveDependencies = project.libraryDependencies.map{d => d.organization + d.name}
    def printDependency(node: DependencyNode, indent: Int) {
      if (node.getDependency == null) {
        if (project == null) {
          print("+- (root)")
        } else {
          print(String.format("+- %s:%s:jar:%s", project.organization, project.name, project.version))
        }
      } else if (!isScalaLib(node.getDependency.getArtifact)) {
        val artifact = node.getDependency.getArtifact
        val signifier = if (nontransitiveDependencies.contains(artifact.getGroupId + artifact.getArtifactId)) "+" else "\\"
        print(String.format("%s%s- %s:%s", "|  " * indent, signifier, artifact, node.getDependency.getScope))
      }
      val iterator = node.getChildren.iterator
      while (iterator.hasNext) {
        printDependency(iterator.next(), indent + 1)
      }
    }
    printDependency(root, 0)
  }
}
