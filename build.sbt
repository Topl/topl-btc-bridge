import com.typesafe.sbt.packager.docker._

inThisBuild(
  List(
    organization := "co.topl",
    homepage := Some(url("https://github.com/Topl/topl-btc-bridge")),
    licenses := Seq("MPL2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/")),
    scalaVersion := "2.13.12"
  )
)

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-language:higherKinds",
  "-language:postfixOps",
  "-Ywarn-unused",
  "-Yrangepos"
)

lazy val commonSettings = Seq(
  fork := true,
  scalacOptions ++= commonScalacOptions,
  semanticdbEnabled := true, // enable SemanticDB for Scalafix
  Test / testOptions ++= Seq(
    Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "2"),
    Tests.Argument(
      TestFrameworks.ScalaTest,
      "-f",
      "sbttest.log",
      "-oDGG",
      "-u",
      "target/test-reports"
    )
  ),
  resolvers ++= Seq(
    Resolver.defaultLocal,
    "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/",
    "Sonatype Staging" at "https://s01.oss.sonatype.org/content/repositories/staging",
    "Sonatype Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots/",
    "Sonatype Releases" at "https://s01.oss.sonatype.org/content/repositories/releases/",
    "Sonatype Releases s01" at "https://s01.oss.sonatype.org/content/repositories/releases/",
    "Bintray" at "https://jcenter.bintray.com/"
  ),
  testFrameworks += TestFrameworks.MUnit
)

lazy val commonDockerSettings = List(
  Docker / version := dynverGitDescribeOutput.value
    .mkVersion(versionFmt, fallbackVersion(dynverCurrentDate.value)),
  dockerAliases := dockerAliases.value.flatMap { alias =>
    if (sys.env.get("RELEASE_PUBLISH").getOrElse("false").toBoolean)
      Seq(
        alias.withRegistryHost(Some("ghcr.io/topl")),
        alias.withRegistryHost(Some("docker.io/toplprotocol"))
      )
    else
      Seq(
        alias.withRegistryHost(Some("ghcr.io/topl"))
      )
  },
  dockerBaseImage := "adoptopenjdk/openjdk11:jdk-11.0.16.1_1-ubuntu",
  dockerExposedVolumes := Seq("/data"),
  dockerChmodType := DockerChmodType.UserGroupWriteExecute,
  dockerUpdateLatest := true
)

lazy val dockerPublishSettingsBroker = List(
  dockerExposedPorts ++= Seq(3000),
  Docker / packageName := "topl-btc-bridge"
) ++ commonDockerSettings

def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val dirtySuffix = out.dirtySuffix.dropPlus.mkString("-", "")
  if (out.isCleanAfterTag)
    out.ref.dropPrefix + dirtySuffix // no commit info if clean after tag
  else
    out.ref.dropPrefix + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
}

def fallbackVersion(d: java.util.Date): String =
  s"HEAD-${sbtdynver.DynVer timestamp d}"

lazy val mavenPublishSettings = List(
  organization := "co.topl",
  homepage := Some(url("https://github.com/Topl/topl-btc-bridge")),
  licenses := List("MPL2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/")),
  ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
  developers := List(
    Developer(
      "mundacho",
      "Edmundo Lopez Bobeda",
      "e.lopez@topl.me",
      url("https://github.com/mundacho")
    ),
    Developer(
      "DiademShoukralla",
      "Diadem Shoukralla",
      "d.dhoukralla@topl.me",
      url("https://github.com/DiademShoukralla")
    )
  )
)

lazy val noPublish = Seq(
  publishLocal / skip := true,
  publish / skip := true
)

lazy val shared = (project in file("shared"))
  .settings(
    mavenPublishSettings
  )
  .settings(
    commonSettings,
    name := "topl-btc-bridge-shared",
    libraryDependencies ++=
      Dependencies.toplBtcBridge.main ++
        Dependencies.toplBtcBridge.test
  )

lazy val toplBtcBridge = (project in file("topl-btc-bridge"))
  .settings(
    if (sys.env.get("DOCKER_PUBLISH").getOrElse("false").toBoolean)
      dockerPublishSettingsBroker
    else mavenPublishSettings
  )
  .settings(
    commonSettings,
    name := "topl-btc-bridge",
    libraryDependencies ++=
      Dependencies.toplBtcBridge.main ++
        Dependencies.toplBtcBridge.test
  )
  .enablePlugins(DockerPlugin, JavaAppPackaging)
  .dependsOn(shared)

lazy val toplBtcCli = (project in file("topl-btc-cli"))
  .settings(mavenPublishSettings)
  .settings(
    commonSettings,
    name := "topl-btc-cli",
    libraryDependencies ++=
      Dependencies.toplBtcBridge.main ++
        Dependencies.toplBtcBridge.test
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(shared)

lazy val integration = (project in file("integration"))
  .dependsOn(toplBtcBridge, toplBtcCli) // your current subproject
  .settings(
    publish / skip := true,
    libraryDependencies ++= Dependencies.toplBtcBridge.main ++ Dependencies.toplBtcBridge.test
  )

lazy val root = project
  .in(file("."))
  .settings(
    organization := "co.topl",
    name := "topl-btc-bridge-umbrella"
    )
    .settings(noPublish)
  .aggregate(toplBtcBridge, toplBtcCli)
