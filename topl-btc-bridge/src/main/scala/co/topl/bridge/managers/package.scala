package co.topl.bridge

import co.topl.brambl.utils.Encoding

package object managers {

  def templateFromSha(decodedHex: Array[Byte]) = s"""{
                "threshold":1,
                "innerTemplates":[
                  {
                    "left": {"routine":"ExtendedEd25519","entityIdx":0,"type":"signature"},
                    "right": {"routine":"Sha256","digest": "${Encoding
      .encodeToBase58(decodedHex)}","type":"digest"},
                    "type": "or"
                  }
                ],
                "type":"predicate"}
              """
}
