package coop.rchain.state

import java.nio.file.Path

import scala.concurrent.duration.FiniteDuration

trait StateManager[F[_]] {
  def createSync(dirPath: Path): F[Synchronizer[F]]
}

trait Synchronizer[F[_]] {
  type Request
  type Result
  type Ticket

  def start: F[Unit]

  def stop(timeout: FiniteDuration): F[Unit]

  def request(request: Request): F[Ticket]

  def result(ticket: Ticket): F[Result]

  def status: F[Map[Ticket, Request]]
}
