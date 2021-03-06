package com.tencent.angel.serving.core

case class CoreExceptions(message: String) extends Exception(message)


case class RouteExceptions(message: String) extends RuntimeException(message)


case class AdaptExceptions(message: String) extends RuntimeException(message)


case class MonitorExceptions(message: String) extends RuntimeException(message)


case class LoadExceptions(message: String) extends Exception(message)


case class ResourceExceptions(message: String) extends RuntimeException(message)


case class ConfigExceptions(message: String) extends RuntimeException(message)


case class ManagerExceptions(message: String) extends RuntimeException(message)

case class FailedPreconditions(message: String) extends Exception(message)

case class InvalidArguments(message: String) extends Exception(message)

case class NotFoundExceptions(message: String) extends Exception(message)




