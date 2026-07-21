package profiles

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class HttpGetSimulation extends Simulation {

  val targetUrl = System.getProperty("target_url", "https://httpbin.org/get")
  val ratePerSec = System.getProperty("rate_per_sec", "50").toInt
  val durationSec = System.getProperty("duration_sec", "30").toInt

  val httpProtocol = http
    .baseUrl(targetUrl)
    .acceptHeader("text/html,application/json")
    .userAgentHeader("Boehm-Gatling")

  val scn = scenario("HTTP GET")
    .exec(
      http("get-request")
        .get("/")
        .check(status.is(200))
    )

  setUp(
    scn.inject(
      constantUsersPerSec(ratePerSec).during(durationSec.seconds)
    )
  ).protocols(httpProtocol)
}
