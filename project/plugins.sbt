Seq(
  "com.eed3si9n"            % "sbt-assembly"              % "2.1.1",
  "org.scalameta"           % "sbt-scalafmt"              % "2.5.0",
  "ch.epfl.scala"           % "sbt-scalafix"              % "0.11.0",
  "com.eed3si9n"            % "sbt-buildinfo"             % "0.11.0",
  "com.github.sbt"          % "sbt-native-packager"       % "1.10.0",
  "com.github.sbt"          % "sbt-ci-release"            % "1.5.12",
  "org.scoverage"           % "sbt-scoverage"             % "2.0.11",
  "org.typelevel"           % "sbt-fs2-grpc"              % "2.7.16",
).map(addSbtPlugin)