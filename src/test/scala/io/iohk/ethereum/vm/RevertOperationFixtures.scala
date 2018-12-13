package io.iohk.ethereum.vm

import akka.util.ByteString
import javax.xml.bind.DatatypeConverter

trait RevertOperationFixtures {

  // eip-140 - testcase
  val revertCode = Assembly(
    PUSH13, hexToByteString("72657665727465642064617461"),
    PUSH1, 0x00,
    SSTORE,
    PUSH32, hexToByteString("726576657274206d657373616765000000000000000000000000000000000000"),
    PUSH1, 0x00,
    MSTORE,
    PUSH1, hexToByteString("0e"),
    PUSH1, 0x00,
    REVERT
  )

  // taken from eip-140 testcase
  val expectedRevertReturnData = hexToByteString("726576657274206d657373616765")
  val usedGasByRevertAssembly = 20024

  private def hexToByteString(hexString: String) = ByteString(DatatypeConverter.parseHexBinary(hexString))

}
