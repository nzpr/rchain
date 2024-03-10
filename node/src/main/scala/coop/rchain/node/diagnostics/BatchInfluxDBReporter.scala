package coop.rchain.node.diagnostics

import cats.effect.kernel.{Async, Sync}
import cats.implicits.catsSyntaxOptionId
import com.influxdb.v3.client.InfluxDBClient
import com.influxdb.v3.client.config.ClientConfig
import com.typesafe.config.Config
import coop.rchain.node.diagnostics.UdpInfluxDBReporter.Settings
import coop.rchain.shared.Log
import fs2.concurrent.Channel
import kamon.Kamon
import kamon.metric._
import kamon.module.MetricReporter
import kamon.status.Environment
import kamon.tag.{Tag, TagSet}
import kamon.util.EnvironmentTags
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.Try

@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements", "org.wartremover.warts.Var"))
class BatchInfluxDBReporter[F[_]: Async: Log](
    dispatcher: cats.effect.std.Dispatcher[F],
    config: Config = Kamon.config()
) extends MetricReporter {
  private val logger       = LoggerFactory.getLogger(classOf[BatchInfluxDBReporter[F]])
  private val c            = readSettings(config)
  private var clientConfig = c._1
  private var settings     = c._2

  private val client  = InfluxDBClient.getInstance(clientConfig)
  private var subject = dispatcher.unsafeRunSync(Channel.unbounded[F, Option[Seq[String]]])

  override def stop(): Unit =
    // finish stream
    subject.send(None)

  override def reconfigure(config: Config): Unit = {
    stop()
    val c = readSettings(config)
    clientConfig = c._1
    settings = c._2
    start()
  }

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit =
    dispatcher.unsafeRunSync {
      subject.send(Seq(translateToLineProtocol(snapshot)).some)
    }

  private def start(): Unit = {
    dispatcher.unsafeRunSync(Log[F].info(s"Starting BatchInfluxDBReporter with: ${clientConfig}"))
    subject = dispatcher.unsafeRunSync(Channel.unbounded[F, Option[Seq[String]]])
    // TODO implement accumulation over time interval settings.batchInterval
    val batching = subject.stream.unNoneTerminate.evalMap(postMetrics).compile
    dispatcher.unsafeRunAndForget(batching.drain)
  }

  private def readSettings(config: Config): (ClientConfig, Settings) = {
    import scala.jdk.CollectionConverters._
    val root          = config.getConfig("kamon.influxdb")
    val host          = root.getString("hostname")
    val organisations = root.getString("organisation")
    val accessToken   = root.getString("token").toCharArray
    val port          = root.getInt("port")
    val database      = root.getString("database")
//    val protocol    = root.getString("protocol").toLowerCase
//    val url         = s"$protocol://$host:$port/write?precision=ms&db=$database"
    val interval =
      if (root.hasPath("batch-interval"))
        Duration.fromNanos(root.getDuration("batch-interval").toNanos)
      else 10.seconds
    val precision = root.getString("precision")

    val additionalTags =
      EnvironmentTags.from(Environment.from(config), root.getConfig("additional-tags"))

    val b = new ClientConfig.Builder
    val cfg = b
      .host(host)
      .organization(organisations)
      .database(database)
      .token(accessToken)
      .build()

    val settings = Settings(
      interval,
      root.getDoubleList("percentiles").asScala.map(_.toDouble).toSeq,
      additionalTags,
      precision
    )

    cfg -> settings

  }

  private def postMetrics(metrics: Seq[String]): F[Unit] =
    Sync[F].delay { Try { client.writeRecord(metrics.mkString) } }

  private def translateToLineProtocol(periodSnapshot: PeriodSnapshot): String = {
    import periodSnapshot._
    val builder   = new StringBuilder
    val timestamp = getTimestamp(periodSnapshot.to)

    counters.foreach(c => writeLongMetricValue(builder, c, "count", timestamp))
    gauges.foreach(g => writeDoubleMetricValue(builder, g, "value", timestamp))
    histograms.foreach(h => writeMetricDistribution(builder, h, settings.percentiles, timestamp))
    rangeSamplers.foreach(
      rs => writeMetricDistribution(builder, rs, settings.percentiles, timestamp)
    )

    builder.result()
  }

  protected def getTimestamp(instant: Instant): String =
    settings.measurementPrecision match {
      case "s" =>
        instant.getEpochSecond.toString
      case "ms" =>
        instant.toEpochMilli.toString
      case "u" | "µ" =>
        ((BigInt(instant.getEpochSecond) * 1000000) + TimeUnit.NANOSECONDS.toMicros(
          instant.getNano.toLong
        )).toString
      case "ns" =>
        ((BigInt(instant.getEpochSecond) * 1000000000) + instant.getNano).toString
    }

  private def writeLongMetricValue(
      builder: StringBuilder,
      metric: MetricSnapshot.Values[Long],
      fieldName: String,
      timestamp: String
  ): Unit =
    metric.instruments.foreach { instrument =>
      writeNameAndTags(builder, metric.name, instrument.tags)
      writeIntField(builder, fieldName, instrument.value, appendSeparator = false)
      writeTimestamp(builder, timestamp)
    }

  private def writeDoubleMetricValue(
      builder: StringBuilder,
      metric: MetricSnapshot.Values[Double],
      fieldName: String,
      timestamp: String
  ): Unit =
    metric.instruments.foreach { instrument =>
      writeNameAndTags(builder, metric.name, instrument.tags)
      writeDoubleField(builder, fieldName, instrument.value, appendSeparator = false)
      writeTimestamp(builder, timestamp)
    }

  private def writeMetricDistribution(
      builder: StringBuilder,
      metric: MetricSnapshot.Distributions,
      percentiles: Seq[Double],
      timestamp: String
  ): Unit =
    metric.instruments.foreach { instrument =>
      if (instrument.value.count > 0) {
        writeNameAndTags(builder, metric.name, instrument.tags)
        writeIntField(builder, "count", instrument.value.count)
        writeIntField(builder, "sum", instrument.value.sum)
        writeIntField(builder, "mean", instrument.value.sum / instrument.value.count)
        writeIntField(builder, "min", instrument.value.min)

        percentiles.foreach { p =>
          writeDoubleField(
            builder,
            "p" + String.valueOf(p),
            instrument.value.percentile(p).value.toDouble
          )
        }

        writeIntField(builder, "max", instrument.value.max, appendSeparator = false)
        writeTimestamp(builder, timestamp)
      }
    }

  private def writeNameAndTags(builder: StringBuilder, name: String, metricTags: TagSet): Unit = {
    builder
      .append(escapeName(name))

    val tags = (if (settings.additionalTags.nonEmpty()) metricTags.withTags(settings.additionalTags)
                else metricTags).all()

    if (tags.nonEmpty) {
      tags.foreach { t =>
        builder
          .append(',')
          .append(escapeString(t.key))
          .append("=")
          .append(escapeString(Tag.unwrapValue(t).toString))
      }
    }

    builder.append(' ')
  }

  private def escapeName(in: String): String =
    in.replace(" ", "\\ ")
      .replace(",", "\\,")

  private def escapeString(in: String): String =
    in.replace(" ", "\\ ")
      .replace("=", "\\=")
      .replace(",", "\\,")

  def writeDoubleField(
      builder: StringBuilder,
      fieldName: String,
      value: Double,
      appendSeparator: Boolean = true
  ): Unit = {
    builder
      .append(fieldName)
      .append('=')
      .append(String.valueOf(value))

    if (appendSeparator)
      builder.append(',')
  }

  def writeIntField(
      builder: StringBuilder,
      fieldName: String,
      value: Long,
      appendSeparator: Boolean = true
  ): Unit = {
    builder
      .append(fieldName)
      .append('=')
      .append(String.valueOf(value))
      .append('i')

    if (appendSeparator)
      builder.append(',')
  }

  def writeTimestamp(builder: StringBuilder, timestamp: String): Unit =
    builder
      .append(' ')
      .append(timestamp)
      .append("\n")

}
