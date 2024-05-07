package co.topl.bridge.modules

import com.google.protobuf.struct.ListValue
import com.google.protobuf.struct.NullValue
import com.google.protobuf.struct.Struct
import com.google.protobuf.struct.Value
import io.circe.Json

trait CommonTxOps {


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

}
