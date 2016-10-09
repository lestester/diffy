package com.twitter.diffy.lifter

import com.google.common.net.{HttpHeaders, MediaType}
import com.twitter.io.Charsets
import com.twitter.logging.Logger
import com.twitter.util.{Try, Future}

import org.jboss.netty.handler.codec.http.{HttpResponse, HttpRequest}

import scala.collection.JavaConversions._

/**
  * 返回值解析类 每次请求 DifferenceProxy 调用liftRequest方法 解析返回结果
  *   TODO 目前没有对请求状态的判断，可以参考在控制台或者请求接口处输出请求状态
  **/
object HttpLifter {
  val ControllerEndpointHeaderName = "X-Action-Name"

  def contentTypeNotSupportedException(contentType: String) = new Exception(s"Content type: $contentType is not supported")
  def contentTypeNotSupportedExceptionFuture(contentType: String) = Future.exception(contentTypeNotSupportedException(contentType))

  case class MalformedJsonContentException(cause: Throwable)
    extends Exception("Malformed Json content")
  {
    initCause(cause)
  }
}

class HttpLifter(excludeHttpHeadersComparison: Boolean) {
  import HttpLifter._

  private[this] val log = Logger(classOf[HttpLifter])
  private[this] def headersMap(response: HttpResponse): Map[String, Any] = {
    if(!excludeHttpHeadersComparison) {
      val rawHeaders = response.headers.entries().map { header =>
        (header.getKey, header.getValue)
      }.toSeq

      val headers = rawHeaders groupBy { case (name, _) => name } map { case (name, values) =>
        name -> (values map { case (_, value) => value } sorted)
      }

      Map( "headers" -> FieldMap(headers))
    } else Map.empty
  }

  def liftRequest(req: HttpRequest): Future[Message] = {
    val canonicalResource = Option(req.headers.get("Canonical-Resource"))
    Future.value(Message(canonicalResource, FieldMap(Map("request"-> req.toString))))
  }

  /** 对请求返回 解析方法 1.解析方式根据header中的contentType 选择合适的解析方式进行解析 **/
  def liftResponse(resp: Try[HttpResponse]): Future[Message] = {
    Future.const(resp) flatMap { r: HttpResponse =>
      val mediaTypeOpt: Option[MediaType] =
        Option(r.headers.get(HttpHeaders.CONTENT_TYPE)) map { MediaType.parse }
      
      val contentLengthOpt = Option(r.headers.get(HttpHeaders.CONTENT_LENGTH))

      /** header supplied by macaw, indicating the controller reached **/
      val controllerEndpoint = Option(r.headers.get(ControllerEndpointHeaderName))

      /** mediaTypeOpt 返回值类型  contentLengthOpt返回值长度 TODO可以加入返回状态码**/
      (mediaTypeOpt, contentLengthOpt) match {
        /** When Content-Length is 0, only compare headers **/
        case (_, Some(length)) if length.toInt == 0 =>
          Future.const(
            Try(Message(controllerEndpoint, FieldMap(headersMap(r))))
          )

        /** When Content-Type is set as application/json, lift as Json **/
        case (Some(mediaType), _) if mediaType.is(MediaType.JSON_UTF_8) || mediaType.toString == "application/json" => {
          val jsonContentTry = Try {
            JsonLifter.decode(r.getContent.toString(Charsets.Utf8))
          }

          Future.const(jsonContentTry map { jsonContent =>
            val responseMap = Map(
              r.getStatus.getCode.toString -> (Map(
                "content" -> jsonContent,
                "chunked" -> r.isChunked
              ) ++ headersMap(r))
            )

            Message(controllerEndpoint, FieldMap(responseMap))
          }).rescue { case t: Throwable =>
            Future.exception(new MalformedJsonContentException(t))
          }
        }

        /** When Content-Type is set as text/html, lift as Html **/
        case (Some(mediaType), _)
          if mediaType.is(MediaType.HTML_UTF_8) || mediaType.toString == "text/html" => {
            val htmlContentTry = Try {
              HtmlLifter.lift(HtmlLifter.decode(r.getContent.toString(Charsets.Utf8)))
            }

            Future.const(htmlContentTry map { htmlContent =>
              val responseMap = Map(
                r.getStatus.getCode.toString -> (Map(
                  "content" -> htmlContent,
                  "chunked" -> r.isChunked
                ) ++ headersMap(r))
              )

              Message(controllerEndpoint, FieldMap(responseMap))
            })
          }

        /** When content type is not set, only compare headers **/
        case (None, _) => {
          Future.const(Try(
            Message(controllerEndpoint, FieldMap(headersMap(r)))))
        }

        case (Some(mediaType), _) => {
          log.debug(s"Content type: $mediaType is not supported")
          contentTypeNotSupportedExceptionFuture(mediaType.toString)
        }
      }
    }
  }
}
