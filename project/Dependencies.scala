import sbt._

object Dependencies {

  val catsCoreVersion = "2.10.0"

  lazy val http4sVersion = "0.23.23"

  val logback: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % "1.4.11"
  )

  val log4cats: Seq[ModuleID] = Seq(
    "org.typelevel" %% "log4cats-core" % "2.6.0",
    "org.typelevel" %% "log4cats-slf4j" % "2.6.0"
  )

  lazy val toplOrg = "co.topl"

  lazy val bramblVersion = "2.0.0-beta1"

  val bramblSdk = toplOrg %% "brambl-sdk" % bramblVersion

  val bramblCrypto = toplOrg %% "crypto" % bramblVersion

  val bramblServiceKit = toplOrg %% "service-kit" % bramblVersion

  val brambl: Seq[ModuleID] = Seq(bramblSdk, bramblCrypto, bramblServiceKit)

  lazy val bitcoinsVersion = "1.9.4"

  lazy val monocleVersion = "3.1.0"

  lazy val munit: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit" % "1.0.0-M10"
  )

  lazy val munitCatsEffects: Seq[ModuleID] = Seq(
    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M4"
  )

  val cats: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-core" % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % "3.5.1"
  )

  val grpcNetty: Seq[ModuleID] =
    Seq("io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion)

  val grpcRuntime: Seq[ModuleID] =
    Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  lazy val scopt: Seq[ModuleID] = Seq("com.github.scopt" %% "scopt" % "4.0.1")

  lazy val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "org.http4s" %% "http4s-ember-server" % http4sVersion
  )

  lazy val bitcoinS: Seq[ModuleID] = Seq(
    "org.bitcoin-s" %% "bitcoin-s-bitcoind-rpc" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-core" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-chain" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-dlc-oracle" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-eclair-rpc" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-fee-provider" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-key-manager" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-node" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-oracle-explorer-client" % bitcoinsVersion,
    "org.bitcoin-s" % "bitcoin-s-secp256k1jni" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-wallet" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-zmq" % bitcoinsVersion
  )

  lazy val optics: Seq[ModuleID] = Seq(
    "dev.optics" %% "monocle-core" % monocleVersion,
    "dev.optics" %% "monocle-macro" % monocleVersion
  )

  object toplBtcBridge {

    lazy val main: Seq[ModuleID] =
      brambl ++
        scopt ++
        cats ++
        log4cats ++
        http4s ++
        optics ++
        bitcoinS ++
        grpcNetty ++
        grpcRuntime

    lazy val test: Seq[ModuleID] =
      (
        munit ++ munitCatsEffects
      )
        .map(_ % Test)
  }

  object toplBtcCli {

    lazy val main: Seq[ModuleID] =
      brambl ++
        scopt ++
        cats ++
        log4cats ++
        logback ++
        http4s ++
        bitcoinS

    lazy val test: Seq[ModuleID] =
      (
        munit ++ munitCatsEffects
      )
        .map(_ % Test)
  }
}
