package profiles

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class HttpGetSimulation extends Simulation {

  val targetUrl = System.getProperty("target_url", "https://httpbin.org/get")
  val ratePerSec = System.getProperty("rate_per_sec", "50").toInt
  val durationSec = System.getProperty("duration_sec", "30").toInt

  private val uri = new java.net.URI(targetUrl)
  private val base = s"${uri.getScheme}://${uri.getHost}${if (uri.getPort != -1) s":${uri.getPort}" else ""}"
  private val path = Option(uri.getPath).filter(_.nonEmpty).getOrElse("/") +
    Option(uri.getQuery).map("?" + _).getOrElse("") +
    Option(uri.getFragment).map("#" + _).getOrElse("")

  val httpProtocol = http
    .baseUrl(base)
    .acceptHeader("text/html,application/json")
    .userAgentHeader("Boehm-Gatling")

  val scn = scenario("HTTP GET")
    .exec(
      http("get-request")
        .get(path)
        .check(status.is(200))
    )

  setUp(
    scn.inject(
      constantUsersPerSec(ratePerSec).during(durationSec.seconds)
    )
  ).protocols(httpProtocol)
}
