import sbt.Keys.*
import sbt.Resolver

ThisBuild / scalaVersion := "3.3.0"
ThisBuild / organization := "zealot"
ThisBuild / version      := "v0.2.8"
ThisBuild / publishTo := {
  val host = "artifactregistry://southamerica-east1-maven.pkg.dev/oystr-cloud-test"
  if (isSnapshot.value) Some("Google Artifact Registry" at host + "/snapshots")
  else                  Some("Google Artifact Registry" at host + "/releases")
}

lazy val settings = Seq(
  resolvers ++= Seq(Resolver.mavenLocal) ++ Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots"),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

val ZioConfigVersion  = "4.0.3"

lazy val deps = new {
  val betterFiles       = "com.github.pathikrit"     %% "better-files"        % "3.9.2"
  val harReader         = "de.sstoehr"               %  "har-reader"          % "2.2.1"
  val jsoup             = "org.jsoup"                %  "jsoup"               % "1.16.1"
  val logback           = "ch.qos.logback"           % "logback-classic"      % "1.2.11"
  val thymeleaf         = "org.thymeleaf"            %  "thymeleaf"           % "3.1.2.RELEASE"
  val zio               = "dev.zio"                  %% "zio"                 % "2.1.16"
  val zioJson           = "dev.zio"                  %% "zio-json"            % "0.7.39"
  val zioSchema         = "dev.zio"                  %% "zio-schema"          % "1.6.5"
  val zioSchemaJson     = "dev.zio"                  %% "zio-schema-json"     % "1.6.5"
  val zioLogging        = "dev.zio"                  %% "zio-logging"         % "2.5.0"
  val zioLoggingSlf4j   = "dev.zio"                  %% "zio-logging-slf4j"   % "2.5.0"
  val zioConfig         = "dev.zio"                  %% "zio-config"          % ZioConfigVersion
  val zioConfigTypesafe = "dev.zio"                  %% "zio-config-typesafe" % ZioConfigVersion
  val zioConfigMagnolia = "dev.zio"                  %% "zio-config-magnolia" % ZioConfigVersion
  val zioProcess        = "dev.zio"                  %% "zio-process"         % "0.7.2"
  val scalaTest         = "org.scalatest"            %% "scalatest"           % "3.2.16" % Test
  val zioTest           = "dev.zio"                  %% "zio-test"            % "2.0.15" % Test
  val zioTestSbt        = "dev.zio"                  %% "zio-test-sbt"        % "2.0.15" % Test
  val zioTestMagnolia   = "dev.zio"                  %% "zio-test-magnolia"   % "2.0.15" % Test
}

lazy val shared = Seq(deps.zio, deps.zioJson, deps.betterFiles, deps.scalaTest, deps.zioTest, deps.zioTestSbt, deps.zioTestMagnolia)

lazy val commons = (project in file("commons"))
  .withId("zealot-commons")
  .settings(settings, libraryDependencies ++= shared)

lazy val http = (project in file("http"))
  .withId("zealot-http")
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= shared ++ Seq(deps.jsoup, deps.zioProcess))

lazy val zealot = (project in file("."))
    .aggregate(commons, http)
    .settings(settings)
