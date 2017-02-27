/*
 * Copyright 2013 Damien Lecan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package play.core.server.servlet

import java.net.URLDecoder
import java.security.cert.X509Certificate
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, Cookie => ServletCookie}

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import play.api.{Logger, _}
import play.api.http.HeaderNames.{CONTENT_LENGTH, X_FORWARDED_FOR, CONTENT_TYPE}
import play.api.http.{HeaderNames, HttpEntity, HttpProtocol}
import play.api.libs.streams.Accumulator
import play.api.mvc.{WebSocket, _}

import scala.concurrent.Future
import scala.util.control.Exception

trait RequestHandler {

  def apply(server: Play2WarServer)

}

trait HttpServletRequestHandler extends RequestHandler {

  implicit val materializer = Play.current.materializer

  protected def getPlayHeaders(request: HttpServletRequest): Headers

  protected def getPlayCookies(request: HttpServletRequest): Cookies

  /**
   * Get a list of cookies from "flat" cookie representation (one-line-string cookie).
   */
  protected def getServletCookies(flatCookie: String): Seq[ServletCookie]

  /**
   * Get HTTP request.
   */
  protected def getHttpRequest(): RichHttpServletRequest

  /**
   * Get HTTP response.
   */
  protected def getHttpResponse(): RichHttpServletResponse

  /**
   * Call just before end of service(...).
   */
  protected def onFinishService(): Unit

  /**
   * Call every time the HTTP response must be terminated (completed).
   */
  protected def onHttpResponseComplete(): Unit

  protected def feedBodyParser(bodyParser: Accumulator[ByteString, Result]): Future[Result] = {
    val source = StreamConverters.fromInputStream(() => getHttpRequest().getRichInputStream.orNull)
    source.runWith(bodyParser.toSink)
  }

  protected def setHeaders(headers: Map[String, String], httpResponse: HttpServletResponse): Unit = {
    // Set response headers
    headers.foreach {
      case (CONTENT_LENGTH, "-1") => // why is it skip?

      // Fix a bug for Set-Cookie header.
      // Multiple cookies could be merged in a single header
      // but it's not properly supported by some browsers
      case (name, value) if name.equalsIgnoreCase(play.api.http.HeaderNames.SET_COOKIE) =>
        getServletCookies(value).foreach(httpResponse.addCookie)

      case (name, value) if name.equalsIgnoreCase(HeaderNames.TRANSFER_ENCODING) && value == HttpProtocol.CHUNKED =>
        // ignore this header
        // the JEE container sets this header itself. Avoid duplication of header (issues/289)

      case (name, value) =>
        httpResponse.setHeader(name, value)
    }
  }

  /**
   * default implementation to push a play result to the servlet output stream
   *
   * @param futureResult the result of the play action
   * @param cleanup clean up callback
   */
  protected def pushPlayResultToServletOS(futureResult: Future[Result], cleanup: () => Unit): Unit = {
    import play.api.libs.iteratee.Execution.Implicits.trampoline

    futureResult.map { result =>
      getHttpResponse().getHttpServletResponse.foreach { httpResponse =>

        val status = result.header.status
        val headers = result.header.headers
        val body: HttpEntity = result.body        
        
        httpResponse.setStatus(status)
        setHeaders(headers, httpResponse)
        
        body.contentType.foreach { contentType =>
          if (!httpResponse.containsHeader(CONTENT_TYPE)) {
            httpResponse.setHeader(CONTENT_TYPE, contentType)
          }
        }

        Logger("play").warn("Sending simple result: " + result)                

        val sink = StreamConverters.fromOutputStream(httpResponse.getOutputStream)
        val future = body.dataStream.runWith(sink)

        future.map( _ => { onHttpResponseComplete() })

      } // end match foreach

    }.onComplete { _ => cleanup() }
  }
}

/**
 * Generic implementation of HttpServletRequestHandler.
 * One instance per incoming HTTP request.
 *
 * <strong>/!\ Warning: this class and its subclasses are intended to thread-safe.</strong>
 */
abstract class Play2GenericServletRequestHandler(val servletRequest: HttpServletRequest, val servletResponse: Option[HttpServletResponse]) extends HttpServletRequestHandler {

  override def apply(server: Play2WarServer) = {

    //    val keepAlive -> non-sens
    //    val websocketableRequest -> non-sens
    val httpVersion = servletRequest.getProtocol
    val servletPath = servletRequest.getRequestURI
    val servletUri = servletPath + Option(servletRequest.getQueryString).filterNot(_.isEmpty).fold("")("?" + _)
    val parameters = getHttpParameters(servletRequest)
    val rHeaders = getPlayHeaders(servletRequest)
    val httpMethod = servletRequest.getMethod
    val isSecure = servletRequest.isSecure

    val clientCertificatesFromRequest: Array[X509Certificate] = Option(servletRequest.getAttribute("javax.servlet.request.X509Certificate")).map(value => value.asInstanceOf[Array[X509Certificate]]).orNull
    val clientCertificates: Seq[X509Certificate] = Option(clientCertificatesFromRequest).map(certs => certs.toSeq).orNull

    def rRemoteAddress = {
      val remoteAddress = servletRequest.getRemoteAddr
      (for {
        xff <- rHeaders.get(X_FORWARDED_FOR)
        app <- server.applicationProvider.get.toOption
        trustxforwarded <- app.configuration.getBoolean("trustxforwarded").orElse(Some(false))
        if remoteAddress == "127.0.0.1" || trustxforwarded
      } yield xff).getOrElse(remoteAddress)
    }

    def tryToCreateRequest = createRequestHeader(parameters)

    def createRequestHeader(parameters: Map[String, Seq[String]] = Map.empty[String, Seq[String]]) = {
      //mapping servlet request to Play's
      val untaggedRequestHeader = new RequestHeader {
        val id = server.newRequestId
        val tags = Map.empty[String,String]
        def uri = servletUri
        def path = servletPath
        def method = httpMethod
        def version = httpVersion
        def queryString = parameters
        def headers = rHeaders
        lazy val remoteAddress = rRemoteAddress
        def secure: Boolean = isSecure
        def clientCertificateChain = Option(clientCertificates)
      }
      untaggedRequestHeader
    }

    // get handler for request
    val (requestHeader, handler: Either[Future[Result], (Handler,Application)]) = Exception
      .allCatch[RequestHeader].either(tryToCreateRequest)
      .fold(
      e => {
        val rh = createRequestHeader()
        val r = server.applicationProvider.get.map(_.global).getOrElse(DefaultGlobal).onBadRequest(rh, e.getMessage)
        (rh, Left(r))
      },
      rh => server.getHandlerFor(rh) match {
        case directResult @ Left(_) => (rh, directResult)
        case Right((taggedRequestHeader, handler, application)) => (taggedRequestHeader, Right((handler, application)))
      }
    )

    // Call onRequestCompletion after all request processing is done. Protected with an AtomicBoolean to ensure can't be executed more than once.
    val alreadyClean = new java.util.concurrent.atomic.AtomicBoolean(false)
    def cleanup() {
//  play does no longer call this
//      if (!alreadyClean.getAndSet(true)) {
//        play.api.Play.maybeApplication.foreach(_.global.onRequestCompletion(requestHeader))
//      }
    }

    trait Response {
      def handle(result: Result): Unit
    }

    def cleanFlashCookie(result: Result): Result = {
      val header = result.header

      val flashCookie = {
        header.headers.get(HeaderNames.SET_COOKIE)
          .map(Cookies.decodeCookieHeader)
          .flatMap(_.find(_.name == Flash.COOKIE_NAME)).orElse {
            Option(requestHeader.flash).filterNot(_.isEmpty).map { _ =>
              Flash.discard.toCookie
            }
          }
      }

      flashCookie.fold(result) { newCookie =>
        result.withHeaders(HeaderNames.SET_COOKIE -> Cookies.mergeCookieHeader(header.headers.getOrElse(HeaderNames.SET_COOKIE, ""), Seq(newCookie)))
      }
    }

    handler match {

      //execute normal action
      case Right((action: EssentialAction, app)) =>
        val a = EssentialAction { rh =>
          import play.api.libs.iteratee.Execution.Implicits.trampoline
          action(rh).recoverWith {
            case error => app.errorHandler.onServerError(requestHeader, error)
          }
        }
        handleAction(a, Some(app))

      //handle all websocket request as bad, since websocket are not handled
      //handle bad websocket request
      case Right((ws: WebSocket, app)) =>
        Logger("play").trace("Bad websocket request")
        val a = EssentialAction(_ => Accumulator.done(Results.BadRequest))
        handleAction(a, Some(app))

      case Left(e) =>
        Logger("play").trace("No handler, got direct result: " + e)
        val a = EssentialAction(_ => Accumulator.done(e))
        handleAction(a, None)
    }

    def handleAction(action: EssentialAction, app: Option[Application]) {
      val bodyParser = action(requestHeader)

      import play.api.libs.iteratee.Execution.Implicits.trampoline

      // Remove Except: 100-continue handling, since it's impossible to handle it
      //val expectContinue: Option[_] = requestHeader.headers.get("Expect").filter(_.equalsIgnoreCase("100-continue"))

      val eventuallyResult: Future[Result] = feedBodyParser(bodyParser)

      val eventuallyResultWithError = eventuallyResult.recoverWith {
        case error =>
          Logger("play").error("Cannot invoke the action, eventually got an error: " + error)
          app.fold(DefaultGlobal.onError(requestHeader, error)) {
            _.errorHandler.onServerError(requestHeader, error)
          }
      }.map { result => cleanFlashCookie(result) }

      pushPlayResultToServletOS(eventuallyResultWithError, cleanup)
    }

    onFinishService()
  }

  private def getHttpParameters(request: HttpServletRequest): Map[String, Seq[String]] = {
    request.getQueryString match {
      case null | "" => Map.empty
      case queryString => queryString.replaceFirst("^?", "").split("&").flatMap { queryElement =>
        val array = queryElement.split("=")
        array.length match {
          case 0 => None
          case 1 => Some(URLDecoder.decode(array(0), "UTF-8") -> "")
          case _ => Some(URLDecoder.decode(array(0), "UTF-8") -> URLDecoder.decode(array(1), "UTF-8"))
        }
      }.groupBy(_._1).map { case (key, value) => key -> value.map(_._2).toSeq }
    }
  }
}
