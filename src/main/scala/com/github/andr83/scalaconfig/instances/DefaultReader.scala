package com.github.andr83.scalaconfig.instances

import java.util.Properties

import com.github.andr83.scalaconfig.{FakePath, Reader}
import com.typesafe.config.{Config, ConfigValue}

import scala.collection.JavaConverters._
import scala.collection.compat._
import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}
import scala.language.higherKinds

/**
  * @author andr83
  */
trait DefaultReader {
  implicit val stringReader: Reader[String] = Reader.pure((config: Config, path: String) => config.getString(path))

  implicit val symbolReader: Reader[Symbol] = Reader.pure((config: Config, path: String) => Symbol(config.getString(path)))

  implicit val intReader: Reader[Int] = Reader.pure((config: Config, path: String) => config.getInt(path))

  implicit val longReader: Reader[Long] = Reader.pure((config: Config, path: String) => config.getLong(path))

  implicit val floatReader: Reader[Float] = Reader.pure((config: Config, path: String) => config.getNumber(path).floatValue())

  implicit val doubleReader: Reader[Double] = Reader.pure((config: Config, path: String) => config.getDouble(path))

  implicit val booleanReader: Reader[Boolean] = Reader.pure((config: Config, path: String) => config.getBoolean(path))

  implicit val finiteDurationReader: Reader[FiniteDuration] = Reader.pure((config: Config, path: String) => {
    val length = config.getDuration(path, java.util.concurrent.TimeUnit.NANOSECONDS)
    FiniteDuration(length, NANOSECONDS)
  })

  implicit val configValueReader: Reader[ConfigValue] = Reader.pure((config: Config, path: String) => config.getValue(path))

  implicit val configReader: Reader[Config] = Reader.pure((config: Config, path: String) => config.getConfig(path))

  implicit def optReader[A: Reader]: Reader[Option[A]] = Reader.pureV((config: Config, path: String) => {
    if (config.hasPath(path)) {
      implicitly[Reader[A]].apply(config, path) match {
        case Right(a) => Right(Some(a))
        case Left(errors) => Left(errors)
      }
    } else {
      Right(None)
    }
  })

  implicit def traversableReader[A: Reader, C[_]](implicit factory: Factory[A, C[A]]): Reader[C[A]] = Reader.pureV((config: Config, path: String) => {
    val reader = implicitly[Reader[A]]
    val list = config.getList(path).asScala

    val (errors, res) = list map (item => {
      val entryConfig = item.atPath(FakePath)
      reader(entryConfig, FakePath)
    }) partition (_.isLeft)

    if (errors.nonEmpty) {
      Left(errors.flatMap(_.left.get).toSeq)
    } else {
      val builder = factory.newBuilder
      builder.sizeHint(list.size)
      res.foreach {
        case Right(a) => builder += a
        case _ =>
      }
      Right(builder.result())
    }
  })

  def mapReader[A: Reader](collapsed: Boolean): Reader[Map[String, A]] = Reader.pureV((config: Config, path: String) => {
    val reader = implicitly[Reader[A]]
    val obj = config.getConfig(path)
    val entries = if (collapsed) obj.entrySet() else obj.root().entrySet()
    val (errors, res) = entries.asScala.map(e => {
      val entryConfig = e.getValue.atPath(FakePath)
      val key = if (collapsed) e.getKey.stripPrefix("\"").stripSuffix("\"") else e.getKey
      key -> reader(entryConfig, FakePath)
    }).partition(_._2.isLeft)
    if (errors.nonEmpty) {
      Left(errors.flatMap(_._2.left.get).toSeq)
    } else {
      Right(res.map {
        case (k, Right(a)) => k -> a
        case _ => throw new IllegalStateException
      }.toMap)
    }
  })

  implicit def mapReaderGeneric[A: Reader]: Reader[Map[String, A]] = mapReader(collapsed = false)
  implicit def mapReaderAnyVal[A <: AnyVal : Reader]: Reader[Map[String, A]] = mapReader(collapsed = true)
  implicit def mapReaderString: Reader[Map[String, String]] = mapReader(collapsed = true)
  implicit def mapReaderInt: Reader[Map[String, Int]] = mapReader(collapsed = true)

  implicit val mapStringAnyReader: Reader[Map[String, AnyRef]] = Reader.pure((config: Config, path: String) => {
    val obj = config.getConfig(path)
    obj.root().unwrapped().asScala.toMap
  })

  implicit val propertiesReader: Reader[Properties] = Reader.pureV((config: Config, path: String) => {
    mapStringAnyReader(config, path) match {
      case Right(map) =>
        val props = new Properties()
        map.foreach { case (k, v)=> props.put(k, v)}
        Right(props)
      case Left(errors) => Left(errors)
    }
  })
}

object DefaultReader extends DefaultReader
