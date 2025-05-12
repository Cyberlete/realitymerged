package org.reality.aci

import java.lang.reflect.Constructor

import cats.effect.Sync
import cats.syntax.all._

import org.reality.kernel.Ω
import org.reality.kryo.JsonSerializer

import io.circe.{Decoder, Encoder}

class StateChannelRuntime(
  val address: String,
  cellClass: Class[_],
  inputClass: Class[_],
  decoder: Decoder[_],
  encoder: Encoder[Any]
) {

  private val cellCtor: Constructor[_] =
    try
      cellClass.getConstructor(inputClass)
    catch {
      case e: NoSuchMethodException => cellClass.getConstructor(classOf[Ω])
    }

  def createCell[F[_]](input: Ω): StdCell[F] =
    cellCtor.newInstance(input).asInstanceOf[StdCell[F]]

  def deserializeInput[F[_]](inputBytes: Array[Byte])(implicit F: Sync[F]): F[Ω] = // TODO change it to EitherT[F,Error,Ω], Test it!
    for {
      deserilizeOutput <- F.delay(JsonSerializer.deserialize(inputBytes)(decoder))
      deserializedInstance <- F.fromEither(deserilizeOutput)
      _ <- F.unlessA(inputClass.isAssignableFrom(deserializedInstance.getClass)) {
        F.raiseError(new Throwable(s"input is not an instance of ${inputClass.getName}"))
      }
    } yield deserializedInstance.asInstanceOf[Ω]

  def deserializeCast[F[_], T](bytes: Array[Byte])(implicit F: Sync[F]): F[T] =
    for {
      deserilizeOutput <- F.delay(JsonSerializer.deserialize(bytes)(decoder))
      deserializedInstance <- F.fromEither(deserilizeOutput)
      _ <- F.unlessA(inputClass.isAssignableFrom(deserializedInstance.getClass)) {
        F.raiseError(new Throwable(s"input is not an instance of ${inputClass.getName}"))
      }
    } yield deserializedInstance.asInstanceOf[T]

  def serialize[F[_]](any: Any)(implicit F: Sync[F]): F[Array[Byte]] = // TODO: Test it!
    F.delay {
      JsonSerializer.serialize(any)(encoder)
    }
}
