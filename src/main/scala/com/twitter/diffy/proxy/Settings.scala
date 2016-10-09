package com.twitter.diffy.proxy

import java.net.InetSocketAddress

import com.twitter.util.Duration

/**
  *  全局变量设置类 以下变量可以通过启动脚本传入 扩展可以在其中增加控制参数
  */
case class Settings(
  datacenter: String,
  servicePort:InetSocketAddress,
  candidate: Target,
  primary: Target,
  secondary: Target,
  protocol: String,
  clientId: String,
  pathToThriftJar: String,
  serviceClass: String,
  serviceName: String,
  apiRoot: String,
  enableThriftMux: Boolean,
  relativeThreshold: Double,
  absoluteThreshold: Double,
  teamEmail: String,
  emailDelay: Duration,
  rootUrl: String,
  allowHttpSideEffects: Boolean,
  excludeHttpHeadersComparison: Boolean,
  skipEmailsWhenNoErrors: Boolean)

case class Target(path: String)
