package fundertow.http

import java.util.function.Consumer

import scala.collection.JavaConverters._

import io.undertow.util.HeaderMap
import io.undertow.util.HeaderValues
import io.undertow.util.HttpString

// FIXME make this better, probably copy headers from http4s, they are awesome
final class HttpHeaders private (private val headers: HeaderMap) extends AnyVal {

  def size: Int = headers.size
  def isEmpty: Boolean = size == 0

  def iterator: Iterator[HeaderValues] = headers.iterator().asScala

  def get(headerName: HttpString): Option[HeaderValues] = Option(headers.get(headerName))
  def getFirst(headerName: HttpString): Option[String] = try {
    Option(headers.getFirst(headerName))
  } catch {
    case _: NoSuchElementException => None
  }

  def contains(headerName: HttpString): Boolean = headers.contains(headerName)

  def put(headerName: HttpString, value: String): HttpHeaders = {
    val n = copy()
    n.add(headerName, value)
    new HttpHeaders(n)
  }

  def remove(headerName: HttpString): HttpHeaders = {
    val n = copy()
    n.remove(headerName)
    new HttpHeaders(n)
  }

  def toHeaderMap: HeaderMap = headers

  // not efficient
  private def copy() = {
    val n = new HeaderMap()
    headers.iterator().forEachRemaining {
      new Consumer[HeaderValues] {
        override def accept(value: HeaderValues): Unit = {
          val _ = n.addAll(value.getHeaderName, value.iterator().asScala.toList.asJavaCollection)
        }
      }
    }
    n
  }

}

object HttpHeaders {

  val empty = apply(new HeaderMap())

  def apply(headers: HeaderMap): HttpHeaders = new HttpHeaders(headers)

  def of(values: (HttpString, String)*): HttpHeaders = new HttpHeaders({
    val headers = new HeaderMap()
    values.foreach { case (name, value) =>
      headers.add(name, value)
    }
    headers
  })

}
