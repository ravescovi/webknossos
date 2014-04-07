import play.api._
import play.api.mvc._
import play.api.mvc.{ Action, Handler, RequestHeader }
import play.api.mvc.Results._

object Global extends GlobalSettings {
  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    if (request.method == "OPTIONS")
      Some(Action {
        Ok(":D").withHeaders(
          "Access-Control-Allow-Origin" -> "*",
          "Access-Control-Allow-Methods" -> "POST, GET, DELETE, PUT, HEAD, PATCH, OPTIONS",
          "Access-Control-Allow-Headers" -> request.headers.get("Access-Control-Request-Headers").getOrElse(""))
      })
    else
      super.onRouteRequest(request)
  }
}