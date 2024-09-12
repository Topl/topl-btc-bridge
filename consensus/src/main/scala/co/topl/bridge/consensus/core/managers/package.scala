package co.topl.bridge.consensus.core

import co.topl.brambl.utils.Encoding
import com.google.protobuf.struct.ListValue
import com.google.protobuf.struct.NullValue
import com.google.protobuf.struct.Struct
import com.google.protobuf.struct.Value
import io.circe.Json

package object managers {

  
  def toStruct(json: Json): Value =
    json.fold[Value](
      jsonNull = Value(Value.Kind.NullValue(NullValue.NULL_VALUE)),
      jsonBoolean = b => Value(Value.Kind.BoolValue(b)),
      jsonNumber = n => Value(Value.Kind.NumberValue(n.toDouble)),
      jsonString = s => Value(Value.Kind.StringValue(s)),
      jsonArray =
        l => Value(Value.Kind.ListValue(ListValue(l.map(toStruct(_))))),
      jsonObject = jo =>
        Value(Value.Kind.StructValue(Struct(jo.toMap.map({ case (k, v) =>
          k -> toStruct(v)
        }))))
    )


  def templateFromSha(decodedHex: Array[Byte], min: Long, max: Long) = 
        s"""
        {"threshold":1,"innerTemplates":[{"left":{"routine":"Sha256","digest":"${Encoding.encodeToBase58(decodedHex)}","type":"digest"},"right":{"chain":"header","min":$min,"max":$max,"type":"height"},"type":"and"}],"type":"predicate"}
        """
}
