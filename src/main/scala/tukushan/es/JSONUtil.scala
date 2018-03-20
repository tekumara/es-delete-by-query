package tukushan.es

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization

trait JSONUtil {

  implicit val formats: Formats = DefaultFormats

  def fromJSON[T](jsonString: String)(implicit mf: Manifest[T]): T =
    try {
      Serialization.read(jsonString)
    } catch {
      case e: Exception => throw new RuntimeException(s"Error deserializing ${mf.runtimeClass.getSimpleName} from JSON: $jsonString", e)
    }

  def toJSON(objectToWrite: AnyRef): String = Serialization.write(objectToWrite)

}
