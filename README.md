Maven-SBT
=========

*Because Ivy ain't Maven.*

maven-sbt is a [simple-build-tool](http://code.google.com/p/simple-build-tool/) plugin which uses Maven (instead of Ivy) to manage dependencies and publish artifacts.


Requirements
------------

* Simple Build Tool


A Quick Warning
---------------

This plugin has not been extensively tested and extends SBT in ways which SBT was not built to be extended. It may have a massive number of bugs and unimplemented integration points. SBT's artifact API is based pretty much exclusively on Ivy and doesn't always map well to Aether (the library underlying Maven).

Right now, though, you can:

* download dependencies with sources (even transitively)
* publish artifacts locally (e.g., `mvn install`)
* publish artifacts remotely (e.g., `mvn deploy` **including snapshots and metadata**)
* print a tree of all your project's dependencies


How To Use
----------

**First**, specify maven-sbt as a dependency in `project/plugins/Plugins.scala`:

    class Plugins(info: sbt.ProjectInfo) extends sbt.PluginDefinition(info) {
      val codaRepo = "Coda Hale's Repository" at "http://repo.codahale.com/"
      val mavenSBT = "com.codahale" % "maven-sbt" % "0.1.1"
    }

and update your project class to include this trait:
    
    class MyProject(info: ProjectInfo) extends DefaultProject(info)
                                       with maven.MavenDependencies

**Finally**, use SBT as normal. Instead of using Ivy when running `make-pom`, `update`, `publish-local`, or `publish`, it'll use Maven.


License
-------

Copyright (c) 2010 Coda Hale

Published under The MIT License, see LICENSE