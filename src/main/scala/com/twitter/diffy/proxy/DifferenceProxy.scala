package com.twitter.diffy.proxy

import javax.inject.Singleton
import com.google.inject.Provides
import com.twitter.diffy.analysis._
import com.twitter.diffy.lifter.Message
import com.twitter.finagle._
import com.twitter.inject.TwitterModule
import com.twitter.logging.Logger
import com.twitter.util._

/**
  * diff主流程类
  * 1.完成发送请求
  * 2.解析请求结果
  * 3.分析diffy结果产生报告
  */
object DifferenceProxyModule extends TwitterModule {
  @Provides
  @Singleton
  def providesDifferenceProxy(
    settings: Settings,
    collector: InMemoryDifferenceCollector,
    joinedDifferences: JoinedDifferences,
    analyzer: DifferenceAnalyzer
  ): DifferenceProxy =
    settings.protocol match {
      case "thrift" => ThriftDifferenceProxy(settings, collector, joinedDifferences, analyzer)
      case "http" => SimpleHttpDifferenceProxy(settings, collector, joinedDifferences, analyzer)
    }
}

object DifferenceProxy {
  object NoResponseException extends Exception("No responses provided by diffy")
  val NoResponseExceptionFuture = Future.exception(NoResponseException)
  val log = Logger(classOf[DifferenceProxy])
}

trait DifferenceProxy {
  import DifferenceProxy._

  type Req
  type Rep
  type Srv <: ClientService[Req, Rep]

  val server: ListeningServer
  val settings: Settings
  var lastReset: Time = Time.now

  def serviceFactory(serverset: String, label: String): Srv

  def liftRequest(req: Req): Future[Message]
  def liftResponse(rep: Try[Rep]): Future[Message]

  // Clients for services
  val candidate = serviceFactory(settings.candidate.path, "candidate")
  val primary   = serviceFactory(settings.primary.path, "primary")
  val secondary = serviceFactory(settings.secondary.path, "secondary")

  val collector: InMemoryDifferenceCollector

  val joinedDifferences: JoinedDifferences

  val analyzer: DifferenceAnalyzer

  private[this] lazy val multicastHandler =
    new SequentialMulticastService(Seq(primary.client, candidate.client, secondary.client))

  /** diff方法主体 req为请求的url log中可以记录该请求 **/
  def proxy = new Service[Req, Rep] {
    override def apply(req: Req): Future[Rep] = {
      /** 发送请求获取response **/
      val rawResponses =
        multicastHandler(req) respond {
          case Return(_) => log.debug("success networking")
          case Throw(t) => log.debug(t, "error networking")
        }

      /** 解析返回数据 可以在解析数据之前 判断header中的状态吗 输出到控制台或者抛出异常  **/
      /** response是一个Seq(队列) Seq(primaryResponse, candidateResponse, secondaryResponse)  **/
      val responses: Future[Seq[Message]] =
        rawResponses flatMap { reps =>
          Future.collect(reps map liftResponse) respond {
            case Return(rs) =>
              log.debug(s"success lifting ${rs.head.endpoint}")

            case Throw(t) => log.debug(t, "error lifting")
          }
        }

      responses foreach {
        case Seq(primaryResponse, candidateResponse, secondaryResponse) =>
          liftRequest(req) respond {
            case Return(m) =>
              log.debug(s"success lifting request for ${m.endpoint}")

            case Throw(t) => log.debug(t, "error lifting request")
          } foreach { req =>
            analyzer(req, candidateResponse, primaryResponse, secondaryResponse)
          }
      }

      NoResponseExceptionFuture
    }
  }

  def clear() = {
    lastReset = Time.now
    analyzer.clear()
  }
}
