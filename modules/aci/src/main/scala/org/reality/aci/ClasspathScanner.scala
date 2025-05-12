package org.reality.aci

import java.util.{Enumeration => JEnumeration}

import cats.conversions.all.autoWidenBifunctor
import cats.effect.Sync

import scala.jdk.CollectionConverters._

import org.reality.utils.streamLiftK

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ClasspathScanner[F[_]](runtimeLoader: RuntimeLoader[F])(implicit F: Sync[F]) {

  private val logger = Slf4jLogger.getLogger[F].mapK(streamLiftK)

  val MANIFEST_FILE = "state-channel.info"
  val classLoader: ClassLoader = this.getClass.getClassLoader

  class CloseableIterator[A](iterator: Iterator[A], closeFn: () => Unit) extends Iterator[A] with AutoCloseable {
    override def hasNext: Boolean = iterator.hasNext

    override def next(): A = iterator.next()

    override def close(): Unit = closeFn()
  }

  import cats.effect.Resource
  import java.io.Closeable
  import java.net.URL

  def scanClasspath: Stream[F, Map[String, StateChannelRuntime]] = {
    val urlEnumeration = classLoader.getResources(MANIFEST_FILE)

    val wrappedEnumeration = new JEnumeration[URL] {
      override def hasMoreElements: Boolean = urlEnumeration.hasMoreElements

      override def nextElement(): URL = urlEnumeration.nextElement()

      def close(): Unit = urlEnumeration.asInstanceOf[Closeable].close()
    }.asScala

    val wrappedEnumerationAutoCloseable = new AutoCloseable {
      def close(): Unit = urlEnumeration.asInstanceOf[Closeable].close()

      val iter: Iterator[URL] = wrappedEnumeration
    }
    Stream
      .resource(Resource.make(F.delay(wrappedEnumeration))(_ => F.delay(wrappedEnumerationAutoCloseable.close())))
      .flatMap { urls =>
        Stream
          .emits(urls.toSeq)
          .evalMap(runtimeLoader.loadRuntime(classLoader, _))
          .map(runtime => Map(runtime.address -> runtime))
          .reduce(_ ++ _) // combine all maps into a single map
      }
  }

  implicit class EnumerationOps[T](enumeration: JEnumeration[T]) {

    def toIterator: Iterator[T] = new Iterator[T] {
      override def hasNext: Boolean = enumeration.hasMoreElements

      override def next(): T = enumeration.nextElement()
    }
  }

}
