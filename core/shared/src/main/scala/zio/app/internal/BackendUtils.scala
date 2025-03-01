package zio.app.internal

import boopickle.CompositePickler
import boopickle.Default._
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.{HttpHeaderNames, HttpHeaderValues}
import zhttp.http._
import zio._
import zio.stream.{UStream, ZStream}

import java.nio.ByteBuffer

object BackendUtils {
  implicit private val exPickler: CompositePickler[Throwable] = exceptionPickler

  private val bytesContent: Header = Header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.BYTES)

  def makeRoute[R, E: Pickler, A: Pickler, B: Pickler](
      service: String,
      method: String,
      call: A => ZIO[R, E, B]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collectM { case post @ Method.POST -> Root / `service0` / `method0` =>
      post.content match {
        case HttpData.CompleteData(data) =>
          val byteBuffer = ByteBuffer.wrap(data.toArray)
          val unpickled  = Unpickle[A].fromBytes(byteBuffer)
          call(unpickled)
            .map(ZioResponse.succeed)
            .catchAllCause(causeToResponseZio[E](_))
            .map(pickle[ZioResponse[E, B]](_))
        case _ => UIO(Response.ok)
      }
    }
  }

  def makeRouteNullary[R, E: Pickler, A: Pickler](
      service: String,
      method: String,
      call: ZIO[R, E, A]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collectM { case Method.GET -> Root / `service0` / `method0` =>
      call
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseZio[E](_))
        .map(pickle[ZioResponse[E, A]](_))
    }
  }

  def makeRouteStream[R, E: Pickler, A: Pickler, B: Pickler](
      service: String,
      method: String,
      call: A => ZStream[R, E, B]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collect { case post @ Method.POST -> Root / `service0` / `method0` =>
      post.content match {
        case HttpData.CompleteData(data) =>
          val byteBuffer = ByteBuffer.wrap(data.toArray)
          val unpickled  = Unpickle[A].fromBytes(byteBuffer)
          makeStreamResponse(call(unpickled))
        case _ => Response.ok
      }
    }
  }

  def makeRouteNullaryStream[R, E: Pickler, A: Pickler](
      service: String,
      method: String,
      call: ZStream[R, E, A]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collect { case Method.GET -> Root / `service0` / `method0` =>
      makeStreamResponse(call)
    }
  }

  private def pickle[A: Pickler](value: A): UResponse = {
    val bytes: ByteBuffer = Pickle.intoBytes(value)
    val byteBuf           = Unpooled.wrappedBuffer(bytes)
    val httpData          = HttpData.fromByteBuf(byteBuf)

    Response.http(status = Status.OK, headers = List(bytesContent), content = httpData)
  }

  private def makeStreamResponse[A: Pickler, E: Pickler, R](
      stream: ZStream[R, E, A]
  ): Response.HttpResponse[R, Nothing] = {
    val responseStream: ZStream[R, Nothing, Byte] =
      stream
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseStream(_))
        .mapConcatChunk { a =>
          Chunk.fromByteBuffer(Pickle.intoBytes(a))
        }

    Response.http(content = HttpData.fromStream(responseStream))
  }

  private def causeToResponseStream[E: Pickler](cause: Cause[E]): UStream[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure)      => ZStream(ZioResponse.fail(failure))
      case Cause.Die(die)           => ZStream(ZioResponse.die(die))
      case Cause.Interrupt(fiberId) => ZStream(ZioResponse.interrupt(fiberId.seqNumber))
    }.get

  private def causeToResponseZio[E: Pickler](cause: Cause[E]): UIO[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure)      => UIO(ZioResponse.fail(failure))
      case Cause.Die(die)           => UIO(ZioResponse.die(die))
      case Cause.Interrupt(fiberId) => UIO(ZioResponse.interrupt(fiberId.seqNumber))
    }.get
}

object CustomPicklers {
  implicit val nothingPickler: Pickler[Nothing] = new Pickler[Nothing] {
    override def pickle(obj: Nothing)(implicit state: PickleState): Unit = throw new Error("IMPOSSIBLE")
    override def unpickle(implicit state: UnpickleState): Nothing        = throw new Error("IMPOSSIBLE")
  }
}
