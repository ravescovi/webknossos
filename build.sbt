import play.routes.compiler.InjectedRoutesGenerator
import play.sbt.routes.RoutesKeys.routesGenerator
import sbt._

val wkVersion = scala.io.Source.fromFile("version").mkString.trim

name := "oxalis"

version := wkVersion

scalaVersion in ThisBuild := "2.11.8"


lazy val webknossosSettings = Seq(
  TwirlKeys.templateImports += "oxalis.view.helpers._",
  TwirlKeys.templateImports += "oxalis.view._",
  scalacOptions += "-target:jvm-1.8",
  routesGenerator := InjectedRoutesGenerator,
  libraryDependencies ++= Dependencies.webknossosDependencies,
  resolvers ++= DependencyResolvers.dependencyResolvers,
  sourceDirectory in Assets := file("none"),
  updateOptions := updateOptions.value.withLatestSnapshots(true),
  unmanagedJars in Compile ++= {
    val libs = baseDirectory.value / "lib"
    val subs = (libs ** "*") filter { _.isDirectory }
    val targets = ( (subs / "target") ** "*" ) filter {f => f.name.startsWith("scala-") && f.isDirectory}
    ((libs +++ subs +++ targets) ** "*.jar").classpath
  }
)


lazy val webknossosDatastoreSettings = Seq(
  libraryDependencies ++= Dependencies.webknossosDatastoreDependencies,
  resolvers ++= DependencyResolvers.dependencyResolvers,
  routesGenerator := InjectedRoutesGenerator,
  name := "webknossos-datastore",
  version := "wk-" + wkVersion
)


val protocolBufferSettings = Seq(
  ProtocPlugin.autoImport.PB.targets in Compile := Seq(
    scalapb.gen() -> new java.io.File((sourceManaged in Compile).value + "/proto")
  ),
  ProtocPlugin.autoImport.PB.protoSources := Seq(new java.io.File("braingames-datastore/proto")))


lazy val util = (project in file("util"))
  .settings(Seq(
    resolvers ++= DependencyResolvers.dependencyResolvers,
    libraryDependencies ++= Dependencies.utilDependencies
  ))

lazy val braingamesBinary = (project in file("braingames-binary"))
  .dependsOn(util)
  .settings(Seq(
    resolvers ++= DependencyResolvers.dependencyResolvers,
    libraryDependencies ++= Dependencies.braingamesBinaryDependencies
  ))

lazy val braingamesDatastore = (project in file("braingames-datastore"))
  .dependsOn(util, braingamesBinary)
  .enablePlugins(play.sbt.PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(ProtocPlugin)
  .settings(protocolBufferSettings)
  .settings(Seq(
    resolvers ++= DependencyResolvers.dependencyResolvers,
    libraryDependencies ++= Dependencies.braingamesDatastoreDependencies,
    routesGenerator := InjectedRoutesGenerator
  ))

lazy val webknossosDatastore = (project in file("webknossos-datastore"))
  .dependsOn(braingamesDatastore)
  .enablePlugins(play.sbt.PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .settings((webknossosDatastoreSettings ++ BuildInfoSettings.webknossosDatastoreBuildInfoSettings):_*)

lazy val webknossos = (project in file("."))
  .dependsOn(util, braingamesBinary, braingamesDatastore)
  .enablePlugins(play.sbt.PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .settings((webknossosSettings ++ AssetCompilation.settings ++ BuildInfoSettings.webknossosBuildInfoSettings):_*)
